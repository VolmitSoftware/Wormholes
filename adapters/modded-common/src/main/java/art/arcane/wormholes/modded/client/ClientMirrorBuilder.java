package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftProjectorBlocks;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.client.ClientSweep;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.math.Box;
import art.arcane.optics.volume.ApertureSlab;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Objects;

public final class ClientMirrorBuilder implements ClientPortalContent {
    public static final int MAX_LATERAL_BLOCKS = 40;
    private static final int UNKNOWN = Integer.MIN_VALUE;

    private final ApertureDescriptor geometry;
    private final OpticTransform transform;
    private final OpticTransform content;
    private final BlockBox box;
    private final int[] ids;
    private final ClientPalette palette;
    private final AxisPermutation permutation;
    private final int[] cell;
    private final double[] scratch;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    private final ShadowSource shadows;
    private long resolvedCells;
    private long missingCells;

    private ClientMirrorBuilder(ApertureDescriptor geometry, BlockBox box, ClientPalette palette, ShadowSource shadows) {
        this.geometry = geometry;
        this.transform = geometry.mirrorTransform();
        this.content = transform.inverse();
        this.box = box;
        this.ids = new int[(int) box.cells()];
        this.palette = palette;
        this.permutation = transform.permutation();
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
        BlockBox box = displayBox(geometry);
        if (box.cells() == 0L || box.cells() > ClientSweep.MAX_BOUNDS_CELLS) {
            return null;
        }
        return new ClientMirrorBuilder(geometry, box, palette, shadows);
    }

    public static BlockBox displayBox(ApertureDescriptor geometry) {
        Box area = geometry.apertureArea();
        Frame frame = geometry.frame();
        double plane = ApertureSlab.plane(frame, (area.getXa() + area.getXb()) * 0.5D, (area.getYa() + area.getYb()) * 0.5D,
            (area.getZa() + area.getZb()) * 0.5D);
        double pad = Math.min(geometry.depthBlocks(), MAX_LATERAL_BLOCKS) + Math.max(0.0D, geometry.aperturePadding());
        return ApertureSlab.of(area, frame, plane, geometry.frontSide(), geometry.depthBlocks(), pad).box();
    }

    public ApertureDescriptor geometry() {
        return geometry;
    }

    public OpticTransform transform() {
        return transform;
    }

    @Override
    public BlockBox cells() {
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
        int id = palette.localId(MinecraftProjectorBlocks.INSTANCE.transform(shadow, permutation));
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

    @FunctionalInterface
    public interface ShadowSource {
        BlockState shadow(int x, int y, int z);
    }
}
