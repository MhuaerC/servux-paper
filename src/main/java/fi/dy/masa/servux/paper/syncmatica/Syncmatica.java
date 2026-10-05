package fi.dy.masa.servux.paper.syncmatica;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fi.dy.masa.servux.paper.network.PayloadTransport;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

/** Compatible with the public Syncmatica CORE, FEATURE, MODIFY and CORE_EX wire protocols. */
public final class Syncmatica implements Listener, PluginMessageListener {
    private static final List<String> PACKETS = List.of("register_version", "feature_request", "feature", "confirm_user",
            "register_metadata", "cancel_share", "request_download", "send_litematic", "received_litematic",
            "finished_litematic", "cancel_litematic", "remove_syncmatic", "modify", "modify_request",
            "modify_request_accept", "modify_request_deny", "modify_finish");
    private static final String FEATURES = "CORE\nFEATURE\nMODIFY\nCORE_EX";
    private static final int CHUNK = 16384;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final JavaPlugin plugin;
    private final Path directory;
    private final Map<UUID, SharedPlacement> placements = new LinkedHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Lock> locks = new HashMap<>();
    private BukkitTask maintenance;
    private long storedBytes;

    private static final class Session {
        boolean versionReceived, ready, extended, modify;
        Upload upload;
        Download download;
        long lastActivity = System.currentTimeMillis();
    }
    private static final class Upload {
        final SharedPlacement placement;
        final Path path;
        final OutputStream stream;
        final MessageDigest digest;
        long bytes;
        Upload(SharedPlacement placement, Path directory) throws IOException {
            this.placement = placement;
            path = Files.createTempFile(directory, "upload-", ".part");
            stream = Files.newOutputStream(path);
            digest = md5();
        }
    }
    private record Download(UUID id, InputStream stream) {}
    private record Lock(UUID player, long deadline) {}

    public Syncmatica(JavaPlugin plugin) {
        this.plugin = plugin;
        directory = plugin.getDataFolder().toPath().resolve("syncmatica");
    }

    public void register() throws IOException {
        Files.createDirectories(directory);
        load();
        for (String packet : PACKETS) {
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, "syncmatica:" + packet, this);
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, "syncmatica:" + packet);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        maintenance = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 100, 100);
        Bukkit.getOnlinePlayers().forEach(this::hello);
    }

    public void unregister() {
        if (maintenance != null) maintenance.cancel();
        for (UUID player : List.copyOf(sessions.keySet())) close(player);
        locks.clear();
        HandlerList.unregisterAll(this);
        for (String packet : PACKETS) {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, "syncmatica:" + packet, this);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, "syncmatica:" + packet);
        }
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> hello(event.getPlayer()), 10);
    }
    @EventHandler public void channel(PlayerRegisterChannelEvent event) {
        if (event.getChannel().equals("syncmatica:register_version")) hello(event.getPlayer());
    }
    @EventHandler public void quit(PlayerQuitEvent event) { close(event.getPlayer().getUniqueId()); }

    private boolean allowed(Player player) {
        return plugin.getConfig().getBoolean("syncmatica.enabled", true) && player.hasPermission("servux.syncmatica");
    }
    private void hello(Player player) {
        if (!player.isOnline() || !allowed(player) || sessions.containsKey(player.getUniqueId())) return;
        sessions.put(player.getUniqueId(), new Session());
        // The suffix requests explicit feature negotiation instead of implying unsupported features.
        send(player, "register_version", b -> b.writeUtf("0.3.15-servux-paper"));
    }

    @Override public void onPluginMessageReceived(String channel, Player player, byte[] payload) {
        if (!allowed(player)) { close(player.getUniqueId()); return; }
        if (payload.length > 32767 || !channel.startsWith("syncmatica:")) return;
        String packet = channel.substring("syncmatica:".length());
        Session session = sessions.get(player.getUniqueId());
        if (session == null) { hello(player); session = sessions.get(player.getUniqueId()); }
        if (session == null) return;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            session.lastActivity = System.currentTimeMillis();
            if (packet.equals("feature_request")) {
                empty(buffer); send(player, "feature", b -> b.writeUtf(FEATURES)); return;
            }
            if (packet.equals("register_version")) {
                String version = buffer.readUtf(128); empty(buffer);
                if (session.ready || session.versionReceived) return;
                session.versionReceived = true;
                if (version.startsWith("0.1.")) confirm(player, session);
                else send(player, "feature_request", b -> {});
                return;
            }
            if (packet.equals("feature")) {
                String featureText = buffer.readUtf(2048); empty(buffer);
                if (session.ready || !session.versionReceived) return;
                Set<String> features = Set.copyOf(List.of(featureText.split("\n")));
                if (!features.contains("CORE")) { close(player.getUniqueId()); return; }
                session.extended = features.contains("CORE_EX");
                session.modify = features.contains("MODIFY");
                confirm(player, session);
                return;
            }
            if (!session.ready) return;
            switch (packet) {
                case "register_metadata" -> share(player, session, buffer);
                case "send_litematic" -> receiveChunk(player, session, buffer);
                case "finished_litematic" -> finishUpload(player, session, buffer);
                case "request_download" -> download(player, session, buffer);
                case "received_litematic" -> {
                    UUID id = buffer.readUUID(); empty(buffer);
                    if (session.download != null && session.download.id().equals(id)) nextChunk(player, session);
                }
                case "cancel_litematic" -> {
                    UUID id = buffer.readUUID(); empty(buffer);
                    if (session.upload != null && session.upload.placement.id().equals(id)) abortUpload(player, session);
                    if (session.download != null && session.download.id().equals(id)) closeDownload(session);
                }
                case "remove_syncmatic" -> remove(player, buffer);
                case "modify_request" -> requestModify(player, session, buffer);
                case "modify_finish" -> finishModify(player, session, buffer);
                default -> { /* Ignore server-only/unknown messages. */ }
            }
        } catch (IOException | RuntimeException error) {
            abortUpload(player, session);
            closeDownload(session);
            locks.entrySet().removeIf(e -> e.getValue().player().equals(player.getUniqueId()));
            plugin.getLogger().warning("Syncmatica rejected " + packet + " from " + player.getName() + ": " + error.getClass().getSimpleName());
        } finally { buffer.release(); }
    }

    private void confirm(Player player, Session session) {
        session.ready = true;
        // Incremental metadata avoids exceeding the custom-payload limit for a large library.
        send(player, "confirm_user", b -> b.writeInt(0));
        for (SharedPlacement placement : placements.values()) metadata(player, session, placement);
    }

    private void share(Player player, Session session, FriendlyByteBuf buffer) throws IOException {
        SharedPlacement placement = SharedPlacement.read(buffer, session.extended, player.getUniqueId(), player.getName());
        empty(buffer);
        long pending = sessions.values().stream().filter(s -> s.upload != null).count();
        boolean duplicate = placements.containsKey(placement.id()) || sessions.values().stream()
                .anyMatch(s -> s.upload != null && s.upload.placement.id().equals(placement.id()));
        if (!player.hasPermission("servux.syncmatica.share") || session.upload != null || duplicate || pending >= 32
                || placements.size() + pending >= Math.clamp(plugin.getConfig().getInt("syncmatica.max_placements", 256), 1, 4096)) {
            id(player, "cancel_share", placement.id()); return;
        }
        if (Files.isRegularFile(file(placement.hash()))) {
            commit(placement); broadcastMetadata(placement);
        } else {
            session.upload = new Upload(placement, directory);
            id(player, "request_download", placement.id());
        }
    }

    private void receiveChunk(Player player, Session session, FriendlyByteBuf buffer) throws IOException {
        UUID id = buffer.readUUID();
        int size = buffer.readInt();
        Upload upload = session.upload;
        if (upload == null || !upload.placement.id().equals(id)) { id(player, "cancel_litematic", id); return; }
        long inFlight = sessions.values().stream().filter(s -> s.upload != null).mapToLong(s -> s.upload.bytes).sum();
        if (!player.hasPermission("servux.syncmatica.share") || size <= 0 || size > CHUNK || size != buffer.readableBytes()
                || upload.bytes + size > limit("syncmatica.max_file_size_mb", 16)
                || storedBytes + inFlight + size > limit("syncmatica.max_storage_mb", 256)) {
            abortUpload(player, session); return;
        }
        byte[] data = new byte[size]; buffer.readBytes(data);
        upload.stream.write(data); upload.digest.update(data); upload.bytes += size;
        id(player, "received_litematic", id);
    }

    private void finishUpload(Player player, Session session, FriendlyByteBuf buffer) throws IOException {
        UUID id = buffer.readUUID(); empty(buffer);
        Upload upload = session.upload;
        if (upload == null || !upload.placement.id().equals(id)) return;
        if (!player.hasPermission("servux.syncmatica.share") || upload.bytes == 0
                || !UUID.nameUUIDFromBytes(upload.digest.digest()).equals(upload.placement.hash())) {
            abortUpload(player, session); return;
        }
        upload.stream.close();
        Path target = file(upload.placement.hash());
        boolean exists = Files.isRegularFile(target);
        if (exists) Files.deleteIfExists(upload.path);
        else { move(upload.path, target); storedBytes += upload.bytes; }
        commit(upload.placement);
        session.upload = null;
        broadcastMetadata(upload.placement);
    }

    private void download(Player player, Session session, FriendlyByteBuf buffer) throws IOException {
        UUID id = buffer.readUUID(); empty(buffer);
        SharedPlacement placement = placements.get(id);
        if (placement == null || session.download != null) { id(player, "cancel_litematic", id); return; }
        session.download = new Download(id, Files.newInputStream(file(placement.hash())));
        nextChunk(player, session);
    }
    private void nextChunk(Player player, Session session) throws IOException {
        Download download = session.download;
        byte[] data = download.stream().readNBytes(CHUNK);
        if (data.length == 0) {
            id(player, "finished_litematic", download.id()); closeDownload(session);
        } else send(player, "send_litematic", b -> { b.writeUUID(download.id()); b.writeInt(data.length); b.writeBytes(data); });
    }

    private boolean mayModify(Player player, SharedPlacement placement) {
        return placement != null && (player.hasPermission("servux.syncmatica.admin")
                || (player.hasPermission("servux.syncmatica.modify") && placement.owner().equals(player.getUniqueId())));
    }
    private void requestModify(Player player, Session session, FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID(); empty(buffer);
        if (!session.modify || !mayModify(player, placements.get(id)) || locks.containsKey(id)) {
            id(player, "modify_request_deny", id); return;
        }
        locks.put(id, new Lock(player.getUniqueId(), System.currentTimeMillis() + 300_000));
        id(player, "modify_request_accept", id);
    }
    private void finishModify(Player player, Session session, FriendlyByteBuf buffer) throws IOException {
        UUID id = buffer.readUUID();
        var position = SharedPlacement.Position.read(buffer, session.extended); empty(buffer);
        SharedPlacement placement = placements.get(id);
        Lock lock = locks.get(id);
        if (lock == null || !lock.player().equals(player.getUniqueId()) || !mayModify(player, placement)
                || lock.deadline() < System.currentTimeMillis()) { id(player, "modify_request_deny", id); return; }
        SharedPlacement updated = placement.move(position, player.getUniqueId(), player.getName());
        commit(updated); locks.remove(id);
        forEachClient((target, client) -> {
            if (client.modify) send(target, "modify", b -> {
                b.writeUUID(id); updated.position().write(b, client.extended);
                if (client.extended) { b.writeUUID(updated.modifier()); b.writeUtf(updated.modifierName()); }
            });
            else { id(target, "remove_syncmatic", id); metadata(target, client, updated); }
        });
    }
    private void remove(Player player, FriendlyByteBuf buffer) throws IOException {
        UUID id = buffer.readUUID(); empty(buffer);
        SharedPlacement placement = placements.get(id);
        if (!mayModify(player, placement)) {
            // Restore a legacy client's locally removed entry when the server denies removal.
            if (placement != null) metadata(player, sessions.get(player.getUniqueId()), placement);
            return;
        }
        Files.deleteIfExists(directory.resolve(id + ".json"));
        placements.remove(id);
        Lock lock = locks.remove(id);
        if (lock != null) {
            Player editing = Bukkit.getPlayer(lock.player());
            if (editing != null) id(editing, "modify_request_deny", id);
        }
        forEachClient((target, session) -> id(target, "remove_syncmatic", id));
        // Files remain while another placement references their hash. Active downloads keep them alive too.
        cleanUnusedFiles();
    }

    private void commit(SharedPlacement placement) throws IOException {
        Path target = directory.resolve(placement.id() + ".json");
        Path temp = Files.createTempFile(directory, "metadata-", ".tmp");
        try { Files.writeString(temp, GSON.toJson(placement)); move(temp, target); }
        finally { Files.deleteIfExists(temp); }
        placements.put(placement.id(), placement);
    }
    private void load() throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".part") || name.endsWith(".tmp")) { Files.deleteIfExists(path); continue; }
                if (!name.endsWith(".json")) continue;
                try {
                    SharedPlacement placement = GSON.fromJson(Files.readString(path), SharedPlacement.class);
                    if (!name.equals(placement.id() + ".json") || !Files.isRegularFile(file(placement.hash()))) continue;
                    placements.put(placement.id(), placement);
                } catch (RuntimeException failure) { plugin.getLogger().warning("Cannot load Syncmatica metadata: " + name); }
            }
        }
        cleanUnusedFiles();
        try (var entries = Files.list(directory)) {
            for (Path path : entries.filter(p -> p.toString().endsWith(".litematic")).toList()) storedBytes += Files.size(path);
        }
    }
    private void cleanUnusedFiles() throws IOException {
        if (sessions.values().stream().anyMatch(s -> s.download != null || s.upload != null)) return;
        Set<String> referenced = new java.util.HashSet<>();
        placements.values().forEach(p -> referenced.add(p.hash() + ".litematic"));
        try (var entries = Files.list(directory)) {
            for (Path path : entries.filter(p -> p.toString().endsWith(".litematic")).toList()) {
                if (!referenced.contains(path.getFileName().toString())) {
                    long bytes = Files.size(path); Files.delete(path); storedBytes = Math.max(0, storedBytes - bytes);
                }
            }
        }
    }

    private void expire() {
        long now = System.currentTimeMillis();
        locks.entrySet().removeIf(e -> {
            if (e.getValue().deadline() >= now) return false;
            Player player = Bukkit.getPlayer(e.getValue().player());
            if (player != null) id(player, "modify_request_deny", e.getKey());
            return true;
        });
        for (var entry : List.copyOf(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player == null || !allowed(player)) { close(entry.getKey()); continue; }
            if (now - session.lastActivity > 120_000) {
                abortUpload(player, session);
                if (session.download != null) id(player, "cancel_litematic", session.download.id());
                closeDownload(session);
            }
        }
        try { cleanUnusedFiles(); } catch (IOException e) { plugin.getLogger().warning("Syncmatica file cleanup failed"); }
    }
    private void close(UUID player) {
        Session session = sessions.remove(player);
        if (session != null) {
            abortUpload(null, session); closeDownload(session);
        }
        locks.entrySet().removeIf(e -> e.getValue().player().equals(player));
    }
    private void abortUpload(Player player, Session session) {
        Upload upload = session.upload;
        if (upload == null) return;
        session.upload = null;
        try { upload.stream.close(); } catch (IOException ignored) {}
        try { Files.deleteIfExists(upload.path); } catch (IOException ignored) {}
        if (player != null) { id(player, "cancel_litematic", upload.placement.id()); id(player, "cancel_share", upload.placement.id()); }
    }
    private static void closeDownload(Session session) {
        if (session.download == null) return;
        try { session.download.stream().close(); } catch (IOException ignored) {}
        session.download = null;
    }
    private long limit(String key, int fallback) { return Math.clamp(plugin.getConfig().getLong(key, fallback), 1, 4096) * 1024 * 1024; }
    private Path file(UUID hash) { return directory.resolve(hash + ".litematic"); }
    private static MessageDigest md5() {
        try { return MessageDigest.getInstance("MD5"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void empty(FriendlyByteBuf buffer) {
        if (buffer.isReadable()) throw new IllegalArgumentException("Trailing packet data");
    }
    private void forEachClient(java.util.function.BiConsumer<Player, Session> action) {
        sessions.forEach((uuid, session) -> {
            Player target = Bukkit.getPlayer(uuid);
            if (target != null && session.ready && allowed(target)) action.accept(target, session);
        });
    }
    private void broadcastMetadata(SharedPlacement placement) { forEachClient((p, s) -> metadata(p, s, placement)); }
    private void metadata(Player player, Session session, SharedPlacement placement) {
        send(player, "register_metadata", b -> placement.write(b, session.extended));
    }
    private static void id(Player player, String packet, UUID id) { send(player, packet, b -> b.writeUUID(id)); }
    private static void send(Player player, String packet, Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buffer);
            byte[] payload = new byte[buffer.readableBytes()]; buffer.readBytes(payload);
            PayloadTransport.send(player, "syncmatica:" + packet, payload);
        } finally { buffer.release(); }
    }
}
