package fi.dy.masa.servux.paper.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
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
import fi.dy.masa.servux.paper.provider.StructureDataProvider;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;

/**
 * Registers and handles the {@code servux:structures} plugin channel via the plain Bukkit
 * {@link org.bukkit.plugin.messaging.Messenger} API, drives the chunk-watch trigger via
 * Paper's {@link PlayerChunkLoadEvent} (the public-API replacement for Fabric's
 * {@code MixinServerChunkLoadingManager} mixin), and handles the structures handshake.
 * <p>
 * MiniHUD only accepts structures metadata between world join and its single retry at the next
 * 20-tick boundary, and its login-time {@code STRUCTURES_REGISTER} is dropped client-side because
 * Paper advertises its channels only after the login packet. So metadata is pushed unchecked at
 * join, the full sync follows once the client registers the channel, and register requests are
 * answered immediately.
 */
public class StructuresChannel implements PluginMessageListener, Listener
{
    public static final String CHANNEL = StructureDataProvider.CHANNEL_ID;
    private static final String PERMISSION = "servux.structures";

    // Channel registration and a register packet can arrive back to back; avoid a duplicate full sync.
    private static final long HANDSHAKE_THROTTLE_TICKS = 15L;

    private final ServuxPaperPlugin plugin;
    private BukkitTask tickTask;
    private final Map<UUID, Long> lastHandshakeTick = new HashMap<>();

    public StructuresChannel(ServuxPaperPlugin plugin)
    {
        this.plugin = plugin;
    }

    public void register()
    {
        StructureDataProvider.INSTANCE.init(this.plugin);

        Bukkit.getMessenger().registerOutgoingPluginChannel(this.plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(this.plugin, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, this.plugin);

        // NOTE: the interval is read once at (re)registration time - changing `structures.update_interval`
        // in config.yml requires a plugin/server restart to take effect for this scheduled task.
        long interval = ServuxPaperConfig.structuresUpdateInterval();
        this.tickTask = Bukkit.getScheduler().runTaskTimer(this.plugin, StructureDataProvider.INSTANCE::tick, interval, interval);
    }

    public void unregister()
    {
        if (this.tickTask != null)
        {
            this.tickTask.cancel();
            this.tickTask = null;
        }

        this.lastHandshakeTick.clear();

        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this.plugin, CHANNEL);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this.plugin, CHANNEL, this);
        HandlerList.unregisterAll(this);
    }

    @EventHandler
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event)
    {
        StructureDataProvider.INSTANCE.onStartedWatchingChunk(event.getPlayer(), event.getChunk());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();

        if (player.hasPermission(PERMISSION))
        {
            ServuxPaperReference.debugLog("structures: pushing metadata to player {} on join", player.getName());
            StructureDataProvider.INSTANCE.sendMetadataUnchecked(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        this.lastHandshakeTick.remove(event.getPlayer().getUniqueId());
        StructureDataProvider.INSTANCE.unregister(event.getPlayer());
    }

    @EventHandler
    public void onPlayerRegisterChannel(PlayerRegisterChannelEvent event)
    {
        if (CHANNEL.equals(event.getChannel()) && event.getPlayer().hasPermission(PERMISSION))
        {
            ServuxPaperReference.debugLog("structures: client registered channel for player {}", event.getPlayer().getName());
            this.handshake(event.getPlayer());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message)
    {
        if (!channel.equals(CHANNEL))
        {
            return;
        }

        ServuxStructuresPacket packet = ServuxStructuresPacket.fromBytes(message);

        if (packet == null)
        {
            ServuxPaperReference.debugLog("structures: received null/invalid packet from player {}", player.getName());
            return;
        }

        if (!player.hasPermission(PERMISSION))
        {
            ServuxPaperReference.debugLog("structures: denying access for player {}, insufficient permissions", player.getName());
            return;
        }

        ServuxPaperReference.debugLog("structures: received packet type '{}' from player {}", packet.getType(), player.getName());

        switch (packet.getType())
        {
            case PACKET_C2S_STRUCTURES_REGISTER -> this.handshake(player);
            case PACKET_C2S_STRUCTURES_UNREGISTER ->
            {
                this.lastHandshakeTick.remove(player.getUniqueId());
                StructureDataProvider.INSTANCE.unregister(player);
            }
            default -> ServuxPaperReference.logger().warn("StructuresChannel#onPluginMessageReceived: unexpected packet type '{}' from player {}", packet.getType(), player.getName());
        }
    }

    private void handshake(Player player)
    {
        UUID uuid = player.getUniqueId();
        long now = StructureDataProvider.currentTick();
        Long last = this.lastHandshakeTick.get(uuid);

        if (last != null && now - last < HANDSHAKE_THROTTLE_TICKS)
        {
            ServuxPaperReference.debugLog("structures: throttling handshake for player {}", player.getName());
            return;
        }

        ServuxPaperReference.debugLog("structures: sending metadata handshake to player {}", player.getName());
        this.lastHandshakeTick.put(uuid, now);
        StructureDataProvider.INSTANCE.registerFresh(player);
    }
}
