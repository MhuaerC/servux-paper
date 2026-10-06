package fi.dy.masa.servux.paper.integration;

import ch.endte.syncmatica.network.SyncmaticaPacket;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Checks outbound wire bytes with Syncmatica's actual 0.3.20 payload codec (test JAR only). */
public final class SyncmaticaCodecFixture implements Listener {
    private static final AtomicInteger checked = new AtomicInteger();
    private final JavaPlugin plugin;

    public SyncmaticaCodecFixture(JavaPlugin plugin) {
        this.plugin = plugin;
        // Mirror the client's registered type lookup. The old outer ID must resolve
        // to DiscardedPayload, demonstrating the class-cast regression independently.
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        try {
            body.writeUtf("0.3.20-servux-paper");
            byte[] legacy = new byte[body.readableBytes()]; body.readBytes(legacy);
            if (!(decode(Identifier.parse("syncmatica:register_version"), legacy) instanceof DiscardedPayload)) {
                throw new AssertionError("Legacy envelope regression was not reproduced");
            }
        } finally { body.release(); }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private static CustomPacketPayload decode(Identifier outer, byte[] data) {
        if (!SyncmaticaPacket.Payload.ID.id().equals(outer)) return new DiscardedPayload(outer, data);
        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try { return SyncmaticaPacket.Payload.CODEC.decode(input); }
        finally { input.release(); }
    }

    public static int checkedPackets() { return checked.get(); }

    @EventHandler public void join(PlayerJoinEvent event) {
        var channel = ((CraftPlayer) event.getPlayer()).getHandle().connection.connection.channel;
        channel.eventLoop().execute(() -> channel.pipeline().addBefore("packet_handler", "servux_syncmatica_codec_test", new ChannelDuplexHandler() {
            @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                if (message instanceof ClientboundCustomPayloadPacket packet
                        && packet.payload().type().id().getNamespace().equals("syncmatica")) {
                    try {
                        if (!(packet.payload() instanceof DiscardedPayload wire)) throw new AssertionError("Unexpected Paper payload");
                        CustomPacketPayload decoded = decode(wire.type().id(), wire.data());
                        if (!(decoded instanceof SyncmaticaPacket.Payload payload)) {
                            throw new AssertionError("Syncmatica client would cast DiscardedPayload: " + wire.type().id());
                        }
                        try {
                            if (payload.data().getType() == null) throw new AssertionError("Unknown inner message");
                            checked.incrementAndGet();
                        } finally { payload.data().getPacket().release(); }
                    } catch (Throwable failure) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "SERVUX_SYNC_CLIENT_CODEC_FAILED", failure);
                        throw new IllegalStateException(failure);
                    }
                }
                super.write(context, message, promise);
            }
        }));
    }
}
