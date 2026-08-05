package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.List;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3d;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

final class ProjectedItemFrameTransform {
    static final int NONE = -1;

    private static final int DIRECTION_INDEX = 8;
    private static final int ITEM_INDEX = 9;
    private static final int ROTATION_INDEX = 10;
    private static final int TARGET_MASK = 0x7;
    private static final int QUARTER_TURN_SHIFT = 3;
    private static final int QUARTER_TURN_MASK = 0x3;
    private static final int REVERSED_ROTATION_FLAG = 1 << 5;

    private ProjectedItemFrameTransform() {
    }

    static int between(Direction sourceFacing,
                       PortalFrame sourceFrame,
                       PortalFrame targetFrame,
                       double[] scratch3) {
        Direction sourceTop = canonicalTop(sourceFacing);
        Direction sourceRight = cross(sourceFacing, sourceTop);
        Direction targetFacing = sourceFrame.transformDirection(sourceFacing, targetFrame, scratch3);
        Direction mappedTop = sourceFrame.transformDirection(sourceTop, targetFrame, scratch3);
        Direction mappedRight = sourceFrame.transformDirection(sourceRight, targetFrame, scratch3);
        return encode(targetFacing, mappedTop, mappedRight);
    }

    static int mirror(Direction sourceFacing,
                      PortalFrame frame,
                      int quarterTurns,
                      double[] scratch3) {
        Direction sourceTop = canonicalTop(sourceFacing);
        Direction sourceRight = cross(sourceFacing, sourceTop);
        Direction targetFacing = mirrorDirection(sourceFacing, frame, quarterTurns, scratch3);
        Direction mappedTop = mirrorDirection(sourceTop, frame, quarterTurns, scratch3);
        Direction mappedRight = mirrorDirection(sourceRight, frame, quarterTurns, scratch3);
        return encode(targetFacing, mappedTop, mappedRight);
    }

    static int spawnData(int transform) {
        return transform == NONE ? 0 : targetFacing(transform).byteValue();
    }

    static Direction targetFacing(int transform) {
        if (transform == NONE) {
            return null;
        }
        return Direction.fromByte((byte) (transform & TARGET_MASK));
    }

    static int transformRotation(int transform, int sourceRotation, boolean filledMap) {
        if (transform == NONE) {
            return sourceRotation;
        }
        int normalized = Math.floorMod(sourceRotation, 8);
        int sign = (transform & REVERSED_ROTATION_FLAG) == 0 ? 1 : -1;
        int quarterTurns = (transform >> QUARTER_TURN_SHIFT) & QUARTER_TURN_MASK;
        if (filledMap) {
            int transformed = Math.floorMod(quarterTurns + (sign * normalized), 4);
            return (normalized & 4) | transformed;
        }
        return Math.floorMod((quarterTurns * 2) + (sign * normalized), 8);
    }

    static List<EntityData<?>> transformMetadata(List<EntityData<?>> metadata, int transform) {
        return transformMetadata(metadata, transform, null, false);
    }

    static List<EntityData<?>> transformMetadata(List<EntityData<?>> metadata,
                                                  int transform,
                                                  Integer projectedMapId,
                                                  boolean stripMapId) {
        if ((transform == NONE && projectedMapId == null && !stripMapId) || metadata == null || metadata.isEmpty()) {
            return metadata;
        }
        boolean filledMap = !stripMapId && containsFilledMap(metadata);
        ArrayList<EntityData<?>> patched = new ArrayList<EntityData<?>>(metadata.size());
        BlockFace targetFace = transform == NONE ? null : blockFace(targetFacing(transform));
        for (EntityData<?> data : metadata) {
            if (transform != NONE && data.getIndex() == DIRECTION_INDEX && data.getValue() instanceof BlockFace) {
                patched.add(replaceValue(data, targetFace));
                continue;
            }
            if (data.getIndex() == ITEM_INDEX && data.getValue() instanceof ItemStack) {
                ItemStack item = (ItemStack) data.getValue();
                if (item.hasComponent(ComponentTypes.MAP_ID) && (projectedMapId != null || stripMapId)) {
                    ItemStack projectedItem = item.copy();
                    if (stripMapId) {
                        projectedItem.unsetComponent(ComponentTypes.MAP_ID);
                    } else {
                        projectedItem.setComponent(ComponentTypes.MAP_ID, projectedMapId);
                    }
                    patched.add(replaceValue(data, projectedItem));
                    continue;
                }
            }
            if (transform != NONE && data.getIndex() == ROTATION_INDEX && data.getValue() instanceof Integer) {
                int sourceRotation = ((Integer) data.getValue()).intValue();
                patched.add(replaceValue(data,
                    Integer.valueOf(transformRotation(transform, sourceRotation, filledMap))));
                continue;
            }
            patched.add(data);
        }
        return patched;
    }

    static Integer mapId(List<EntityData<?>> metadata) {
        if (metadata == null) {
            return null;
        }
        for (EntityData<?> data : metadata) {
            if (data.getIndex() != ITEM_INDEX || !(data.getValue() instanceof ItemStack)) {
                continue;
            }
            ItemStack item = (ItemStack) data.getValue();
            return item.getComponent(ComponentTypes.MAP_ID).orElse(null);
        }
        return null;
    }

    static boolean isReversed(int transform) {
        return transform != NONE && (transform & REVERSED_ROTATION_FLAG) != 0;
    }

    static Vector3d betweenAnchor(double sourceX,
                                  double sourceY,
                                  double sourceZ,
                                  double sourceOriginX,
                                  double sourceOriginY,
                                  double sourceOriginZ,
                                  double targetOriginX,
                                  double targetOriginY,
                                  double targetOriginZ,
                                  PortalFrame sourceFrame,
                                  PortalFrame targetFrame,
                                  double[] scratch3) {
        PortalCoordMap.transformPointInto(blockCenter(sourceX), blockCenter(sourceY), blockCenter(sourceZ),
            sourceOriginX, sourceOriginY, sourceOriginZ,
            targetOriginX, targetOriginY, targetOriginZ,
            sourceFrame, targetFrame, scratch3);
        double targetX = scratch3[0];
        double targetY = scratch3[1];
        double targetZ = scratch3[2];
        sourceFrame.transformVectorInto(1.0D, 1.0D, 1.0D, targetFrame, scratch3);
        double snapTolerance = ProjectorFrameTransform.coordinateSnapTolerance(
            sourceOriginX, sourceOriginY, sourceOriginZ, targetOriginX, targetOriginY, targetOriginZ);
        return anchorPosition(targetX, targetY, targetZ,
            scratch3[0], scratch3[1], scratch3[2], snapTolerance);
    }

    static Vector3d mirrorAnchor(double sourceX,
                                 double sourceY,
                                 double sourceZ,
                                 double originX,
                                 double originY,
                                 double originZ,
                                 PortalFrame frame,
                                 int quarterTurns,
                                 double[] scratch3) {
        PortalCoordMap.mirrorSourceToDisplayPointInto(
            blockCenter(sourceX), blockCenter(sourceY), blockCenter(sourceZ),
            originX, originY, originZ, frame, quarterTurns, scratch3);
        double targetX = scratch3[0];
        double targetY = scratch3[1];
        double targetZ = scratch3[2];
        PortalCoordMap.mirrorSourceToDisplayVectorInto(
            1.0D, 1.0D, 1.0D, frame, quarterTurns, scratch3);
        double snapTolerance = ProjectorFrameTransform.coordinateSnapTolerance(
            originX, originY, originZ, originX, originY, originZ);
        return anchorPosition(targetX, targetY, targetZ,
            scratch3[0], scratch3[1], scratch3[2], snapTolerance);
    }

    static boolean isItemFrame(EntityType entityType) {
        return entityType == EntityTypes.ITEM_FRAME || entityType == EntityTypes.GLOW_ITEM_FRAME;
    }

    private static int encode(Direction targetFacing, Direction mappedTop, Direction mappedRight) {
        int quarterTurns = quarterTurns(targetFacing, mappedTop);
        Direction expectedRight = rotatedRight(targetFacing, quarterTurns);
        boolean reversed;
        if (mappedRight == expectedRight) {
            reversed = false;
        } else if (mappedRight == expectedRight.reverse()) {
            reversed = true;
        } else {
            throw new IllegalStateException("Item frame transform produced a non-orthogonal orientation");
        }
        int encoded = targetFacing.byteValue() | (quarterTurns << QUARTER_TURN_SHIFT);
        return reversed ? encoded | REVERSED_ROTATION_FLAG : encoded;
    }

    private static int quarterTurns(Direction facing, Direction mappedTop) {
        Direction top = canonicalTop(facing);
        Direction right = cross(facing, top);
        if (mappedTop == top) {
            return 0;
        }
        if (mappedTop == right.reverse()) {
            return 1;
        }
        if (mappedTop == top.reverse()) {
            return 2;
        }
        if (mappedTop == right) {
            return 3;
        }
        throw new IllegalStateException("Item frame transform moved its top outside the target plane");
    }

    private static Direction rotatedRight(Direction facing, int quarterTurns) {
        Direction top = canonicalTop(facing);
        Direction right = cross(facing, top);
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> top;
            case 2 -> right.reverse();
            case 3 -> top.reverse();
            default -> right;
        };
    }

    private static Direction canonicalTop(Direction facing) {
        return switch (facing) {
            case U -> Direction.N;
            case D -> Direction.S;
            default -> Direction.U;
        };
    }

    private static Direction cross(Direction left, Direction right) {
        return Direction.closest(
            (left.y() * right.z()) - (left.z() * right.y()),
            (left.z() * right.x()) - (left.x() * right.z()),
            (left.x() * right.y()) - (left.y() * right.x()));
    }

    private static Direction mirrorDirection(Direction source,
                                             PortalFrame frame,
                                             int quarterTurns,
                                             double[] scratch3) {
        PortalCoordMap.mirrorSourceToDisplayVectorInto(
            source.x(), source.y(), source.z(), frame, quarterTurns, scratch3);
        return Direction.closest(scratch3[0], scratch3[1], scratch3[2]);
    }

    private static boolean containsFilledMap(List<EntityData<?>> metadata) {
        for (EntityData<?> data : metadata) {
            if (data.getIndex() != ITEM_INDEX || !(data.getValue() instanceof ItemStack)) {
                continue;
            }
            ItemStack item = (ItemStack) data.getValue();
            return item.hasComponent(ComponentTypes.MAP_ID);
        }
        return false;
    }

    private static BlockFace blockFace(Direction direction) {
        return switch (direction) {
            case D -> BlockFace.DOWN;
            case U -> BlockFace.UP;
            case N -> BlockFace.NORTH;
            case S -> BlockFace.SOUTH;
            case W -> BlockFace.WEST;
            case E -> BlockFace.EAST;
        };
    }

    private static double blockCenter(double coordinate) {
        return Math.floor(coordinate) + 0.5D;
    }

    private static Vector3d anchorPosition(double x,
                                           double y,
                                           double z,
                                           double xScale,
                                           double yScale,
                                           double zScale,
                                           double snapTolerance) {
        return new Vector3d(
            anchorCoordinate(x, xScale, snapTolerance),
            anchorCoordinate(y, yScale, snapTolerance),
            anchorCoordinate(z, zScale, snapTolerance));
    }

    private static double anchorCoordinate(double coordinate, double scale, double snapTolerance) {
        double snapped = ProjectorFrameTransform.snapNearInteger(coordinate, snapTolerance);
        return scale > 0.0D ? Math.ceil(snapped) - 1.0D : Math.floor(snapped);
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityData<T> replaceValue(EntityData<?> source, T value) {
        EntityDataType<T> type = (EntityDataType<T>) source.getType();
        return new EntityData<T>(source.getIndex(), type, value);
    }
}
