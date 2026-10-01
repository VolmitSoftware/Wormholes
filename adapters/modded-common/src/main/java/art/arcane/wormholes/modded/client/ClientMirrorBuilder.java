package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectorFrameTransform;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.ClientSpace;
import art.arcane.wormholes.render.client.ClientViewSweep;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ClientMirrorBuilder implements ClientPortalContent {
    public static final int MAX_LATERAL_BLOCKS = 40;
    private static final int UNKNOWN = Integer.MIN_VALUE;

    private final ClientPortalGeometry geometry;
    private final ClientSpace space;
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

    private ClientMirrorBuilder(ClientPortalGeometry geometry, PlateBox box, ClientPalette palette, ShadowSource shadows) {
        this.geometry = geometry;
        this.space = ClientSpace.mirror(geometry);
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
            space.toContent(x, y, z, scratch);
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

    public static ClientMirrorBuilder create(ClientPortalGeometry geometry, ClientPalette palette, ShadowSource shadows) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(shadows, "shadows");
        if (!geometry.mirror() || !geometry.valid()) {
            return null;
        }
        PlateBox box = displayBox(geometry);
        if (box.cells() == 0L || box.cells() > ClientViewSweep.MAX_BOUNDS_CELLS) {
            return null;
        }
        return new ClientMirrorBuilder(geometry, box, palette, shadows);
    }

    public static PlateBox displayBox(ClientPortalGeometry geometry) {
        AxisAlignedBB area = geometry.apertureArea();
        PortalFrame frame = geometry.frame();
        Direction normal = frame.getNormal();
        int normalAxis = axisOf(normal);
        double facing = normalAxis == 0 ? normal.x() : normalAxis == 1 ? normal.y() : normal.z();
        double origin = (low(area, normalAxis) + high(area, normalAxis)) * 0.5D;
        double clearance = ProjectorFrameTransform.portalPlaneClearance(area, frame);
        double maxDepth = geometry.depthBlocks() + clearance;
        double signedMin = geometry.frontSide() ? -maxDepth : clearance;
        double signedMax = geometry.frontSide() ? -clearance : maxDepth;
        double centerA = origin + (signedMin / facing);
        double centerB = origin + (signedMax / facing);
        int[] min = new int[3];
        int[] max = new int[3];
        min[normalAxis] = ProjectorFrameTransform.minBlockForCenter(Math.min(centerA, centerB));
        max[normalAxis] = ProjectorFrameTransform.maxBlockForCenter(Math.max(centerA, centerB));
        double pad = Math.min(geometry.depthBlocks(), MAX_LATERAL_BLOCKS) + Math.max(0.0D, geometry.aperturePadding());
        for (int axis = 0; axis < 3; axis++) {
            if (axis == normalAxis) {
                continue;
            }
            min[axis] = ProjectorFrameTransform.minBlockForCenter(low(area, axis) - pad);
            max[axis] = ProjectorFrameTransform.maxBlockForCenter(high(area, axis) + pad);
        }
        return PlateBox.spanning(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    public ClientPortalGeometry geometry() {
        return geometry;
    }

    public ClientSpace space() {
        return space;
    }

    @Override
    public PlateBox cells() {
        return box;
    }

    @Override
    public int backingState() {
        return ClientViewProtocol.PALETTE_AIR;
    }

    @Override
    public int paletteIdAt(int x, int y, int z) {
        int index = box.index(x, y, z);
        if (index < 0) {
            return ClientViewProtocol.PALETTE_AIR;
        }
        int known = ids[index];
        if (known != UNKNOWN) {
            return known;
        }
        space.contentCell(x, y, z, cell, scratch);
        BlockState shadow = shadows.shadow(cell[0], cell[1], cell[2]);
        if (shadow == null) {
            missingCells++;
            return ClientViewProtocol.PALETTE_AIR;
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
        space.displayCell(x, y, z, cell, scratch);
        int index = box.index(cell[0], cell[1], cell[2]);
        if (index < 0) {
            return false;
        }
        ids[index] = UNKNOWN;
        displayOut.add(ProjectionCellKey.pack(cell[0], cell[1], cell[2]));
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

    private static int axisOf(Direction direction) {
        return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
    }

    private static double low(AxisAlignedBB box, int axis) {
        return axis == 0 ? box.getXa() : axis == 1 ? box.getYa() : box.getZa();
    }

    private static double high(AxisAlignedBB box, int axis) {
        return axis == 0 ? box.getXb() : axis == 1 ? box.getYb() : box.getZb();
    }

    @FunctionalInterface
    public interface ShadowSource {
        BlockState shadow(int x, int y, int z);
    }
}
