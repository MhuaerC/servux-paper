package fi.dy.masa.servux.paper.network;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;

/** Paper 26.3 transport, including Fabric clients that do not send minecraft:register. */
public final class PayloadTransport
{
    private PayloadTransport() {}

    public static void send(Player player, String channel, byte[] data)
    {
        if (data.length > PacketSplitter.MAX_TOTAL_PER_PACKET_S2C)
        {
            throw new IllegalArgumentException("Servux payload exceeds the client packet limit");
        }
        var connection = ((CraftPlayer) player).getHandle().connection;
        if (connection != null)
        {
            connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(Identifier.parse(channel), data)));
        }
    }
}
