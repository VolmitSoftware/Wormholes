package art.arcane.optics.plate;

import art.arcane.optics.math.BlockBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.Sample;
import art.arcane.optics.fidelity.BlockEntitySample;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

public final class PlateGrid<B> {
    public static final int NO_CELL = 0;
    static final char ABSENT = 0;
    static final char BLOCK_ENTITY = Character.MAX_VALUE;
    static final int MAX_PALETTE = Character.MAX_VALUE - 1;
    private static final long PALETTE_ENTRY_BYTES = 8L + PlateCell.BYTES;
    private static final long BLOCK_ENTITY_ENTRY_BYTES = 24L;
    private static final PlateGrid<?> EMPTY = new PlateGrid<Object>(BlockBox.EMPTY, new char[0], List.of(),
        new Long2ObjectOpenHashMap<PlateCell<Object>>(), 0);

    private final BlockBox box;
    private final char[] cells;
    private final List<PlateCell<B>> palette;
    private final Long2ObjectOpenHashMap<PlateCell<B>> blockEntityCells;
    private final int cellCount;
    private final long bytes;

    private PlateGrid(BlockBox box, char[] cells, List<PlateCell<B>> palette,
                      Long2ObjectOpenHashMap<PlateCell<B>> blockEntityCells, int cellCount) {
        this.box = box;
        this.cells = cells;
        this.palette = palette;
        this.blockEntityCells = blockEntityCells;
        this.cellCount = cellCount;
        this.bytes = predictBytes(box) + (palette.size() * PALETTE_ENTRY_BYTES) + blockEntityBytes(blockEntityCells);
    }

    @SuppressWarnings("unchecked")
    public static <B> PlateGrid<B> empty() {
        return (PlateGrid<B>) EMPTY;
    }

    public static long predictBytes(BlockBox box) {
        return box.cells() * Character.BYTES;
    }

    public BlockBox box() {
        return box;
    }

    public int ref(int x, int y, int z) {
        int index = box.index(x, y, z);
        return index < 0 ? ABSENT : cells[index];
    }

    public static boolean blockEntityRef(int ref) {
        return ref == BLOCK_ENTITY;
    }

    public PlateCell<B> paletteCell(int ref) {
        return palette.get(ref - 1);
    }

    public PlateCell<B> cell(long localKey) {
        int index = box.index(CellKeys.unpackX(localKey), CellKeys.unpackY(localKey),
            CellKeys.unpackZ(localKey));
        if (index < 0) {
            return null;
        }
        char entry = cells[index];
        if (entry == ABSENT) {
            return null;
        }
        if (entry == BLOCK_ENTITY) {
            return blockEntityCells.get(localKey);
        }
        return palette.get(entry - 1);
    }

    public int cellCount() {
        return cellCount;
    }

    public int paletteSize() {
        return palette.size();
    }

    public long bytes() {
        return bytes;
    }

    public LongSet cellKeys() {
        LongOpenHashSet keys = new LongOpenHashSet(Math.max(16, cellCount));
        int index = 0;
        for (int dx = 0; dx < box.sizeX(); dx++) {
            for (int dy = 0; dy < box.sizeY(); dy++) {
                for (int dz = 0; dz < box.sizeZ(); dz++) {
                    if (cells[index++] != ABSENT) {
                        keys.add(CellKeys.pack(box.minX() + dx, box.minY() + dy, box.minZ() + dz));
                    }
                }
            }
        }
        return keys;
    }

    private static <B> long blockEntityBytes(Long2ObjectOpenHashMap<PlateCell<B>> blockEntityCells) {
        long total = 0L;
        for (PlateCell<B> cell : blockEntityCells.values()) {
            total += BLOCK_ENTITY_ENTRY_BYTES + cell.bytes();
        }
        return total;
    }

    public static final class Writer<B> {
        private final BlockBox box;
        private final char[] cells;
        private final ArrayList<PlateCell<B>> palette;
        private final Object2IntOpenHashMap<B> blockEntries;
        private final Object2IntOpenHashMap<B> backingEntries;
        private final Object2IntOpenHashMap<B> occludedEntries;
        private final Long2ObjectOpenHashMap<PlateCell<B>> blockEntityCells;
        private char airEntry;
        private int cellCount;

        public Writer(BlockBox box) {
            long size = box.cells();
            if (size > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("plate box of " + size + " cells exceeds the dense grid limit");
            }
            this.box = box;
            this.cells = new char[(int) size];
            this.palette = new ArrayList<PlateCell<B>>(64);
            this.blockEntries = new Object2IntOpenHashMap<B>(64);
            this.backingEntries = new Object2IntOpenHashMap<B>(16);
            this.occludedEntries = new Object2IntOpenHashMap<B>(16);
            this.blockEntityCells = new Long2ObjectOpenHashMap<PlateCell<B>>();
            this.airEntry = ABSENT;
        }

        Writer(PlateGrid<B> base) {
            this.box = base.box;
            this.cells = base.cells.clone();
            this.palette = new ArrayList<PlateCell<B>>(base.palette);
            this.blockEntries = new Object2IntOpenHashMap<B>(64);
            this.backingEntries = new Object2IntOpenHashMap<B>(16);
            this.occludedEntries = new Object2IntOpenHashMap<B>(16);
            this.blockEntityCells = new Long2ObjectOpenHashMap<PlateCell<B>>(base.blockEntityCells);
            this.airEntry = ABSENT;
            this.cellCount = base.cellCount;
            for (int i = 0; i < palette.size(); i++) {
                PlateCell<B> cell = palette.get(i);
                char entry = (char) (i + 1);
                switch (cell.kind()) {
                    case REMOTE_AIR -> airEntry = entry;
                    case BLOCK -> blockEntries.put(cell.sourceData(), entry);
                    case BACKING_BLOCK -> backingEntries.put(cell.sourceData(), entry);
                    default -> occludedEntries.put(cell.sourceData(), entry);
                }
            }
        }

        int index(int x, int y, int z) {
            return box.index(x, y, z);
        }

        boolean present(int index) {
            return cells[index] != ABSENT;
        }

        void copy(int fromIndex, long fromKey, int toIndex, long toKey) {
            char entry = cells[fromIndex];
            if (entry == BLOCK_ENTITY) {
                blockEntityCells.put(toKey, blockEntityCells.get(fromKey));
            }
            place(toIndex, toKey, entry);
        }

        void put(int index, long localKey, Sample.Kind kind, B source, B data, BlockEntitySample blockEntity) {
            if (blockEntity != null) {
                blockEntityCells.put(localKey, new PlateCell<B>(kind, source, data, blockEntity));
                place(index, localKey, BLOCK_ENTITY);
                return;
            }
            char entry = entry(kind, source, data);
            if (entry != ABSENT) {
                place(index, localKey, entry);
            }
        }

        void clear(int index, long localKey) {
            char previous = cells[index];
            if (previous == ABSENT) {
                return;
            }
            if (previous == BLOCK_ENTITY) {
                blockEntityCells.remove(localKey);
            }
            cells[index] = ABSENT;
            cellCount--;
        }

        void fillAir(B air) {
            Arrays.fill(cells, entry(Sample.Kind.REMOTE_AIR, air, air));
            cellCount = cells.length;
        }

        public PlateGrid<B> finish() {
            return new PlateGrid<B>(box, cells, List.copyOf(palette), blockEntityCells, cellCount);
        }

        private void place(int index, long localKey, char entry) {
            char previous = cells[index];
            if (previous == ABSENT) {
                cellCount++;
            } else if (previous == BLOCK_ENTITY && entry != BLOCK_ENTITY) {
                blockEntityCells.remove(localKey);
            }
            cells[index] = entry;
        }

        private char entry(Sample.Kind kind, B source, B data) {
            if (kind == Sample.Kind.REMOTE_AIR) {
                if (airEntry == ABSENT) {
                    airEntry = append(kind, source, data);
                }
                return airEntry;
            }
            Object2IntOpenHashMap<B> entries = switch (kind) {
                case BLOCK -> blockEntries;
                case BACKING_BLOCK -> backingEntries;
                default -> occludedEntries;
            };
            int known = entries.getInt(source);
            if (known != 0) {
                return (char) known;
            }
            char appended = append(kind, source, data);
            if (appended != ABSENT) {
                entries.put(source, appended);
            }
            return appended;
        }

        private char append(Sample.Kind kind, B source, B data) {
            if (palette.size() >= MAX_PALETTE) {
                return ABSENT;
            }
            palette.add(new PlateCell<B>(kind, source, data, null));
            return (char) palette.size();
        }
    }
}
