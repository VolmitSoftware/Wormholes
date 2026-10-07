package art.arcane.optics.stream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Function;

import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.PlateCell;
import art.arcane.optics.plate.PlateGrid;
import art.arcane.optics.plate.ViewPlate;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

public final class PlateStreamEncoder<B> {
    private static final String FALLBACK_BACKING_STATE = "minecraft:stone";

    private final SessionPalette palette;
    private final Function<B, String> stateStrings;
    private final Map<ViewPlate<B>, EncodedPlate> cache;
    private long encodes;

    public PlateStreamEncoder(SessionPalette palette, Function<B, String> stateStrings) {
        this.palette = Objects.requireNonNull(palette, "palette");
        this.stateStrings = Objects.requireNonNull(stateStrings, "stateStrings");
        this.cache = new WeakHashMap<ViewPlate<B>, EncodedPlate>();
    }

    public SessionPalette palette() {
        return palette;
    }

    public synchronized long encodes() {
        return encodes;
    }

    public EncodedPlate cached(ViewPlate<B> plate) {
        synchronized (cache) {
            return cache.get(plate);
        }
    }

    public EncodedPlate encode(ViewPlate<B> plate, BrickLightSource light, boolean blockEntities) {
        synchronized (cache) {
            EncodedPlate cached = cache.get(plate);
            if (cached != null) {
                return cached;
            }
        }
        EncodedPlate encoded = encodeFresh(plate, light == null ? BrickLightSource.NONE : light, blockEntities);
        synchronized (cache) {
            EncodedPlate raced = cache.get(plate);
            if (raced != null) {
                return raced;
            }
            cache.put(plate, encoded);
        }
        return encoded;
    }

    public void forget(ViewPlate<B> plate) {
        synchronized (cache) {
            cache.remove(plate);
        }
    }

    private EncodedPlate encodeFresh(ViewPlate<B> plate, BrickLightSource light, boolean blockEntities) {
        synchronized (this) {
            encodes++;
        }
        BlockBox box = plate.box();
        PlateSectionBox sections = PlateSectionBox.snap(box);
        int brickCount = sections.brickCount();
        Brick[] bricks = new Brick[brickCount];
        byte[][] bodies = new byte[brickCount][];
        IdentityHashMap<PlateCell<B>, Integer> cellIds = new IdentityHashMap<PlateCell<B>, Integer>(256);
        IdentityHashMap<PlateCell<B>, String> cellStates = new IdentityHashMap<PlateCell<B>, String>(64);
        Object2IntOpenHashMap<String> backingVotes = new Object2IntOpenHashMap<String>(16);
        IntOpenHashSet referenced = new IntOpenHashSet(256);
        int[] kindCounts = new int[ProjectorSample.Kind.values().length + 1];
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        int paletteSize = plate.paletteSize();
        int[] refIds = new int[paletteSize + 1];
        Arrays.fill(refIds, -1);
        String[] refStates = new String[paletteSize + 1];
        List<PlateCell<B>> refCells = new ArrayList<PlateCell<B>>(Collections.nCopies(paletteSize + 1, (PlateCell<B>) null));
        List<Brick.BlockEntityCell> blockEntityCells = new ArrayList<Brick.BlockEntityCell>();
        int maxX = box.minX() + box.sizeX();
        int maxY = box.minY() + box.sizeY();
        int maxZ = box.minZ() + box.sizeZ();
        for (int brickIndex = 0; brickIndex < brickCount; brickIndex++) {
            int baseX = sections.sectionX(brickIndex) << 4;
            int baseY = sections.sectionY(brickIndex) << 4;
            int baseZ = sections.sectionZ(brickIndex) << 4;
            blockEntityCells.clear();
            int blockEntityBytes = 0;
            for (int y = 0; y < ViewStreamLimits.BRICK_EDGE; y++) {
                int worldY = baseY + y;
                boolean rowY = worldY >= box.minY() && worldY < maxY;
                for (int z = 0; z < ViewStreamLimits.BRICK_EDGE; z++) {
                    int worldZ = baseZ + z;
                    boolean rowZ = rowY && worldZ >= box.minZ() && worldZ < maxZ;
                    int rowBase = (y << 8) | (z << 4);
                    for (int x = 0; x < ViewStreamLimits.BRICK_EDGE; x++) {
                        int worldX = baseX + x;
                        int cellIndex = rowBase | x;
                        if (!rowZ || worldX < box.minX() || worldX >= maxX) {
                            cells[cellIndex] = ViewStreamLimits.PALETTE_AIR;
                            continue;
                        }
                        int ref = plate.cellRef(worldX, worldY, worldZ);
                        boolean entityCell = PlateGrid.blockEntityRef(ref);
                        PlateCell<B> cell = null;
                        if (entityCell) {
                            cell = plate.cell(CellKeys.pack(worldX, worldY, worldZ));
                        } else if (ref != PlateGrid.NO_CELL) {
                            cell = refCells.get(ref);
                            if (cell == null) {
                                cell = plate.paletteCell(ref);
                                refCells.set(ref, cell);
                            }
                        }
                        if (cell == null) {
                            cells[cellIndex] = ViewStreamLimits.PALETTE_AIR;
                            kindCounts[kindCounts.length - 1]++;
                            continue;
                        }
                        ProjectorSample.Kind kind = cell.kind();
                        kindCounts[kind.ordinal()]++;
                        int id;
                        switch (kind) {
                            case OCCLUDED -> id = ViewStreamLimits.PALETTE_OCCLUDED;
                            case BACKING_BLOCK -> {
                                id = ViewStreamLimits.PALETTE_BACKING;
                                backingVotes.addTo(entityCell ? stateString(cell, cellStates) : refState(ref, cell, refStates), 1);
                            }
                            case BLOCK -> {
                                if (entityCell) {
                                    id = stateId(cell, cellIds);
                                    referenced.add(id);
                                } else {
                                    id = refIds[ref];
                                    if (id < 0) {
                                        id = palette.id(stateStrings.apply(cell.data()));
                                        refIds[ref] = id;
                                        referenced.add(id);
                                    }
                                }
                                if (blockEntities && cell.blockEntity() != null
                                    && blockEntityCells.size() < ViewStreamLimits.MAX_BRICK_BLOCK_ENTITIES) {
                                    byte[] payload = blockEntityPayload(cell.blockEntity());
                                    if (payload != null && blockEntityBytes + payload.length <= ViewStreamLimits.MAX_BRICK_BLOCK_ENTITY_BYTES) {
                                        blockEntityBytes += payload.length;
                                        blockEntityCells.add(new Brick.BlockEntityCell(cellIndex, payload));
                                    }
                                }
                            }
                            default -> id = ViewStreamLimits.PALETTE_AIR;
                        }
                        cells[cellIndex] = id;
                    }
                }
            }
            Brick brick = BrickCodec.pack(brickIndex, cells);
            if (!brick.isEmpty() && !blockEntityCells.isEmpty()) {
                brick = brick.withBlockEntities(blockEntityCells.toArray(new Brick.BlockEntityCell[0]));
            }
            if (light != BrickLightSource.NONE) {
                byte[] blockNibbles = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
                byte[] skyNibbles = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
                if (light.fill(sections.sectionX(brickIndex), sections.sectionY(brickIndex), sections.sectionZ(brickIndex), blockNibbles, skyNibbles)) {
                    brick = brick.withLight(blockNibbles, skyNibbles);
                }
            }
            bricks[brickIndex] = brick;
            bodies[brickIndex] = body(brick);
        }
        String backing = vote(backingVotes);
        int backingState = palette.id(backing == null ? FALLBACK_BACKING_STATE : backing);
        referenced.add(backingState);
        int[] referencedIds = referenced.toIntArray();
        Arrays.sort(referencedIds);
        return new EncodedPlate(sections, box, backingState, bricks, bodies, referencedIds, kindCounts);
    }

    private int stateId(PlateCell<B> cell, IdentityHashMap<PlateCell<B>, Integer> cellIds) {
        Integer known = cellIds.get(cell);
        if (known != null) {
            return known;
        }
        int id = palette.id(stateStrings.apply(cell.data()));
        cellIds.put(cell, id);
        return id;
    }

    private String refState(int ref, PlateCell<B> cell, String[] refStates) {
        String known = refStates[ref];
        if (known != null) {
            return known;
        }
        String state = SessionPalette.canonical(stateStrings.apply(cell.data()));
        refStates[ref] = state;
        return state;
    }

    private String stateString(PlateCell<B> cell, IdentityHashMap<PlateCell<B>, String> cellStates) {
        String known = cellStates.get(cell);
        if (known != null) {
            return known;
        }
        String state = SessionPalette.canonical(stateStrings.apply(cell.data()));
        cellStates.put(cell, state);
        return state;
    }

    private static String vote(Object2IntOpenHashMap<String> votes) {
        String best = null;
        int bestCount = 0;
        for (Object2IntOpenHashMap.Entry<String> entry : votes.object2IntEntrySet()) {
            int count = entry.getIntValue();
            if (count > bestCount || (count == bestCount && entry.getKey().compareTo(best) < 0)) {
                best = entry.getKey();
                bestCount = count;
            }
        }
        return best;
    }

    private static byte[] blockEntityPayload(BlockEntitySample sample) {
        try {
            byte[] payload = BlockEntitySample.encode(sample);
            return payload.length > ViewStreamLimits.MAX_BLOCK_ENTITY_PAYLOAD_BYTES ? null : payload;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] body(Brick brick) {
        try {
            return BrickCodec.body(brick);
        } catch (ViewStreamProtocolException e) {
            throw new IllegalStateException("brick " + brick.brickIndex() + " does not encode", e);
        }
    }
}
