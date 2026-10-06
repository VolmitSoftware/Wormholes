package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.client.ClientSweep;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.volume.ProjectionVolume;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ClientMirrorBuilder implements ClientPortalContent {
    public static final int MAX_LATERAL_BLOCKS = 40;
    private static final int UNKNOWN = Integer.MIN_VALUE;

    private final ApertureDescriptor geometry;
    private final OpticTransform transform;
    private final OpticTransform content;
    private final PlateBox box;
    private final int[] ids;
    private final ClientPalette palette;
    private final ClientStateReflector reflector;
    private final int[] cell;
    private final double[] scratch;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    private final ShadowSource shadows;
    private long resolvedCells;
    private long missingCells;

    private ClientMirrorBuilder(ApertureDescriptor geometry, PlateBox box, ClientPalette palette, ShadowSource shadows) {
        this.geometry = geometry;
        this.transform = geometry.mirrorTransform();
        this.content = transform.inverse();
        this.box = box;
        this.ids = new int[(int) box.cells()];
        this.palette = palette;
        this.reflector = new ClientStateReflector(List.of(geometry));
        this.cell = new int[3];
        this.scratch = new double[3];
        this.shadows = shadows;
        Arrays.fill(ids, UNKNOWN);
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? box.minX() : box.minX() + box.sizeX();
            double y = (corner & 2) == 0 ? box.minY() : box.minY() + box.sizeY();
            double z = (corner & 4) == 0 ? box.minZ() : box.minZ() + box.sizeZ();
            content.pointInto(x, y, z, scratch);
            minX = Math.min(minX, scratch[0]);
            minZ = Math.min(minZ, scratch[2]);
            maxX = Math.max(maxX, scratch[0]);
            maxZ = Math.max(maxZ, scratch[2]);
        }
        this.minChunkX = ((int) Math.floor(minX)) >> 4;
        this.minChunkZ = ((int) Math.floor(minZ)) >> 4;
        this.maxChunkX = ((int) Math.floor(maxX)) >> 4;
        this.maxChunkZ = ((int) Math.floor(maxZ)) >> 4;
    }

    public static ClientMirrorBuilder create(ApertureDescriptor geometry, ClientPalette palette, ShadowSource shadows) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(shadows, "shadows");
        if (!geometry.mirror() || !geometry.valid()) {
            return null;
        }
        PlateBox box = displayBox(geometry);
        if (box.cells() == 0L || box.cells() > ClientSweep.MAX_BOUNDS_CELLS) {
            return null;
        }
        return new ClientMirrorBuilder(geometry, box, palette, shadows);
    }

    public static PlateBox displayBox(ApertureDescriptor geometry) {
        Box area = geometry.apertureArea();
        Frame frame = geometry.frame();
        Face normal = frame.getNormal();
        int normalAxis = axisOf(normal);
        double facing = normalAxis == 0 ? normal.x() : normalAxis == 1 ? normal.y() : normal.z();
        double origin = (low(area, normalAxis) + high(area, normalAxis)) * 0.5D;
        double clearance = ProjectionVolume.portalPlaneClearance(area, frame);
        double maxDepth = geometry.depthBlocks() + clearance;
        double signedMin = geometry.frontSide() ? -maxDepth : clearance;
        double signedMax = geometry.frontSide() ? -clearance : maxDepth;
        double centerA = origin + (signedMin / facing);
        double centerB = origin + (signedMax / facing);
        int[] min = new int[3];
        int[] max = new int[3];
        min[normalAxis] = ProjectionVolume.minBlockForCenter(Math.min(centerA, centerB));
        max[normalAxis] = ProjectionVolume.maxBlockForCenter(Math.max(centerA, centerB));
        double pad = Math.min(geometry.depthBlocks(), MAX_LATERAL_BLOCKS) + Math.max(0.0D, geometry.aperturePadding());
        for (int axis = 0; axis < 3; axis++) {
            if (axis == normalAxis) {
                continue;
            }
            min[axis] = ProjectionVolume.minBlockForCenter(low(area, axis) - pad);
            max[axis] = ProjectionVolume.maxBlockForCenter(high(area, axis) + pad);
        }
        return PlateBox.spanning(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    public ApertureDescriptor geometry() {
        return geometry;
    }

    public OpticTransform transform() {
        return transform;
    }

    @Override
    public PlateBox cells() {
        return box;
    }

    @Override
    public int backingState() {
        return ViewStreamLimits.PALETTE_AIR;
    }

    @Override
    public int paletteIdAt(int x, int y, int z) {
        int index = box.index(x, y, z);
        if (index < 0) {
            return ViewStreamLimits.PALETTE_AIR;
        }
        int known = ids[index];
        if (known != UNKNOWN) {
            return known;
        }
        content.cellInto(x, y, z, cell);
        BlockState shadow = shadows.shadow(cell[0], cell[1], cell[2]);
        if (shadow == null) {
            missingCells++;
            return ViewStreamLimits.PALETTE_AIR;
        }
        int id = palette.localId(reflector.reflect(shadow));
        ids[index] = id;
        resolvedCells++;
        return id;
    }

    @Override
    public BlockEntitySample blockEntityAt(int x, int y, int z) {
        return null;
    }

    public boolean sourceChanged(int x, int y, int z, LongArrayList displayOut) {
        transform.cellInto(x, y, z, cell);
        int index = box.index(cell[0], cell[1], cell[2]);
        if (index < 0) {
            return false;
        }
        ids[index] = UNKNOWN;
        displayOut.add(CellKeys.pack(cell[0], cell[1], cell[2]));
        return true;
    }

    public boolean sourceChunk(int chunkX, int chunkZ) {
        return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    public void invalidateAll() {
        Arrays.fill(ids, UNKNOWN);
    }

    public long resolvedCells() {
        return resolvedCells;
    }

    public long missingCells() {
        return missingCells;
    }

    private static int axisOf(Face direction) {
        return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
    }

    private static double low(Box box, int axis) {
        return axis == 0 ? box.getXa() : axis == 1 ? box.getYa() : box.getZa();
    }

    private static double high(Box box, int axis) {
        return axis == 0 ? box.getXb() : axis == 1 ? box.getYb() : box.getZb();
    }

    @FunctionalInterface
    public interface ShadowSource {
        BlockState shadow(int x, int y, int z);
    }
}
