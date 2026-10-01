package fi.dy.masa.servux.paper.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import fi.dy.masa.servux.paper.ServuxPaperConfig;
import fi.dy.masa.servux.paper.ServuxPaperPlugin;
import fi.dy.masa.servux.paper.ServuxPaperReference;
import fi.dy.masa.servux.paper.provider.HudDataProvider;
import net.minecraft.nbt.CompoundTag;

/**
 * Registers and handles the {@code servux:hud_metadata} plugin channel via the plain Bukkit
 * {@link org.bukkit.plugin.messaging.Messenger} API (no PacketEvents needed for this channel).
 * Also schedules the periodic weather/data-logger tick broadcast.
 * <p>
 * MiniHUD sends its single metadata request while handling the login packet, before Paper has
 * advertised its channels via {@code minecraft:register}, so Fabric's {@code canSend()} check drops
 * it and the client never retries until a dimension change. To cover this, metadata is pushed
 * unsolicited shortly after join/channel registration unless the client requests it first.
 */
public class HudMetadataChannel implements PluginMessageListener, Listener
{
    public static final String CHANNEL = HudDataProvider.CHANNEL_ID;
    private static final String PERMISSION = "servux.hud_data";
    private static final long METADATA_PUSH_DELAY_TICKS = 10L;

    private final ServuxPaperPlugin plugin;
    private BukkitTask tickTask;
    private final Map<UUID, BukkitTask> pendingMetadataPushes = new HashMap<>();

    public HudMetadataChannel(ServuxPaperPlugin plugin)
    {
        this.plugin = plugin;
    }

    public void register()
    {
        HudDataProvider.INSTANCE.init(this.plugin);

        Bukkit.getMessenger().registerOutgoingPluginChannel(this.plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(this.plugin, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, this.plugin);

        // NOTE: the interval is read once at (re)registration time - changing `hud_data.update_interval`
        // in config.yml requires a plugin/server restart to take effect for this scheduled task.
        long interval = ServuxPaperConfig.hudDataUpdateInterval();
        this.tickTask = Bukkit.getScheduler().runTaskTimer(this.plugin, HudDataProvider.INSTANCE::tick, interval, interval);
    }

    public void unregister()
    {
        if (this.tickTask != null)
        {
            this.tickTask.cancel();
            this.tickTask = null;
        }

        for (BukkitTask task : this.pendingMetadataPushes.values())
        {
            task.cancel();
        }
        this.pendingMetadataPushes.clear();

        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this.plugin, CHANNEL);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this.plugin, CHANNEL, this);
        HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        this.scheduleMetadataPush(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerRegisterChannel(PlayerRegisterChannelEvent event)
    {
        if (CHANNEL.equals(event.getChannel()))
        {
            this.scheduleMetadataPush(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        this.cancelMetadataPush(event.getPlayer().getUniqueId());
        HudDataProvider.INSTANCE.removeSubscriber(event.getPlayer());
    }

    private void scheduleMetadataPush(UUID uuid)
    {
        this.cancelMetadataPush(uuid);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this.plugin, () ->
        {
            this.pendingMetadataPushes.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);

            if (player != null && player.isOnline() && player.hasPermission(PERMISSION))
            {
                ServuxPaperReference.debugLog("hud_data: pushing metadata to player {}", player.getName());
                this.sendMetadata(player);
            }
        }, METADATA_PUSH_DELAY_TICKS);

        this.pendingMetadataPushes.put(uuid, task);
    }

    private void cancelMetadataPush(UUID uuid)
    {
        BukkitTask task = this.pendingMetadataPushes.remove(uuid);

        if (task != null)
        {
            task.cancel();
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message)
    {
        if (!channel.equals(CHANNEL))
        {
            return;
        }

        ServuxHudPacket packet = ServuxHudPacket.fromBytes(message);

        if (packet == null)
        {
            return;
        }

        if (!player.hasPermission(PERMISSION))
        {
            ServuxPaperReference.debugLog("hud_data: denying access for player {}, insufficient permissions", player.getName());
            return;
        }

        switch (packet.getType())
        {
            case PACKET_C2S_METADATA_REQUEST ->
            {
                this.cancelMetadataPush(player.getUniqueId());
                this.sendMetadata(player);
            }
            case PACKET_C2S_SPAWN_DATA_REQUEST -> this.sendSpawnData(player);
            case PACKET_C2S_RECIPE_MANAGER_REQUEST -> HudDataProvider.INSTANCE.sendRecipeManager(player);
            case PACKET_C2S_DATA_LOGGER_REQUEST -> HudDataProvider.INSTANCE.updateLoggerSubscription(player, packet.getCompound());
            case PACKET_C2S_UNREGISTER_REPLY -> HudDataProvider.INSTANCE.removeSubscriber(player);
            default -> ServuxPaperReference.logger().warn("HudMetadataChannel#onPluginMessageReceived: unexpected packet type '{}' from player {}", packet.getType(), player.getName());
        }
    }

    private void sendMetadata(Player player)
    {
        Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
        CompoundTag nbt = HudDataProvider.INSTANCE.buildMetadataNbt(player, spawn);

        this.send(player, ServuxHudPacket.MetadataResponse(nbt));
    }

    private void sendSpawnData(Player player)
    {
        Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
        CompoundTag nbt = HudDataProvider.INSTANCE.buildSpawnNbt(player, spawn);

        this.send(player, ServuxHudPacket.SpawnResponse(nbt));
    }

    private void send(Player player, ServuxHudPacket packet)
    {
        PayloadTransport.send(player, CHANNEL, packet.toBytes());
    }
}
