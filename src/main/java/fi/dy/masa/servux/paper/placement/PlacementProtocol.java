package fi.dy.masa.servux.paper.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Litematica/Servux accurate placement protocol V3 (property ordering is part of the wire format). */
public final class PlacementProtocol {
    private PlacementProtocol() {}

    private static final Set<Property<?>> PROPERTIES = Set.of(
            BlockStateProperties.INVERTED, BlockStateProperties.OPEN, BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.AXIS, BlockStateProperties.HALF, BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.CHEST_TYPE, BlockStateProperties.MODE_COMPARATOR, BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.FACING, BlockStateProperties.FACING_HOPPER, BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.ORIENTATION, BlockStateProperties.RAIL_SHAPE, BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.SLAB_TYPE, BlockStateProperties.STAIRS_SHAPE, BlockStateProperties.COPPER_GOLEM_POSE,
            BlockStateProperties.BITES, BlockStateProperties.DELAY, BlockStateProperties.NOTE, BlockStateProperties.ROTATION_16);

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static BlockState decode(BlockState state, int bits, Direction playerFacing) {
        Property direction = null;
        for (Property<?> property : state.getProperties()) {
            if (property instanceof EnumProperty<?> && property.getValueClass() == Direction.class) {
                direction = property;
                break;
            }
        }
        if (direction != null && direction != BlockStateProperties.VERTICAL_DIRECTION) {
            Direction current = (Direction) state.getValue(direction);
            int index = (bits & 15) >>> 1;
            Direction facing = index == 6 ? current.getOpposite()
                    : index <= 5 ? Direction.from3DDataValue(index) : current;
            if (!direction.getPossibleValues().contains(facing)) facing = playerFacing.getOpposite();
            if (direction.getPossibleValues().contains(facing)) state = state.setValue(direction, facing);
            bits >>>= 3;
        }
        bits >>>= 1;
        var properties = new ArrayList<>(state.getProperties());
        properties.sort(Comparator.comparing(Property::getName));
        for (Property property : properties) {
            if (property == direction || !PROPERTIES.contains(property)) continue;
            var values = new ArrayList<Comparable>(property.getPossibleValues());
            values.sort(Comparable::compareTo);
            int width = 32 - Integer.numberOfLeadingZeros(values.size() - 1);
            int index = bits & ((1 << width) - 1);
            // Invalid indices do not consume bits in the upstream V3 decoder.
            if (index < values.size()) {
                Comparable value = values.get(index);
                if (value != SlabType.DOUBLE) state = state.setValue(property, value);
                bits >>>= width;
            }
        }
        return state;
    }
}
