package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.DirectionMapping;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.Optional;

import static art.arcane.wormholes.util.Direction.U;
import static art.arcane.wormholes.util.Direction.D;
import static art.arcane.wormholes.util.Direction.N;
import static art.arcane.wormholes.util.Direction.S;
import static art.arcane.wormholes.util.Direction.E;
import static art.arcane.wormholes.util.Direction.W;

public final class MinecraftProjectedBlockStates {
    private MinecraftProjectedBlockStates() {
    }

    public static BlockState transform(BlockState source, DirectionMapping mapping) {
        BlockState mirrored = mapping.reflects() ? source.mirror(Mirror.FRONT_BACK) : source;
        BlockState result = mirrored.rotate(switch (mapping.quarterTurnsClockwise()) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
        });
        if (mapping.map(U) == U) {
            return result;
        }
        for (Property<?> property : source.getProperties()) {
            if (connectionDirection(property.getName(), source) != null) {
                result = setSerialized(result, property, property.getValueClass() == Boolean.class ? "false" : "none");
            }
        }
        for (Property<?> property : source.getProperties()) {
            result = transformProperty(source, result, property, mapping);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState transformProperty(
        BlockState source, BlockState result, Property<T> property, DirectionMapping mapping) {
        T value = source.getValue(property);
        Direction connection = connectionDirection(property.getName(), source);
        if (connection != null) {
            Direction target = map(connection, mapping);
            Property<?> targetProperty = source.getBlock().getStateDefinition().getProperty(target.getSerializedName());
            if (targetProperty != null && connectionDirection(targetProperty.getName(), source) != null) {
                return setSerialized(result, targetProperty, property.getName(value));
            }
            return result;
        }
        if (value instanceof Direction direction) {
            return setSerialized(result, property, map(direction, mapping).getSerializedName());
        }
        if (value instanceof Direction.Axis axis) {
            Direction direction = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
            return setSerialized(result, property, map(direction, mapping).getAxis().getSerializedName());
        }
        if (value instanceof RailShape rail) {
            DirectionMapping.RailShape mapped = mapping.mapRailShape(DirectionMapping.RailShape.valueOf(rail.name()));
            return mapped == null ? result : setSerialized(result, property, RailShape.valueOf(mapped.name()).getSerializedName());
        }
        if (property.getName().equals("rotation") && value instanceof Integer rotation) {
            return setSerialized(result, property, Integer.toString(mapping.mapRotation(rotation)));
        }
        return result;
    }

    private static Direction connectionDirection(String name, BlockState state) {
        return switch (name) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            case "up" -> state.getBlock() instanceof MultifaceBlock || state.getBlock() instanceof HugeMushroomBlock ? Direction.UP : null;
            case "down" -> state.getBlock() instanceof MultifaceBlock || state.getBlock() instanceof HugeMushroomBlock ? Direction.DOWN : null;
            default -> null;
        };
    }

    private static <T extends Comparable<T>> BlockState setSerialized(BlockState state, Property<T> property, String name) {
        Optional<T> value = property.getValue(name);
        return value.isPresent() ? state.setValue(property, value.get()) : state;
    }

    private static Direction map(Direction direction, DirectionMapping mapping) {
        return switch (mapping.map(switch (direction) {
            case UP -> U;
            case DOWN -> D;
            case NORTH -> N;
            case SOUTH -> S;
            case EAST -> E;
            case WEST -> W;
        })) {
            case U -> Direction.UP;
            case D -> Direction.DOWN;
            case N -> Direction.NORTH;
            case S -> Direction.SOUTH;
            case E -> Direction.EAST;
            case W -> Direction.WEST;
        };
    }
}
