package fi.dy.masa.servux.paper.syncmatica;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Syncmatica CORE / CORE_EX metadata. File names are display labels, never filesystem paths. */
public record SharedPlacement(UUID id, String name, UUID hash, UUID owner, String ownerName,
                              UUID modifier, String modifierName, Position position) {
    public record Region(String name, long pos, int rotation, int mirror) {}
    public record Position(long pos, String dimension, int rotation, int mirror, List<Region> regions) {
        static Position read(FriendlyByteBuf buffer, boolean extended) {
            long pos = buffer.readLong();
            String dimension = buffer.readUtf(256);
            if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid dimension");
            int rotation = ordinal(buffer, 4), mirror = ordinal(buffer, 3);
            int count = extended ? buffer.readInt() : 0;
            if (count < 0 || count > 128) throw new IllegalArgumentException("Too many subregions");
            var regions = new ArrayList<Region>();
            for (int i = 0; i < count; i++) {
                regions.add(new Region(buffer.readUtf(128), buffer.readLong(), ordinal(buffer, 4), ordinal(buffer, 3)));
            }
            return new Position(pos, dimension, rotation, mirror, List.copyOf(regions));
        }
        void write(FriendlyByteBuf buffer, boolean extended) {
            buffer.writeLong(pos);
            buffer.writeUtf(dimension);
            buffer.writeInt(rotation);
            buffer.writeInt(mirror);
            if (extended) {
                buffer.writeInt(regions.size());
                for (Region region : regions) {
                    buffer.writeUtf(region.name());
                    buffer.writeLong(region.pos());
                    buffer.writeInt(region.rotation());
                    buffer.writeInt(region.mirror());
                }
            }
        }
        private static int ordinal(FriendlyByteBuf buffer, int size) {
            int value = buffer.readInt();
            if (value < 0 || value >= size) throw new IllegalArgumentException("Invalid rotation/mirror");
            return value;
        }
    }

    static SharedPlacement read(FriendlyByteBuf buffer, boolean extended, UUID player, String playerName) {
        UUID id = buffer.readUUID();
        String name = buffer.readUtf(128).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.isBlank()) throw new IllegalArgumentException("Empty schematic name");
        UUID hash = buffer.readUUID();
        if (extended) {
            buffer.readUUID(); buffer.readUtf(128);
            buffer.readUUID(); buffer.readUtf(128);
        }
        // Ownership comes from the authenticated connection, never from the packet.
        return new SharedPlacement(id, name, hash, player, playerName, player, playerName, Position.read(buffer, extended));
    }

    void write(FriendlyByteBuf buffer, boolean extended) {
        buffer.writeUUID(id); buffer.writeUtf(name); buffer.writeUUID(hash);
        if (extended) {
            buffer.writeUUID(owner); buffer.writeUtf(ownerName);
            buffer.writeUUID(modifier); buffer.writeUtf(modifierName);
        }
        position.write(buffer, extended);
    }

    SharedPlacement move(Position target, UUID player, String playerName) {
        return new SharedPlacement(id, name, hash, owner, ownerName, player, playerName, target);
    }
}
