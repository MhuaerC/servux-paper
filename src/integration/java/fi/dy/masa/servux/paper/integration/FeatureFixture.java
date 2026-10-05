package fi.dy.masa.servux.paper.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.servux.paper.network.PayloadTransport;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

/** Test control channel exists only in the integration-tests JAR, never the shipped plugin. */
public final class FeatureFixture implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, PermissionAttachment> permissions = new HashMap<>();
    private final Map<UUID, Boolean> cancelled = new HashMap<>();
    private final Map<UUID, Integer> events = new HashMap<>();
    private final Map<UUID, Boolean> offhands = new HashMap<>();
    public FeatureFixture(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, "servux:feature_test", (channel, player, bytes) -> {
            var request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String operation = request.get("op").getAsString();
            UUID id = player.getUniqueId();
            var response = new JsonObject();
            var world = player.getWorld();
            if (operation.equals("prepare")) {
                for (int x = 1; x <= 6; x++) for (int z = -2; z <= 5; z++) {
                    world.getBlockAt(x, 100, z).setType(Material.STONE, false);
                    for (int y = 101; y <= 104; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
                player.teleport(new Location(world, 3.5, 101, 3.5, 180, 0));
                player.setGameMode(request.has("creative") && request.get("creative").getAsBoolean() ? GameMode.CREATIVE : GameMode.SURVIVAL);
                player.getInventory().clear();
                player.getInventory().setHeldItemSlot(0);
                boolean offhand = request.has("offhand") && request.get("offhand").getAsBoolean();
                offhands.put(id, offhand);
                ItemStack stack = new ItemStack(Material.valueOf(request.get("item").getAsString()), 5);
                if (offhand) player.getInventory().setItemInOffHand(stack);
                else player.getInventory().setItemInMainHand(stack);
                if (request.has("water") && request.get("water").getAsBoolean()) world.getBlockAt(3, 101, 0).setType(Material.WATER, false);
                if (request.has("deny")) permissions.computeIfAbsent(id, k -> player.addAttachment(plugin))
                        .setPermission("servux.easy_place", !request.get("deny").getAsBoolean());
                cancelled.put(id, request.has("cancel") && request.get("cancel").getAsBoolean());
                events.put(id, 0);
            } else if (operation.equals("permission")) {
                permissions.computeIfAbsent(id, k -> player.addAttachment(plugin))
                        .setPermission(request.get("node").getAsString(), request.get("value").getAsBoolean());
            }
            boolean offhand = offhands.getOrDefault(id, false);
            var stack = ((CraftPlayer) player).getHandle().getItemInHand(offhand
                    ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND);
            response.addProperty("state", world.getBlockAt(3, 101, 0).getBlockData().getAsString());
            response.addProperty("upper", world.getBlockAt(3, 102, 0).getBlockData().getAsString());
            response.addProperty("east", world.getBlockAt(4, 101, 0).getBlockData().getAsString());
            response.addProperty("count", stack.getCount());
            response.addProperty("temporary_component", stack.has(DataComponents.BLOCK_STATE));
            response.addProperty("events", events.getOrDefault(id, 0));
            response.addProperty("uuid", id.toString());
            PayloadTransport.send(player, "servux:feature_test", response.toString().getBytes(StandardCharsets.UTF_8));
        });
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, "servux:feature_test");
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void place(BlockPlaceEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        events.merge(player, 1, Integer::sum);
        if (cancelled.getOrDefault(player, false)) event.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        permissions.remove(id); cancelled.remove(id); events.remove(id); offhands.remove(id);
    }
}
