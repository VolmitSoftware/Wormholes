package art.arcane.wormholes.render.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.portal.PortalCellAperture;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public record ClientPortalGeometry(int originX,
                                   int originY,
                                   int originZ,
                                   int facing,
                                   boolean frontSide,
                                   int quarterTurns,
                                   boolean mirror,
                                   int apertureWidth,
                                   int apertureHeight,
                                   long[] apertureMask,
                                   float nearPlanePadding,
                                   float aperturePadding,
                                   float frustumCullingRatio,
                                   int depthBlocks,
                                   int recursionDepth,
                                   int blackoutPolicy,
                                   int blackoutState,
                                   int maskAirPolicy,
                                   int lightingPolicy,
                                   int fidelityFlags,
                                   int kind,
                                   int parentPortalKey,
                                   long targetIdentity,
                                   List<ClientPortalGeometry> nested) {
    public static final int KIND_FRAME = 0;
    public static final int KIND_RTP = 1;
    public static final int KIND_DOOR = 2;
    public static final int KIND_VANILLA_REPLACEMENT = 3;
    public static final int FIDELITY_DISPLAY_ENTITIES = 1;
    public static final int FIDELITY_LIGHTING = 1 << 1;
    public static final int FIDELITY_WEATHER = 1 << 2;
    public static final int FIDELITY_SOUNDS = 1 << 3;
    public static final int BLACKOUT_OFF = 0;
    public static final int BLACKOUT_SHELL = 1;
    public static final int BLACKOUT_SHELL_AND_BURIED = 2;
    public static final int MASK_AIR_PROJECT = 0;
    public static final int MASK_AIR_KEEP_REAL = 1;
    public static final int MAX_APERTURE_EDGE = 0xFFFF;
    public static final int MAX_DEPTH_BLOCKS = 0xFFFF;
    public static final int MAX_APERTURE_CELLS = 1 << 20;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final ProjectedBlockClaim.LightingPolicy[] LIGHTING_POLICIES = ProjectedBlockClaim.LightingPolicy.values();
    private static final double BLOCK_EXTENT = 0.999D;

    public ClientPortalGeometry {
        Objects.requireNonNull(apertureMask, "apertureMask");
        nested = nested == null ? List.of() : List.copyOf(nested);
    }

    public static Optional<ClientPortalGeometry> fromPortal(Source source) {
        Objects.requireNonNull(source, "source");
        PortalCellAperture aperture = Objects.requireNonNull(source.aperture(), "aperture");
        PortalFrame frame = Objects.requireNonNull(source.frame(), "frame");
        AxisAlignedBB area = aperture.getArea();
        if (area == null) {
            return Optional.empty();
        }
        int[] min = {(int) Math.floor(area.getXa()), (int) Math.floor(area.getYa()), (int) Math.floor(area.getZa())};
        int[] max = {(int) Math.floor(area.getXb()), (int) Math.floor(area.getYb()), (int) Math.floor(area.getZb())};
        Direction normal = frame.getNormal();
        PortalFrame canonical = PortalFrame.canonical(normal);
        int normalAxis = axisOf(normal);
        int columnAxis = axisOf(canonical.getRight());
        int rowAxis = axisOf(canonical.getUp());
        int frameTurns = quarterTurnsFrom(canonical, frame);
        long width = (long) max[columnAxis] - min[columnAxis] + 1L;
        long height = (long) max[rowAxis] - min[rowAxis] + 1L;
        if (max[normalAxis] != min[normalAxis] || frameTurns < 0
            || width < 1L || height < 1L || width > MAX_APERTURE_EDGE || height > MAX_APERTURE_EDGE
            || width * height > MAX_APERTURE_CELLS
            || source.depthBlocks() < 0 || source.depthBlocks() > MAX_DEPTH_BLOCKS
            || source.mirrorQuarterTurns() < 0 || source.mirrorQuarterTurns() > 3) {
            return Optional.empty();
        }
        int columns = (int) width;
        int rows = (int) height;
        boolean[] open = new boolean[columns * rows];
        int[] cell = new int[3];
        cell[normalAxis] = min[normalAxis];
        for (int row = 0; row < rows; row++) {
            cell[rowAxis] = min[rowAxis] + row;
            for (int column = 0; column < columns; column++) {
                cell[columnAxis] = min[columnAxis] + column;
                open[(row * columns) + column] = aperture.containsBlock(cell[0], cell[1], cell[2]);
            }
        }
        ProjectedBlockClaim.LightingPolicy lighting = source.lightingPolicy() == null
            ? ProjectedBlockClaim.LightingPolicy.LOCAL
            : source.lightingPolicy();
        return Optional.of(new ClientPortalGeometry(min[0], min[1], min[2], normal.ordinal(), source.frontSide(),
            packQuarterTurns(frameTurns, source.mirror() ? source.mirrorQuarterTurns() : 0), source.mirror(),
            columns, rows, apertureMask(columns, rows, open),
            (float) source.nearPlanePadding(), (float) source.aperturePadding(), (float) source.frustumCullingRatio(),
            source.depthBlocks(), Math.max(0, source.recursionDepth()), source.blackoutPolicy(), source.blackoutState(),
            source.maskAirPolicy(), lighting.ordinal(), source.fidelityFlags(), source.kind(), source.parentPortalKey(),
            source.targetIdentity(), source.nested()));
    }

    public static int packQuarterTurns(int frameQuarterTurns, int mirrorQuarterTurns) {
        return (frameQuarterTurns & 3) | ((mirrorQuarterTurns & 3) << 2);
    }

    public static long[] apertureMask(int width, int height, boolean[] open) {
        long[] mask = new long[(Math.max(0, width * height) + 63) >>> 6];
        for (int bit = 0; bit < open.length; bit++) {
            if (open[bit]) {
                mask[bit >>> 6] |= 1L << (bit & 63);
            }
        }
        return mask;
    }

    public boolean valid() {
        return facing >= 0 && facing < DIRECTIONS.length
            && quarterTurns >= 0 && quarterTurns <= 15
            && apertureWidth >= 1 && apertureHeight >= 1
            && apertureWidth <= MAX_APERTURE_EDGE && apertureHeight <= MAX_APERTURE_EDGE
            && (long) apertureWidth * apertureHeight <= MAX_APERTURE_CELLS
            && apertureMask.length == (((long) apertureWidth * apertureHeight) + 63L) >>> 6
            && openCellCount() > 0
            && depthBlocks >= 0 && depthBlocks <= MAX_DEPTH_BLOCKS
            && recursionDepth >= 0
            && Float.isFinite(nearPlanePadding) && nearPlanePadding >= 0.0F
            && Float.isFinite(aperturePadding) && aperturePadding >= 0.0F
            && Float.isFinite(frustumCullingRatio)
            && blackoutPolicy >= BLACKOUT_OFF && blackoutPolicy <= BLACKOUT_SHELL_AND_BURIED
            && maskAirPolicy >= MASK_AIR_PROJECT && maskAirPolicy <= MASK_AIR_KEEP_REAL
            && lightingPolicy >= 0 && lightingPolicy < LIGHTING_POLICIES.length
            && kind >= KIND_FRAME && kind <= KIND_VANILLA_REPLACEMENT;
    }

    public ClientPortalGeometry withParent(int parentKey) {
        return new ClientPortalGeometry(originX, originY, originZ, facing, frontSide, quarterTurns, mirror, apertureWidth, apertureHeight,
            apertureMask, nearPlanePadding, aperturePadding, frustumCullingRatio, depthBlocks, recursionDepth, blackoutPolicy, blackoutState,
            maskAirPolicy, lightingPolicy, fidelityFlags, kind, parentKey, targetIdentity, nested);
    }

    public ClientPortalGeometry withNested(List<ClientPortalGeometry> children) {
        return new ClientPortalGeometry(originX, originY, originZ, facing, frontSide, quarterTurns, mirror, apertureWidth, apertureHeight,
            apertureMask, nearPlanePadding, aperturePadding, frustumCullingRatio, depthBlocks, recursionDepth, blackoutPolicy, blackoutState,
            maskAirPolicy, lightingPolicy, fidelityFlags, kind, parentPortalKey, targetIdentity, children);
    }

    public boolean apertureOpen(int column, int row) {
        if (column < 0 || row < 0 || column >= apertureWidth || row >= apertureHeight) {
            return false;
        }
        int bit = (row * apertureWidth) + column;
        int word = bit >>> 6;
        return word < apertureMask.length && (apertureMask[word] & (1L << (bit & 63))) != 0L;
    }

    public int openCellCount() {
        int count = 0;
        int cells = apertureWidth * apertureHeight;
        for (int word = 0; word < apertureMask.length; word++) {
            long bits = apertureMask[word];
            int remaining = cells - (word << 6);
            if (remaining <= 0) {
                break;
            }
            if (remaining < 64) {
                bits &= (1L << remaining) - 1L;
            }
            count += Long.bitCount(bits);
        }
        return count;
    }

    public Direction facingDirection() {
        return DIRECTIONS[facing];
    }

    public double planeCoordinate() {
        Direction normal = facingDirection();
        int origin = normal.x() != 0 ? originX : normal.y() != 0 ? originY : originZ;
        return origin + 0.5D + (kind == KIND_DOOR ? (normal.x() + normal.y() + normal.z()) * DoorwayPlane.planeOffset(normal) : 0.0D);
    }

    public double signedDistance(double x, double y, double z) {
        Direction normal = facingDirection();
        return normal.x() * x + normal.y() * y + normal.z() * z
            - (normal.x() + normal.y() + normal.z()) * planeCoordinate();
    }

    public int frameQuarterTurns() {
        return quarterTurns & 3;
    }

    public int mirrorQuarterTurns() {
        return (quarterTurns >> 2) & 3;
    }

    public ClientPortalGeometry withDepth(int depth) {
        return new ClientPortalGeometry(originX, originY, originZ, facing, frontSide, quarterTurns, mirror, apertureWidth,
            apertureHeight, apertureMask, nearPlanePadding, aperturePadding, frustumCullingRatio, depth, recursionDepth,
            blackoutPolicy, blackoutState, maskAirPolicy, lightingPolicy, fidelityFlags, kind, parentPortalKey, targetIdentity, nested);
    }

    public PortalFrame frame() {
        PortalFrame frame = PortalFrame.canonical(facingDirection());
        for (int turn = 0; turn < frameQuarterTurns(); turn++) {
            frame = frame.rotateClockwise();
        }
        return frame;
    }

    public ProjectedBlockClaim.LightingPolicy lightingPolicyType() {
        return LIGHTING_POLICIES[lightingPolicy];
    }

    public boolean hasFidelity(int flag) {
        return (fidelityFlags & flag) != 0;
    }

    public AxisAlignedBB apertureArea() {
        int[] min = {originX, originY, originZ};
        int[] max = {originX, originY, originZ};
        PortalFrame canonical = PortalFrame.canonical(facingDirection());
        max[axisOf(canonical.getRight())] += apertureWidth - 1;
        max[axisOf(canonical.getUp())] += apertureHeight - 1;
        return new AxisAlignedBB(min[0], max[0] + BLOCK_EXTENT, min[1], max[1] + BLOCK_EXTENT, min[2], max[2] + BLOCK_EXTENT);
    }

    public PortalGeometry aperture() {
        PortalFrame canonical = PortalFrame.canonical(facingDirection());
        int columnAxis = axisOf(canonical.getRight());
        int rowAxis = axisOf(canonical.getUp());
        List<GeometryVector> cells = new ArrayList<GeometryVector>(openCellCount());
        int[] origin = {originX, originY, originZ};
        int[] cell = {originX, originY, originZ};
        for (int row = 0; row < apertureHeight; row++) {
            cell[rowAxis] = origin[rowAxis] + row;
            for (int column = 0; column < apertureWidth; column++) {
                if (!apertureOpen(column, row)) {
                    continue;
                }
                cell[columnAxis] = origin[columnAxis] + column;
                cells.add(new GeometryVector(cell[0], cell[1], cell[2]));
            }
        }
        PortalGeometry geometry = new PortalGeometry();
        geometry.restore(apertureArea(), cells);
        return geometry;
    }

    @Override
    public long[] apertureMask() {
        return apertureMask.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClientPortalGeometry that)) {
            return false;
        }
        return sameSurface(that) && nested.equals(that.nested);
    }

    public boolean sameSurface(ClientPortalGeometry that) {
        return sameSurface(that, false);
    }

    public boolean sameContentSurface(ClientPortalGeometry that) {
        return sameSurface(that, true);
    }

    private boolean sameSurface(ClientPortalGeometry that, boolean ignoreSide) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        return originX == that.originX && originY == that.originY && originZ == that.originZ
            && facing == that.facing && (ignoreSide || frontSide == that.frontSide) && quarterTurns == that.quarterTurns && mirror == that.mirror
            && apertureWidth == that.apertureWidth && apertureHeight == that.apertureHeight
            && Arrays.equals(apertureMask, that.apertureMask)
            && Float.floatToIntBits(nearPlanePadding) == Float.floatToIntBits(that.nearPlanePadding)
            && Float.floatToIntBits(aperturePadding) == Float.floatToIntBits(that.aperturePadding)
            && Float.floatToIntBits(frustumCullingRatio) == Float.floatToIntBits(that.frustumCullingRatio)
            && depthBlocks == that.depthBlocks && recursionDepth == that.recursionDepth
            && blackoutPolicy == that.blackoutPolicy && blackoutState == that.blackoutState && maskAirPolicy == that.maskAirPolicy
            && lightingPolicy == that.lightingPolicy && fidelityFlags == that.fidelityFlags && kind == that.kind
            && parentPortalKey == that.parentPortalKey && targetIdentity == that.targetIdentity;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(originX, originY, originZ, facing, frontSide, quarterTurns, mirror, apertureWidth, apertureHeight,
            nearPlanePadding, aperturePadding, frustumCullingRatio, depthBlocks, recursionDepth, blackoutPolicy, blackoutState,
            maskAirPolicy, lightingPolicy, fidelityFlags, kind, parentPortalKey, targetIdentity, nested);
        return result * 31 + Arrays.hashCode(apertureMask);
    }

    @Override
    public String toString() {
        return "ClientPortalGeometry[origin=" + originX + "," + originY + "," + originZ
            + ", facing=" + facing + ", frontSide=" + frontSide + ", quarterTurns=" + quarterTurns + ", mirror=" + mirror
            + ", aperture=" + apertureWidth + "x" + apertureHeight + ", mask=" + Arrays.toString(apertureMask)
            + ", nearPlanePadding=" + nearPlanePadding + ", aperturePadding=" + aperturePadding
            + ", frustumCullingRatio=" + frustumCullingRatio + ", depthBlocks=" + depthBlocks + ", recursionDepth=" + recursionDepth
            + ", blackoutPolicy=" + blackoutPolicy + ", blackoutState=" + blackoutState + ", maskAirPolicy=" + maskAirPolicy
            + ", lightingPolicy=" + lightingPolicy + ", fidelityFlags=" + fidelityFlags + ", kind=" + kind
            + ", parentPortalKey=" + parentPortalKey + ", targetIdentity=" + targetIdentity + ", nested=" + nested + "]";
    }

    public static int axisOf(Direction direction) {
        return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
    }

    private static int quarterTurnsFrom(PortalFrame canonical, PortalFrame frame) {
        PortalFrame candidate = canonical;
        for (int turn = 0; turn < 4; turn++) {
            if (candidate.getNormal() == frame.getNormal() && candidate.getRight() == frame.getRight()
                && candidate.getUp() == frame.getUp()) {
                return turn;
            }
            candidate = candidate.rotateClockwise();
        }
        return -1;
    }

    public record Source(PortalCellAperture aperture,
                         PortalFrame frame,
                         boolean frontSide,
                         boolean mirror,
                         int mirrorQuarterTurns,
                         double nearPlanePadding,
                         double aperturePadding,
                         double frustumCullingRatio,
                         int depthBlocks,
                         int recursionDepth,
                         int blackoutPolicy,
                         int blackoutState,
                         int maskAirPolicy,
                         ProjectedBlockClaim.LightingPolicy lightingPolicy,
                         int fidelityFlags,
                         int kind,
                         int parentPortalKey,
                         long targetIdentity,
                         List<ClientPortalGeometry> nested) {
    }
}
