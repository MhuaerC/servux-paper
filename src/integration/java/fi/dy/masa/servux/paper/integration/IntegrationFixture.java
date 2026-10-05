package fi.dy.masa.servux.paper.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.dy.masa.servux.paper.ServuxPaperConfig;
import fi.dy.masa.servux.paper.network.PacketSplitter;
import fi.dy.masa.servux.paper.network.ServuxStructuresPacket;
import net.minecraft.SharedConstants;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Pig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Only packaged in the separate Actions test fixture. */
public final class IntegrationFixture extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        new FeatureFixture(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                var servux = (JavaPlugin) Bukkit.getPluginManager().getPlugin("ServuxPaper");
                if (servux == null || !servux.isEnabled()) throw new AssertionError("Servux failed to load");
                var config = servux.getConfig();
                config.set("hud_data.share_seed", true);
                config.set("hud_data.share_weather_status", true);
                config.set("hud_data.loggers_enabled", true);
                ServuxPaperConfig.load(config);
                var world = Bukkit.getWorlds().getFirst();
                world.addPluginChunkTicket(0, 0, this);
                world.setSpawnLocation(0, 101, 2);
                var block = world.getBlockAt(0, 100, 0);
                block.setType(Material.CHEST, false);
                ((Chest) block.getState()).getBlockInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
                var pig = world.spawn(new Location(world, 2, 101, 0), Pig.class);
                pig.setAI(false);
                pig.setGravity(false);
                pig.setInvulnerable(true);
                var root = new JsonObject();
                root.addProperty("entity_id", pig.getEntityId());
                root.addProperty("protocol", SharedConstants.getProtocolVersion());
                add(root, "login_s2c", LoginProtocols.CLIENTBOUND_TEMPLATE);
                add(root, "login_c2s", LoginProtocols.SERVERBOUND_TEMPLATE);
                add(root, "configuration_s2c", ConfigurationProtocols.CLIENTBOUND_TEMPLATE);
                add(root, "configuration_c2s", ConfigurationProtocols.SERVERBOUND_TEMPLATE);
                add(root, "play_s2c", GameProtocols.CLIENTBOUND_TEMPLATE);
                add(root, "play_c2s", GameProtocols.SERVERBOUND_TEMPLATE);
                Files.writeString(Path.of("network-fixture.json"), new GsonBuilder().create().toJson(root));
                getLogger().info("SERVUX_FIXTURE_READY");
            } catch (Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "SERVUX_FIXTURE_FAILED", failure);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        player.setGravity(false);
        player.setInvulnerable(true);
        player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 7));
        player.getEnderChest().setItem(0, new ItemStack(Material.GOLD_INGOT, 5));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            byte[] payload = new byte[2 * PacketSplitter.MAX_PAYLOAD_PER_PACKET_S2C + 32];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
            PacketSplitter.send("servux:ci_fragment", this, player, payload,
                    bytes -> ServuxStructuresPacket.StructureDataFragment(bytes).toBytes());
        }, 20);
    }

    private static void add(JsonObject root, String key, ProtocolInfo.DetailsProvider template) {
        var packets = new JsonObject();
        template.details().listPackets((type, id) -> packets.addProperty(type.id().getPath(), id));
        root.add(key, packets);
    }
}
