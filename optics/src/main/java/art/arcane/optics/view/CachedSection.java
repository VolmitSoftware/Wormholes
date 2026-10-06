package art.arcane.optics.view;


import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class CachedSection<B, M> {
    public static final int CELLS = 4096;
    static final int WORDS = 64;
    static final int NEIGHBOURS = 27;
    static final long[] OPEN = new long[WORDS];

    private static final long FIXED_BYTES = 160L;
    private static final long BITSET_BYTES = WORDS * 8L + 16L;
    private static final int PADDED = 20;

    private final int sectionX;
    private final int sectionY;
    private final int sectionZ;
    private final Object[] palette;
    private final Object[] materials;
    private final byte[] byteIndices;
    private final short[] shortIndices;
    private final long[] occluding;
    private final long bytes;
    private final int filledTick;
    private int lastAccessTick;
    private long[] surrounded;
    private long[] deep;
    private long[] unknown;

    private CachedSection(int sectionX, int sectionY, int sectionZ, Object[] palette, Object[] materials,
                          byte[] byteIndices, short[] shortIndices, long[] occluding, int filledTick) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.palette = palette;
        this.materials = materials;
        this.byteIndices = byteIndices;
        this.shortIndices = shortIndices;
        this.occluding = occluding;
        this.filledTick = filledTick;
        this.lastAccessTick = filledTick;
        long indexBytes = byteIndices != null ? CELLS + 16L : shortIndices != null ? CELLS * 2L + 16L : 0L;
        this.bytes = FIXED_BYTES + indexBytes + (BITSET_BYTES * 3L) + (palette.length * 16L) + 32L;
    }

    public static int index(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    public int sectionX() {
        return sectionX;
    }

    public int sectionY() {
        return sectionY;
    }

    public int sectionZ() {
        return sectionZ;
    }

    public boolean is(int x, int y, int z) {
        return sectionX == x && sectionY == y && sectionZ == z;
    }

    public long bytes() {
        return bytes;
    }

    public int filledTick() {
        return filledTick;
    }

    public int paletteSize() {
        return palette.length;
    }

    @SuppressWarnings("unchecked")
    public B data(int index) {
        return (B) palette[entry(index)];
    }

    @SuppressWarnings("unchecked")
    public M material(int index) {
        return (M) materials[entry(index)];
    }

    public boolean occluding(int index) {
        return (occluding[index >>> 6] & (1L << index)) != 0L;
    }

    public boolean hasBuriedDepth() {
        return surrounded != null;
    }

    public int buriedDepth(int index) {
        long[] surroundedBits = surrounded;
        if (surroundedBits == null) {
            return -1;
        }
        long bit = 1L << index;
        int word = index >>> 6;
        if (unknown != null && (unknown[word] & bit) != 0L) {
            return -1;
        }
        if ((surroundedBits[word] & bit) == 0L) {
            return 0;
        }
        return (deep[word] & bit) == 0L ? 1 : 2;
    }

    public boolean sameOpacity(CachedSection<B, M> other) {
        return Arrays.equals(occluding, other.occluding);
    }

    int lastAccessTick() {
        return lastAccessTick;
    }

    void touch(int tick) {
        lastAccessTick = tick;
    }

    boolean partialBuriedDepth() {
        return unknown != null;
    }

    void clearBuriedDepth() {
        surrounded = null;
        deep = null;
        unknown = null;
    }

    void adoptBuriedDepth(CachedSection<B, M> previous) {
        surrounded = previous.surrounded;
        deep = previous.deep;
        unknown = previous.unknown;
    }

    long[] occludingBits() {
        return occluding;
    }

    void computeBuriedDepth(long[][] neighbours) {
        int[][] padded = new int[PADDED][PADDED];
        int[][] paddedUnknown = new int[PADDED][PADDED];
        boolean anyUnknown = false;
        for (int py = 0; py < PADDED; py++) {
            int y = py - 2;
            int dy = y < 0 ? -1 : y > 15 ? 1 : 0;
            int localY = y & 15;
            for (int pz = 0; pz < PADDED; pz++) {
                int z = pz - 2;
                int dz = z < 0 ? -1 : z > 15 ? 1 : 0;
                int localZ = z & 15;
                long[] middle = neighbours[neighbour(0, dy, dz)];
                int bits = row(middle, localY, localZ) << 2;
                int missing = middle == null ? 0x3FFFC : 0;
                if (dy == 0 || dz == 0) {
                    long[] west = neighbours[neighbour(-1, dy, dz)];
                    long[] east = neighbours[neighbour(1, dy, dz)];
                    bits |= (row(west, localY, localZ) >>> 14) & 0x3;
                    bits |= (row(east, localY, localZ) & 0x3) << 18;
                    missing |= (west == null ? 0x3 : 0) | (east == null ? 0xC0000 : 0);
                }
                padded[py][pz] = bits;
                paddedUnknown[py][pz] = missing;
                anyUnknown |= missing != 0;
            }
        }
        int[][] enclosed = new int[PADDED][PADDED];
        int[][] enclosedUnknown = new int[PADDED][PADDED];
        for (int py = 1; py < PADDED - 1; py++) {
            for (int pz = 1; pz < PADDED - 1; pz++) {
                int open = padded[py][pz];
                enclosed[py][pz] = open & (open << 1) & (open >>> 1)
                    & padded[py - 1][pz] & padded[py + 1][pz] & padded[py][pz - 1] & padded[py][pz + 1];
                if (anyUnknown) {
                    int missing = paddedUnknown[py][pz];
                    enclosedUnknown[py][pz] = missing | (missing << 1) | (missing >>> 1)
                        | paddedUnknown[py - 1][pz] | paddedUnknown[py + 1][pz] | paddedUnknown[py][pz - 1] | paddedUnknown[py][pz + 1];
                }
            }
        }
        long[] surroundedBits = new long[WORDS];
        long[] deepBits = new long[WORDS];
        long[] unknownBits = anyUnknown ? new long[WORDS] : null;
        for (int y = 0; y < 16; y++) {
            int py = y + 2;
            for (int z = 0; z < 16; z++) {
                int pz = z + 2;
                int center = enclosed[py][pz];
                int twice = center & (center << 1) & (center >>> 1)
                    & enclosed[py - 1][pz] & enclosed[py + 1][pz] & enclosed[py][pz - 1] & enclosed[py][pz + 1];
                int word = (y << 2) | (z >>> 2);
                int shift = (z & 3) << 4;
                surroundedBits[word] |= ((long) ((center >>> 2) & 0xFFFF)) << shift;
                deepBits[word] |= ((long) ((twice >>> 2) & 0xFFFF)) << shift;
                if (unknownBits != null) {
                    int centerUnknown = enclosedUnknown[py][pz];
                    int twiceUnknown = centerUnknown | (centerUnknown << 1) | (centerUnknown >>> 1)
                        | enclosedUnknown[py - 1][pz] | enclosedUnknown[py + 1][pz]
                        | enclosedUnknown[py][pz - 1] | enclosedUnknown[py][pz + 1];
                    int undetermined = centerUnknown | (center & twiceUnknown);
                    unknownBits[word] |= ((long) ((undetermined >>> 2) & 0xFFFF)) << shift;
                }
            }
        }
        deep = deepBits;
        unknown = unknownBits;
        surrounded = surroundedBits;
    }

    static int neighbour(int dx, int dy, int dz) {
        return ((dx + 1) * 9) + ((dy + 1) * 3) + (dz + 1);
    }

    static boolean haloNeighbour(int dx, int dy, int dz) {
        int offsets = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        return offsets == 1 || offsets == 2;
    }

    private static int row(long[] bits, int localY, int localZ) {
        if (bits == null) {
            return 0;
        }
        return (int) ((bits[(localY << 2) | (localZ >>> 2)] >>> ((localZ & 3) << 4)) & 0xFFFFL);
    }

    private int entry(int index) {
        if (byteIndices != null) {
            return byteIndices[index] & 0xFF;
        }
        if (shortIndices != null) {
            return shortIndices[index];
        }
        return 0;
    }

    public static final class Builder<B, M> {
        private static final int LINEAR_PALETTE_LIMIT = 16;

        private final BlockStates<B, M> blocks;
        private final short[] indices;
        private final Map<Object, Integer> lookup;
        private Object[] palette;
        private Object[] materials;
        private boolean[] occludingEntries;
        private Object[] keys;
        private int size;
        private int lastEntry;

        public Builder(BlockStates<B, M> blocks) {
            this.blocks = blocks;
            this.indices = new short[CELLS];
            this.lookup = new HashMap<Object, Integer>(64);
            this.palette = new Object[16];
            this.materials = new Object[16];
            this.occludingEntries = new boolean[16];
            this.keys = new Object[16];
            this.size = 0;
            this.lastEntry = -1;
        }

        public void reset() {
            Arrays.fill(palette, 0, size, null);
            Arrays.fill(materials, 0, size, null);
            Arrays.fill(keys, 0, size, null);
            lookup.clear();
            size = 0;
            lastEntry = -1;
        }

        public void set(int index, B data, M material) {
            indices[index] = (short) entry(data, material);
        }

        public CachedSection<B, M> build(int sectionX, int sectionY, int sectionZ, int tick) {
            long[] occluding = new long[WORDS];
            for (int index = 0; index < CELLS; index++) {
                if (occludingEntries[indices[index]]) {
                    occluding[index >>> 6] |= 1L << index;
                }
            }
            byte[] byteIndices = null;
            short[] shortIndices = null;
            if (size > 256) {
                shortIndices = Arrays.copyOf(indices, CELLS);
            } else if (size > 1) {
                byteIndices = new byte[CELLS];
                for (int index = 0; index < CELLS; index++) {
                    byteIndices[index] = (byte) indices[index];
                }
            }
            return new CachedSection<B, M>(sectionX, sectionY, sectionZ, Arrays.copyOf(palette, size),
                Arrays.copyOf(materials, size), byteIndices, shortIndices, occluding, tick);
        }

        private int entry(B data, M material) {
            Object key = blocks.isAir(material) ? material : data;
            if (lastEntry >= 0 && keys[lastEntry].equals(key)) {
                return lastEntry;
            }
            int found = find(key);
            if (found < 0) {
                found = add(key, data, material);
            }
            lastEntry = found;
            return found;
        }

        private int find(Object key) {
            if (size <= LINEAR_PALETTE_LIMIT) {
                for (int entry = 0; entry < size; entry++) {
                    if (keys[entry].equals(key)) {
                        return entry;
                    }
                }
                return -1;
            }
            Integer entry = lookup.get(key);
            return entry == null ? -1 : entry.intValue();
        }

        private int add(Object key, B data, M material) {
            if (size == palette.length) {
                int grown = size * 2;
                palette = Arrays.copyOf(palette, grown);
                materials = Arrays.copyOf(materials, grown);
                occludingEntries = Arrays.copyOf(occludingEntries, grown);
                keys = Arrays.copyOf(keys, grown);
            }
            int entry = size++;
            palette[entry] = data;
            materials[entry] = material;
            occludingEntries[entry] = blocks.isOccluding(material);
            keys[entry] = key;
            if (size == LINEAR_PALETTE_LIMIT + 1) {
                for (int existing = 0; existing < size; existing++) {
                    lookup.put(keys[existing], Integer.valueOf(existing));
                }
            } else if (size > LINEAR_PALETTE_LIMIT + 1) {
                lookup.put(key, Integer.valueOf(entry));
            }
            return entry;
        }
    }
}
