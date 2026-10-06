package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.network.client.PlateHandoff;
import art.arcane.optics.stream.PlateSectionBox;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.PlateCell;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Objects;

public final class ClientPlate implements ClientPortalContent {
    public static final int BRICK_OVERHEAD_BYTES = 64;
    private static final int BLOCK_ENTITY_OVERHEAD_BYTES = 24;
    private static final long SWEEP_BITMASKS = 5L;

    private final int portalKey;
    private final int revision;
    private final PlateSectionBox sections;
    private final BlockBox cells;
    private final int backingState;
    private final Brick[] bricks;
    private final PlateHandoff<BlockState> handoff;
    private final ClientPalette palette;
    private final IdentityHashMap<PlateCell<BlockState>, Integer> handoffIds;
    private final byte[][] handoffBlockLight;
    private final byte[][] handoffSkyLight;
    private final boolean[] handoffLightKnown;
    private final long bytes;

    private ClientPlate(int portalKey, int revision, PlateSectionBox sections, BlockBox cells, int backingState, Brick[] bricks,
                        PlateHandoff<BlockState> handoff, ClientPalette palette) {
        this.portalKey = portalKey;
        this.revision = revision;
        this.sections = Objects.requireNonNull(sections, "sections");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.backingState = backingState;
        this.bricks = bricks;
        this.handoff = handoff;
        this.palette = palette;
        int brickCount = sections.brickCount();
        this.handoffIds = handoff == null ? null : new IdentityHashMap<>(256);
        this.handoffBlockLight = handoff == null ? null : new byte[brickCount][];
        this.handoffSkyLight = handoff == null ? null : new byte[brickCount][];
        this.handoffLightKnown = handoff == null ? null : new boolean[brickCount];
        this.bytes = (handoff == null ? brickBytes(bricks) : handoff.plate().bytes()) + sweepBytes(cells);
    }

    public static ClientPlate fromBricks(int portalKey, int revision, PlateSectionBox sections, BlockBox cells, int backingState, Brick[] bricks) {
        Objects.requireNonNull(bricks, "bricks");
        if (bricks.length != sections.brickCount()) {
            throw new IllegalArgumentException("plate of " + bricks.length + " bricks for " + sections.brickCount() + " sections");
        }
        return new ClientPlate(portalKey, revision, sections, cells, backingState, bricks, null, null);
    }

    public static ClientPlate fromHandoff(PlateHandoff<BlockState> handoff, ClientPalette palette) {
        Objects.requireNonNull(handoff, "handoff");
        Objects.requireNonNull(palette, "palette");
        BlockBox box = handoff.plate().box();
        PlateSectionBox sections = PlateSectionBox.snap(box);
        int backingState = palette.localId(handoff.backingState());
        return new ClientPlate(handoff.portalKey(), handoff.plateRevision(), sections, box, backingState, null, handoff, palette);
    }

    public static long brickBytes(Brick brick) {
        if (brick == null || brick.isEmpty()) {
            return BRICK_OVERHEAD_BYTES;
        }
        long total = BRICK_OVERHEAD_BYTES + ((long) brick.packedIndices().length * 8L) + ((long) brick.localPalette().length * 4L);
        if (brick.hasLight()) {
            total += ViewStreamLimits.LIGHT_NIBBLE_BYTES * 2L;
        }
        Brick.BlockEntityCell[] blockEntities = brick.blockEntities();
        for (int index = 0; index < blockEntities.length; index++) {
            total += BLOCK_ENTITY_OVERHEAD_BYTES + blockEntities[index].payload().length;
        }
        return total;
    }

    public static long sweepBytes(BlockBox cells) {
        return SWEEP_BITMASKS * Long.BYTES * ((cells.cells() + 63L) >>> 6);
    }

    public static long brickBytes(Brick[] bricks) {
        long total = 0L;
        for (int index = 0; index < bricks.length; index++) {
            total += brickBytes(bricks[index]);
        }
        return total;
    }

    public int portalKey() {
        return portalKey;
    }

    public int revision() {
        return revision;
    }

    public PlateSectionBox sections() {
        return sections;
    }

    @Override
    public BlockBox cells() {
        return cells;
    }

    @Override
    public int backingState() {
        return backingState;
    }

    public int brickCount() {
        return sections.brickCount();
    }

    public boolean zeroCopy() {
        return handoff != null;
    }

    public long bytes() {
        return bytes;
    }

    public Brick brick(int brickIndex) {
        return bricks == null ? null : bricks[brickIndex];
    }

    public Brick[] bricksCopy() {
        return bricks == null ? new Brick[sections.brickCount()] : bricks.clone();
    }

    public int brickIndexOf(int x, int y, int z) {
        return sections.index(x >> 4, y >> 4, z >> 4);
    }

    public boolean contains(int x, int y, int z) {
        return x >= cells.minX() && y >= cells.minY() && z >= cells.minZ()
            && x < cells.minX() + cells.sizeX() && y < cells.minY() + cells.sizeY() && z < cells.minZ() + cells.sizeZ();
    }

    @Override
    public int paletteIdAt(int x, int y, int z) {
        if (!contains(x, y, z)) {
            return ViewStreamLimits.PALETTE_AIR;
        }
        if (handoff != null) {
            return handoffIdAt(x, y, z);
        }
        int brickIndex = brickIndexOf(x, y, z);
        if (brickIndex < 0) {
            return ViewStreamLimits.PALETTE_AIR;
        }
        Brick brick = bricks[brickIndex];
        return brick == null ? ViewStreamLimits.PALETTE_AIR : brick.paletteIdAt(ViewStreamLimits.brickCellIndex(x, y, z));
    }

    public boolean hasLight(int brickIndex) {
        if (handoff != null) {
            return handoffLight(brickIndex);
        }
        Brick brick = bricks[brickIndex];
        return brick != null && brick.hasLight();
    }

    public byte[] blockLight(int brickIndex) {
        if (handoff != null) {
            return handoffLight(brickIndex) ? handoffBlockLight[brickIndex] : null;
        }
        Brick brick = bricks[brickIndex];
        return brick == null ? null : brick.blockLight();
    }

    public byte[] skyLight(int brickIndex) {
        if (handoff != null) {
            return handoffLight(brickIndex) ? handoffSkyLight[brickIndex] : null;
        }
        Brick brick = bricks[brickIndex];
        return brick == null ? null : brick.skyLight();
    }

    @Override
    public BlockEntitySample blockEntityAt(int x, int y, int z) {
        if (handoff != null) {
            PlateCell<BlockState> cell = handoff.plate().cell(CellKeys.pack(x, y, z));
            return cell == null ? null : cell.blockEntity();
        }
        int brickIndex = brickIndexOf(x, y, z);
        if (brickIndex < 0) {
            return null;
        }
        Brick brick = bricks[brickIndex];
        if (brick == null || !brick.hasBlockEntities()) {
            return null;
        }
        int cellIndex = ViewStreamLimits.brickCellIndex(x, y, z);
        Brick.BlockEntityCell[] blockEntities = brick.blockEntities();
        for (int index = 0; index < blockEntities.length; index++) {
            if (blockEntities[index].cellIndex() == cellIndex) {
                try {
                    return BlockEntitySample.decode(blockEntities[index].payload());
                } catch (IOException failure) {
                    return null;
                }
            }
        }
        return null;
    }

    private int handoffIdAt(int x, int y, int z) {
        PlateCell<BlockState> cell = handoff.plate().cell(CellKeys.pack(x, y, z));
        if (cell == null) {
            return ViewStreamLimits.PALETTE_AIR;
        }
        ProjectorSample.Kind kind = cell.kind();
        return switch (kind) {
            case OCCLUDED -> ViewStreamLimits.PALETTE_OCCLUDED;
            case BACKING_BLOCK -> ViewStreamLimits.PALETTE_BACKING;
            case BLOCK -> handoffBlockId(cell);
            default -> ViewStreamLimits.PALETTE_AIR;
        };
    }

    private int handoffBlockId(PlateCell<BlockState> cell) {
        Integer known = handoffIds.get(cell);
        if (known != null) {
            return known;
        }
        BlockState state = cell.data();
        int id = state == null ? ViewStreamLimits.PALETTE_AIR : palette.localId(state);
        handoffIds.put(cell, id);
        return id;
    }

    private boolean handoffLight(int brickIndex) {
        if (handoffLightKnown[brickIndex]) {
            return handoffBlockLight[brickIndex] != null;
        }
        handoffLightKnown[brickIndex] = true;
        BrickLightSource light = handoff.light();
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        if (!light.fill(sections.sectionX(brickIndex), sections.sectionY(brickIndex), sections.sectionZ(brickIndex), block, sky)) {
            return false;
        }
        handoffBlockLight[brickIndex] = block;
        handoffSkyLight[brickIndex] = sky;
        return true;
    }
}
