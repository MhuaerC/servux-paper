package fi.dy.masa.servux.paper.placement;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Decode the extended hit X before vanilla's hit-vector check, then use vanilla placement. */
public final class EasyPlace implements Listener {
    private static final String HANDLER = "servux_paper_easy_place";
    private final JavaPlugin plugin;
    private final Map<UUID, Channel> channels = new HashMap<>();
    private volatile boolean active;

    public EasyPlace(JavaPlugin plugin) { this.plugin = plugin; }

    public void register() {
        active = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getOnlinePlayers().forEach(this::attach);
    }

    public void unregister() {
        active = false;
        HandlerList.unregisterAll(this);
        channels.values().forEach(EasyPlace::detach);
        channels.clear();
    }

    @EventHandler public void join(PlayerJoinEvent event) { attach(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Channel channel = channels.remove(event.getPlayer().getUniqueId());
        if (channel != null) detach(channel);
    }

    private static void detach(Channel channel) {
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER) != null) channel.pipeline().remove(HANDLER);
        });
    }

    private void attach(Player player) {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        channels.put(player.getUniqueId(), channel);
        channel.eventLoop().execute(() -> {
            if (!active || channel.pipeline().get(HANDLER) != null) return;
            channel.pipeline().addBefore("packet_handler", HANDLER, new ChannelDuplexHandler() {
                private final AtomicInteger pending = new AtomicInteger();
                @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                    if (active && message instanceof ServerboundUseItemOnPacket packet) {
                        double x = packet.hitResult().getLocation().x - packet.hitResult().getBlockPos().getX();
                        if (Double.isFinite(x) && x >= 2 && x <= 0xFFFFFF) {
                            if (pending.incrementAndGet() > 64) {
                                pending.decrementAndGet();
                                return;
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                try {
                                    if (active && player.isOnline()) handle(player, packet);
                                } finally { pending.decrementAndGet(); }
                            });
                            return;
                        }
                    }
                    super.channelRead(context, message);
                }
            });
        });
    }

    private void handle(Player bukkit, ServerboundUseItemOnPacket packet) {
        var player = ((CraftPlayer) bukkit).getHandle();
        var level = player.level();
        var hit = packet.hitResult();
        var clicked = hit.getBlockPos();
        double relativeX = hit.getLocation().x - clicked.getX();
        var cleanHit = new BlockHitResult(new Vec3(clicked.getX() + relativeX - Math.floor(relativeX),
                hit.getLocation().y, hit.getLocation().z), hit.getDirection(), clicked, hit.isInside());
        var stack = player.getItemInHand(packet.hand());
        if (!plugin.getConfig().getBoolean("easy_place.enabled", true) || !bukkit.hasPermission("servux.easy_place")
                || !(stack.getItem() instanceof BlockItem item) || !level.hasChunkAt(clicked)
                || !player.isWithinBlockInteractionRange(clicked, 1.0)) {
            reject(bukkit, packet);
            return;
        }
        var context = new BlockPlaceContext(new UseOnContext(player, packet.hand(), cleanHit));
        var state = item.getBlock().getStateForPlacement(context);
        if (state == null || !context.canPlace()) { reject(bukkit, packet); return; }
        var target = context.getClickedPos();
        var desired = PlacementProtocol.decode(state, (int) relativeX - 2, player.getDirection());
        if (!desired.canSurvive(level, target)
                || !level.isUnobstructed(desired, target, CollisionContext.placementContext(player))
                || (desired.getBlock() instanceof BedBlock
                    && !level.getBlockState(target.relative(desired.getValue(BlockStateProperties.HORIZONTAL_FACING))).canBeReplaced(context))) {
            reject(bukkit, packet);
            return;
        }

        // A scoped vanilla block_state component applies the requested state before setPlacedBy
        // (beds/doors) and Paper's placement events. Never set world blocks or consume items ourselves.
        var originalProperties = stack.get(DataComponents.BLOCK_STATE);
        var properties = new HashMap<String, String>();
        for (var property : desired.getProperties()) {
            properties.put(property.getName(), propertyValue(property, desired.getValue(property)));
        }
        var working = stack.copy();
        working.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(properties));
        player.setItemInHand(packet.hand(), working);
        try {
            player.connection.handleUseItemOn(new ServerboundUseItemOnPacket(packet.hand(), cleanHit, packet.sequence()));
        } finally {
            // Paper may restore a copy on event cancellation; strip the temporary component there too.
            var remaining = player.getItemInHand(packet.hand());
            if (remaining.getItem() == working.getItem()
                    && remaining.get(DataComponents.BLOCK_STATE) != null
                    && remaining.get(DataComponents.BLOCK_STATE).equals(working.get(DataComponents.BLOCK_STATE))) {
                if (originalProperties == null) remaining.remove(DataComponents.BLOCK_STATE);
                else remaining.set(DataComponents.BLOCK_STATE, originalProperties);
            }
            bukkit.updateInventory();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propertyValue(net.minecraft.world.level.block.state.properties.Property property, Comparable value) {
        return property.getName(value);
    }

    private static void reject(Player bukkit, ServerboundUseItemOnPacket packet) {
        var player = ((CraftPlayer) bukkit).getHandle();
        player.connection.ackBlockChangesUpTo(packet.sequence());
        var pos = packet.hitResult().getBlockPos();
        if (player.level().hasChunkAt(pos)) {
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos.relative(packet.hitResult().getDirection())));
        }
        bukkit.updateInventory();
    }
}
