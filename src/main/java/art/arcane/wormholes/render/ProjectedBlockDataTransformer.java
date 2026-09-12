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

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

public final class ProjectedBlockDataTransformer {
    private static final BlockFace[] HORIZONTAL_FACES = {
        BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
    };

    private ProjectedBlockDataTransformer() {
    }

    public static BlockData transform(BlockData source, PortalFrame fromFrame, PortalFrame toFrame, double[] scratch3) {
        return transform(source, DirectionMapping.between(fromFrame, toFrame, scratch3));
    }

    public static BlockData mirror(BlockData source, PortalFrame frame, int quarterTurns, double[] scratch3) {
        return transform(source, DirectionMapping.mirror(frame, quarterTurns, scratch3));
    }

    private static BlockData transform(BlockData source, DirectionMapping mapping) {
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
        Direction source = fromBlockFace(directional.getFacing());
        if (source == null) {
            return;
        }
        Direction target = mapping.map(source);
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
        int index = BlockRotation16.index(rotatable.getRotation());
        if (index < 0) {
            return;
        }
        rotatable.setRotation(BlockRotation16.face(mapping.mapRotation(index)));
    }

    private static void transformOrientable(BlockData data, DirectionMapping mapping) {
        if (!(data instanceof Orientable)) {
            return;
        }
        Orientable orientable = (Orientable) data;
        Direction source = directionForAxis(orientable.getAxis());
        Direction target = mapping.map(source);
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
            Direction source = fromBlockFace(face);
            if (source == null) {
                continue;
            }
            Direction target = mapping.map(source);
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
        Direction source = fromBlockFace(face);
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
        Rail.Shape transformed = rotateRailShape(rail.getShape(), mapping);
        if (transformed != null && rail.getShapes().contains(transformed)) {
            rail.setShape(transformed);
        }
    }

    private static Rail.Shape rotateRailShape(Rail.Shape shape, DirectionMapping mapping) {
        switch(shape) {
            case NORTH_SOUTH:
                return straightRailShape(rotateHorizontal(Direction.N, mapping));
            case EAST_WEST:
                return straightRailShape(rotateHorizontal(Direction.E, mapping));
            case ASCENDING_NORTH:
                return ascendingRailShape(rotateHorizontal(Direction.N, mapping), shape);
            case ASCENDING_SOUTH:
                return ascendingRailShape(rotateHorizontal(Direction.S, mapping), shape);
            case ASCENDING_EAST:
                return ascendingRailShape(rotateHorizontal(Direction.E, mapping), shape);
            case ASCENDING_WEST:
                return ascendingRailShape(rotateHorizontal(Direction.W, mapping), shape);
            case SOUTH_EAST:
                return curvedRailShape(rotateHorizontal(Direction.S, mapping), rotateHorizontal(Direction.E, mapping));
            case SOUTH_WEST:
                return curvedRailShape(rotateHorizontal(Direction.S, mapping), rotateHorizontal(Direction.W, mapping));
            case NORTH_WEST:
                return curvedRailShape(rotateHorizontal(Direction.N, mapping), rotateHorizontal(Direction.W, mapping));
            case NORTH_EAST:
                return curvedRailShape(rotateHorizontal(Direction.N, mapping), rotateHorizontal(Direction.E, mapping));
            default:
                return null;
        }
    }

    private static BlockFace rotateHorizontal(Direction source, DirectionMapping mapping) {
        BlockFace face = toBlockFace(mapping.map(source));
        if (face == BlockFace.NORTH || face == BlockFace.SOUTH || face == BlockFace.EAST || face == BlockFace.WEST) {
            return face;
        }
        return null;
    }

    private static Rail.Shape straightRailShape(BlockFace face) {
        if (face == BlockFace.NORTH || face == BlockFace.SOUTH) {
            return Rail.Shape.NORTH_SOUTH;
        }
        if (face == BlockFace.EAST || face == BlockFace.WEST) {
            return Rail.Shape.EAST_WEST;
        }
        return null;
    }

    private static Rail.Shape ascendingRailShape(BlockFace face, Rail.Shape fallback) {
        if (face == null) {
            return fallback;
        }
        switch(face) {
            case NORTH:
                return Rail.Shape.ASCENDING_NORTH;
            case SOUTH:
                return Rail.Shape.ASCENDING_SOUTH;
            case EAST:
                return Rail.Shape.ASCENDING_EAST;
            case WEST:
                return Rail.Shape.ASCENDING_WEST;
            default:
                return fallback;
        }
    }

    private static Rail.Shape curvedRailShape(BlockFace a, BlockFace b) {
        if (a == null || b == null) {
            return null;
        }
        boolean north = a == BlockFace.NORTH || b == BlockFace.NORTH;
        boolean south = a == BlockFace.SOUTH || b == BlockFace.SOUTH;
        boolean east = a == BlockFace.EAST || b == BlockFace.EAST;
        boolean west = a == BlockFace.WEST || b == BlockFace.WEST;
        if (south && east) {
            return Rail.Shape.SOUTH_EAST;
        }
        if (south && west) {
            return Rail.Shape.SOUTH_WEST;
        }
        if (north && west) {
            return Rail.Shape.NORTH_WEST;
        }
        if (north && east) {
            return Rail.Shape.NORTH_EAST;
        }
        return null;
    }

    static Direction mirrorDirection(Direction source, PortalFrame frame, int quarterTurns, double[] scratch3) {
        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.x(), source.y(), source.z(), frame, quarterTurns, scratch3);
        return Direction.closest(scratch3[0], scratch3[1], scratch3[2]);
    }

    private static final class DirectionMapping {
        private static final int HANDEDNESS_UNCOMPUTED = Integer.MIN_VALUE;

        private final PortalFrame fromFrame;
        private final PortalFrame toFrame;
        private final PortalFrame mirrorFrame;
        private final int quarterTurns;
        private final double[] scratch3;
        private int imageQuarterTurns;
        private boolean reflects;

        private DirectionMapping(PortalFrame fromFrame, PortalFrame toFrame, PortalFrame mirrorFrame, int quarterTurns, double[] scratch3) {
            this.fromFrame = fromFrame;
            this.toFrame = toFrame;
            this.mirrorFrame = mirrorFrame;
            this.quarterTurns = quarterTurns;
            this.scratch3 = scratch3;
            this.imageQuarterTurns = HANDEDNESS_UNCOMPUTED;
            this.reflects = false;
        }

        /** Quarter turns clockwise from above that this mapping applies to the horizontal plane. */
        private int quarterTurnsClockwise() {
            if (imageQuarterTurns == HANDEDNESS_UNCOMPUTED) {
                computeHandedness();
            }
            return imageQuarterTurns;
        }

        /** True when the mapping mirrors the horizontal plane, so handed block states must swap sides. */
        private boolean reflects() {
            if (imageQuarterTurns == HANDEDNESS_UNCOMPUTED) {
                computeHandedness();
            }
            return reflects;
        }

        /** Maps a 16-step rotation index through the mapping, reflecting first and then turning. */
        private int mapRotation(int index) {
            int reflected = reflects() ? BlockRotation16.reflect(index, Direction.E) : index;
            return BlockRotation16.rotate(reflected, quarterTurnsClockwise());
        }

        private void computeHandedness() {
            int southIndex = BlockRotation16.index(toBlockFace(map(Direction.S)));
            int eastIndex = BlockRotation16.index(toBlockFace(map(Direction.E)));
            if (southIndex < 0 || eastIndex < 0) {
                imageQuarterTurns = 0;
                reflects = false;
                return;
            }
            imageQuarterTurns = southIndex / 4;
            reflects = eastIndex != BlockRotation16.rotate(BlockRotation16.index(BlockFace.EAST), imageQuarterTurns);
        }

        private static DirectionMapping between(PortalFrame fromFrame, PortalFrame toFrame, double[] scratch3) {
            return new DirectionMapping(fromFrame, toFrame, null, 0, scratch3);
        }

        private static DirectionMapping mirror(PortalFrame frame, int quarterTurns, double[] scratch3) {
            return new DirectionMapping(null, null, frame, quarterTurns, scratch3);
        }

        private Direction map(Direction source) {
            if(mirrorFrame != null) {
                return mirrorDirection(source, mirrorFrame, quarterTurns, scratch3);
            }
            return fromFrame.transformDirection(source, toFrame, scratch3);
        }
    }

    private static Direction directionForAxis(Axis axis) {
        switch(axis) {
            case X:
                return Direction.E;
            case Y:
                return Direction.U;
            case Z:
            default:
                return Direction.S;
        }
    }

    private static Axis axisForDirection(Direction direction) {
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

    private static Direction fromBlockFace(BlockFace face) {
        switch(face) {
            case NORTH:
                return Direction.N;
            case SOUTH:
                return Direction.S;
            case EAST:
                return Direction.E;
            case WEST:
                return Direction.W;
            case UP:
                return Direction.U;
            case DOWN:
                return Direction.D;
            default:
                return null;
        }
    }

    private static BlockFace toBlockFace(Direction direction) {
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
