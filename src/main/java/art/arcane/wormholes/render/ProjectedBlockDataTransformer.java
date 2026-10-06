package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.Set;

import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.DirectionMapping;
import art.arcane.optics.frame.PortalCoordMap;

public final class ProjectedBlockDataTransformer {
    private static final BlockFace[] HORIZONTAL_FACES = {
        BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
    };

    private ProjectedBlockDataTransformer() {
    }

    public static BlockData transform(BlockData source, Frame fromFrame, Frame toFrame, double[] scratch3) {
        return transform(source, DirectionMapping.between(fromFrame, toFrame, scratch3));
    }

    public static BlockData mirror(BlockData source, Frame frame, int quarterTurns, double[] scratch3) {
        return transform(source, DirectionMapping.mirror(frame, quarterTurns, scratch3));
    }

    public static BlockData transform(BlockData source, DirectionMapping mapping) {
        BlockData copy = source.clone();
        transformDirectional(copy, mapping);
        transformRotatable(copy, mapping);
        transformOrientable(copy, mapping);
        transformMultipleFacing(copy, mapping);
        transformRail(copy, mapping);
        transformWall(copy, mapping);
        transformRedstoneWire(copy, mapping);
        transformChirality(copy, mapping);
        return copy;
    }

    public static boolean requiresTransform(BlockData source) {
        return source instanceof Directional
            || source instanceof Rotatable
            || source instanceof Orientable
            || source instanceof MultipleFacing
            || source instanceof Rail
            || source instanceof Wall
            || source instanceof RedstoneWire;
    }

    private static void transformDirectional(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof Directional)) {
            return;
        }
        Directional directional = (Directional) data;
        Face source = fromBlockFace(directional.getFacing());
        if (source == null) {
            return;
        }
        Face target = mapping.map(source);
        BlockFace targetFace = toBlockFace(target);
        Set<BlockFace> faces = directional.getFaces();
        if (targetFace != null && faces.contains(targetFace)) {
            directional.setFacing(targetFace);
        }
    }

    private static void transformRotatable(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof Rotatable)) {
            return;
        }
        Rotatable rotatable = (Rotatable) data;
        int index = BukkitBlockRotation16.index(rotatable.getRotation());
        if (index < 0) {
            return;
        }
        rotatable.setRotation(BukkitBlockRotation16.face(mapping.mapRotation(index)));
    }

    private static void transformOrientable(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof Orientable)) {
            return;
        }
        Orientable orientable = (Orientable) data;
        Face source = directionForAxis(orientable.getAxis());
        Face target = mapping.map(source);
        Axis axis = axisForDirection(target);
        if (orientable.getAxes().contains(axis)) {
            orientable.setAxis(axis);
        }
    }

    private static void transformMultipleFacing(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof MultipleFacing)) {
            return;
        }
        MultipleFacing multiple = (MultipleFacing) data;
        ArrayList<BlockFace> enabled = new ArrayList<BlockFace>(multiple.getFaces());
        for (BlockFace face : multiple.getAllowedFaces()) {
            multiple.setFace(face, false);
        }
        for (BlockFace face : enabled) {
            Face source = fromBlockFace(face);
            if (source == null) {
                continue;
            }
            Face target = mapping.map(source);
            BlockFace targetFace = toBlockFace(target);
            if (targetFace != null && multiple.getAllowedFaces().contains(targetFace)) {
                multiple.setFace(targetFace, true);
            }
        }
    }

    private static void transformWall(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof Wall wall)) {
            return;
        }
        Wall.Height[] heights = new Wall.Height[HORIZONTAL_FACES.length];
        for (int index = 0; index < HORIZONTAL_FACES.length; index++) {
            heights[index] = wall.getHeight(HORIZONTAL_FACES[index]);
            wall.setHeight(HORIZONTAL_FACES[index], Wall.Height.NONE);
        }
        for (int index = 0; index < HORIZONTAL_FACES.length; index++) {
            BlockFace targetFace = mappedHorizontalFace(HORIZONTAL_FACES[index], mapping);
            if (targetFace != null) {
                wall.setHeight(targetFace, heights[index]);
            }
        }
    }

    private static void transformRedstoneWire(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof RedstoneWire wire)) {
            return;
        }
        RedstoneWire.Connection[] connections = new RedstoneWire.Connection[HORIZONTAL_FACES.length];
        for (int index = 0; index < HORIZONTAL_FACES.length; index++) {
            connections[index] = wire.getFace(HORIZONTAL_FACES[index]);
            wire.setFace(HORIZONTAL_FACES[index], RedstoneWire.Connection.NONE);
        }
        Set<BlockFace> allowed = wire.getAllowedFaces();
        for (int index = 0; index < HORIZONTAL_FACES.length; index++) {
            BlockFace targetFace = mappedHorizontalFace(HORIZONTAL_FACES[index], mapping);
            if (targetFace != null && allowed.contains(targetFace)) {
                wire.setFace(targetFace, connections[index]);
            }
        }
    }

    /**
     * Swaps handed block states that a rotation leaves alone but a reflection turns inside out. Rails
     * are already reflected by their endpoint remap, bisected halves and bell attachments survive a
     * horizontal reflection unchanged.
     */
    private static void transformChirality(BlockData data, DirectionMapping mapping) {
        if (!mapping.reflects()) {
            return;
        }
        if (data instanceof Stairs stairs) {
            stairs.setShape(switch (stairs.getShape()) {
                case INNER_LEFT -> Stairs.Shape.INNER_RIGHT;
                case INNER_RIGHT -> Stairs.Shape.INNER_LEFT;
                case OUTER_LEFT -> Stairs.Shape.OUTER_RIGHT;
                case OUTER_RIGHT -> Stairs.Shape.OUTER_LEFT;
                case STRAIGHT -> Stairs.Shape.STRAIGHT;
            });
        }
        if (data instanceof Door door) {
            door.setHinge(door.getHinge() == Door.Hinge.LEFT ? Door.Hinge.RIGHT : Door.Hinge.LEFT);
        }
        if (data instanceof Chest chest && chest.getType() != Chest.Type.SINGLE) {
            chest.setType(chest.getType() == Chest.Type.LEFT ? Chest.Type.RIGHT : Chest.Type.LEFT);
        }
    }

    private static BlockFace mappedHorizontalFace(BlockFace face, DirectionMapping mapping) {
        Face source = fromBlockFace(face);
        if (source == null) {
            return null;
        }
        BlockFace target = toBlockFace(mapping.map(source));
        return target == BlockFace.UP || target == BlockFace.DOWN ? null : target;
    }

    private static void transformRail(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof Rail)) {
            return;
        }
        Rail rail = (Rail) data;
        DirectionMapping.RailShape shape = mapping.mapRailShape(DirectionMapping.RailShape.valueOf(rail.getShape().name()));
        Rail.Shape transformed = shape == null ? null : Rail.Shape.valueOf(shape.name());
        if (transformed != null && rail.getShapes().contains(transformed)) {
            rail.setShape(transformed);
        }
    }

    static Face mirrorDirection(Face source, Frame frame, int quarterTurns, double[] scratch3) {
        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.x(), source.y(), source.z(), frame, quarterTurns, scratch3);
        return Face.closest(scratch3[0], scratch3[1], scratch3[2]);
    }

    private static Face directionForAxis(Axis axis) {
        switch(axis) {
            case X:
                return Face.E;
            case Y:
                return Face.U;
            case Z:
            default:
                return Face.S;
        }
    }

    private static Axis axisForDirection(Face direction) {
        switch(direction.getAxis()) {
            case X:
                return Axis.X;
            case Y:
                return Axis.Y;
            case Z:
            default:
                return Axis.Z;
        }
    }

    private static Face fromBlockFace(BlockFace face) {
        switch(face) {
            case NORTH:
                return Face.N;
            case SOUTH:
                return Face.S;
            case EAST:
                return Face.E;
            case WEST:
                return Face.W;
            case UP:
                return Face.U;
            case DOWN:
                return Face.D;
            default:
                return null;
        }
    }

    private static BlockFace toBlockFace(Face direction) {
        switch(direction) {
            case N:
                return BlockFace.NORTH;
            case S:
                return BlockFace.SOUTH;
            case E:
                return BlockFace.EAST;
            case W:
                return BlockFace.WEST;
            case U:
                return BlockFace.UP;
            case D:
                return BlockFace.DOWN;
            default:
                return null;
        }
    }
}
