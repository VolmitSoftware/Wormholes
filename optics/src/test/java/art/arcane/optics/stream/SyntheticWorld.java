package art.arcane.optics.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.view.ContentView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

final class SyntheticWorld implements ContentView<String, String> {
    static final int MIN_Y = 0;
    static final int MAX_Y = 128;
    static final String AIR = "minecraft:air";
    private static final String[] ORES = {
        "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore", "minecraft:gold_ore", "minecraft:redstone_ore[lit=false]",
        "minecraft:lapis_ore", "minecraft:diamond_ore", "minecraft:emerald_ore"
    };
    private static final String[] STONES = {"minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
        "minecraft:gravel", "minecraft:dirt"};
    private static final String[] FLOWERS = {"minecraft:poppy", "minecraft:dandelion", "minecraft:azure_bluet", "minecraft:oxeye_daisy",
        "minecraft:cornflower", "minecraft:blue_orchid"};
    private static final String[] FACINGS = {"north", "east", "south", "west"};
    private static final Set<String> FLOWER_SET = Set.of("poppy", "dandelion", "azure_bluet", "oxeye_daisy", "cornflower", "blue_orchid",
        "short_grass", "tall_grass");

    private final long seed;
    private final UUID id;
    private final List<String> states;
    private final Object2IntOpenHashMap<String> ids;
    private final Long2ObjectOpenHashMap<char[]> columns;
    private final LongOpenHashSet edits;
    private final Long2ObjectOpenHashMap<String> overrides;
    private final Long2ObjectOpenHashMap<BlockEntitySample> blockEntities;

    SyntheticWorld(long seed) {
        this.seed = seed;
        this.id = new UUID(seed, ~seed);
        this.states = new ArrayList<String>(256);
        this.ids = new Object2IntOpenHashMap<String>(256);
        this.ids.defaultReturnValue(-1);
        this.columns = new Long2ObjectOpenHashMap<char[]>();
        this.edits = new LongOpenHashSet();
        this.overrides = new Long2ObjectOpenHashMap<String>();
        this.blockEntities = new Long2ObjectOpenHashMap<BlockEntitySample>();
        id(AIR);
    }

    static String materialOf(String block) {
        if (block == null) {
            return null;
        }
        int bracket = block.indexOf('[');
        String name = bracket < 0 ? block : block.substring(0, bracket);
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    long seed() {
        return seed;
    }

    void set(int x, int y, int z, String state) {
        long key = cellKey(x, y, z);
        overrides.put(key, state);
        edits.add(key);
    }

    void setBlockEntity(int x, int y, int z, String state, BlockEntitySample sample) {
        set(x, y, z, state);
        blockEntities.put(cellKey(x, y, z), sample);
    }

    int surface(int x, int z) {
        for (int y = MAX_Y - 1; y > MIN_Y; y--) {
            String block = sampleBlockData(x, y, z);
            String material = materialOf(block);
            if (material.equals("air")) {
                continue;
            }
            if (material.equals("water") || material.contains("leaves") || material.contains("log")) {
                return Integer.MIN_VALUE;
            }
            if (material.contains("grass") && !material.equals("grass_block") || material.contains("fern") || FLOWER_SET.contains(material)) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    @Override
    public UUID worldId() {
        return id;
    }

    @Override
    public String sampleBiome(int x, int y, int z) {
        return "minecraft:plains";
    }

    @Override
    public int biomeId(int x, int y, int z) {
        return -1;
    }

    @Override
    public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
        return blockEntities.get(cellKey(x, y, z));
    }

    @Override
    public int getLight(int x, int y, int z) {
        return 15;
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public String material(int x, int y, int z) {
        return materialOf(sampleBlockData(x, y, z));
    }

    @Override
    public int getMinHeight() {
        return MIN_Y;
    }

    @Override
    public int getMaxHeight() {
        return MAX_Y;
    }

    @Override
    public String sampleBlockData(int x, int y, int z) {
        if (y >= MAX_Y) {
            return AIR;
        }
        if (y < MIN_Y) {
            return "minecraft:deepslate";
        }
        if (!overrides.isEmpty()) {
            String override = overrides.get(cellKey(x, y, z));
            if (override != null) {
                return override;
            }
        }
        long columnKey = (((long) x) << 32) | (z & 0xFFFFFFFFL);
        char[] column = columns.get(columnKey);
        if (column == null) {
            column = generateColumn(x, z);
            columns.put(columnKey, column);
        }
        return states.get(column[y - MIN_Y]);
    }

    @Override
    public boolean isChunkReady(int x, int z) {
        return true;
    }

    @Override
    public void requestChunk(int x, int z) {
    }

    @Override
    public long getRevision() {
        return 0L;
    }

    private char[] generateColumn(int x, int z) {
        char[] column = new char[MAX_Y - MIN_Y];
        int height = height(x, z);
        for (int y = MIN_Y; y < MAX_Y; y++) {
            column[y - MIN_Y] = (char) id(terrain(x, y, z, height));
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                applyTree(column, x, z, x + dx, z + dz);
            }
        }
        applyRuin(column, x, z);
        return column;
    }

    private String terrain(int x, int y, int z, int height) {
        if (y > height) {
            if (y <= 61 && height < 61) {
                return "minecraft:water[level=0]";
            }
            if (y == height + 1) {
                return decoration(x, z, height);
            }
            return AIR;
        }
        if (y < height - 3 && cave(x, y, z)) {
            return AIR;
        }
        if (y == height) {
            if (height <= 61) {
                return "minecraft:sand";
            }
            double patch = noise2(x * 0.11D, z * 0.11D, 7);
            if (patch > 0.86D) {
                return "minecraft:stone";
            }
            if (patch < 0.09D) {
                return "minecraft:coarse_dirt";
            }
            return "minecraft:grass_block[snowy=false]";
        }
        if (y >= height - 3) {
            return "minecraft:dirt";
        }
        String ore = ore(x, y, z);
        if (ore != null) {
            return ore;
        }
        double blob = noise3(x * 0.07D, y * 0.09D, z * 0.07D, 3);
        if (blob > 0.81D) {
            return STONES[1 + (int) (hash(x >> 4, y >> 4, z >> 4, 11) % 4L)];
        }
        if (blob < 0.07D) {
            return STONES[5 + (int) (hash(x >> 2, y >> 2, z >> 2, 13) % 2L)];
        }
        return "minecraft:stone";
    }

    private String decoration(int x, int z, int height) {
        if (height <= 61) {
            return AIR;
        }
        long roll = hash(x, 0, z, 21) % 1000L;
        if (roll < 80) {
            return "minecraft:short_grass";
        }
        if (roll < 95) {
            return "minecraft:tall_grass[half=lower]";
        }
        if (roll < 110) {
            return FLOWERS[(int) (hash(x, 1, z, 22) % FLOWERS.length)];
        }
        if (roll < 116) {
            return "minecraft:fern";
        }
        return AIR;
    }

    private boolean cave(int x, int y, int z) {
        return noise3(x * 0.07D, y * 0.11D, z * 0.07D, 5) > 0.76D;
    }

    private String ore(int x, int y, int z) {
        int cx = x >> 3;
        int cy = y >> 3;
        int cz = z >> 3;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    long roll = hash(cx + ox, cy + oy, cz + oz, 31);
                    if (roll % 100L >= 10L) {
                        continue;
                    }
                    int centerX = ((cx + ox) << 3) + (int) ((roll >>> 8) & 7L);
                    int centerY = ((cy + oy) << 3) + (int) ((roll >>> 11) & 7L);
                    int centerZ = ((cz + oz) << 3) + (int) ((roll >>> 14) & 7L);
                    int radius = 1 + (int) ((roll >>> 17) & 1L);
                    int dx = x - centerX;
                    int dy = y - centerY;
                    int dz = z - centerZ;
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                        int type = (int) ((roll >>> 20) % ORES.length);
                        if (type >= 6 && centerY > 24) {
                            type = type % 3;
                        }
                        return ORES[type];
                    }
                }
            }
        }
        return null;
    }

    private void applyTree(char[] column, int x, int z, int anchorX, int anchorZ) {
        long roll = hash(anchorX, 0, anchorZ, 41);
        if (roll % 1000L >= 18L) {
            return;
        }
        int height = height(anchorX, anchorZ);
        if (height <= 62) {
            return;
        }
        int trunk = 4 + (int) ((roll >>> 12) % 3L);
        boolean birch = ((roll >>> 20) & 3L) == 0L;
        String log = birch ? "minecraft:birch_log[axis=y]" : "minecraft:oak_log[axis=y]";
        String leaves = birch ? "minecraft:birch_leaves" : "minecraft:oak_leaves";
        int dx = x - anchorX;
        int dz = z - anchorZ;
        int dist = Math.abs(dx) + Math.abs(dz);
        int top = height + trunk;
        for (int y = height + 1; y <= top + 1 && y < MAX_Y; y++) {
            int fromTop = top - y;
            boolean inLeaves;
            if (fromTop <= 0) {
                inLeaves = dist <= 1 && !(dx != 0 && dz != 0);
            } else if (fromTop <= 2) {
                inLeaves = Math.abs(dx) <= 2 && Math.abs(dz) <= 2 && !(Math.abs(dx) == 2 && Math.abs(dz) == 2 && (roll >>> (24 + fromTop)) % 2L == 0L);
            } else {
                inLeaves = false;
            }
            if (dx == 0 && dz == 0 && y <= top) {
                column[y - MIN_Y] = (char) id(log);
            } else if (inLeaves && column[y - MIN_Y] == 0) {
                int distance = Math.max(1, Math.min(7, dist + Math.max(0, 1 - fromTop)));
                column[y - MIN_Y] = (char) id(leaves + "[distance=" + distance + ",persistent=false,waterlogged=false]");
            }
        }
    }

    private void applyRuin(char[] column, int x, int z) {
        int anchorX = Math.floorDiv(x, 24) * 24 + 8;
        int anchorZ = Math.floorDiv(z, 24) * 24 + 8;
        long roll = hash(anchorX, 3, anchorZ, 51);
        if (roll % 100L >= 35L) {
            return;
        }
        int dx = x - anchorX;
        int dz = z - anchorZ;
        if (Math.abs(dx) > 3 || Math.abs(dz) > 3) {
            return;
        }
        int base = height(anchorX, anchorZ);
        if (base <= 61) {
            return;
        }
        boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 3;
        int wallHeight = 2 + (int) ((hash(x, 5, z, 52) >>> 3) % 3L);
        for (int y = base + 1; y <= base + 4 && y < MAX_Y; y++) {
            String state = null;
            if (wall && y <= base + wallHeight) {
                long pick = hash(x, y, z, 53) % 10L;
                if (pick < 5) {
                    state = "minecraft:cobblestone";
                } else if (pick < 8) {
                    state = "minecraft:mossy_cobblestone";
                } else if (pick < 9) {
                    state = "minecraft:cobblestone_stairs[facing=" + FACINGS[(int) ((hash(x, y, z, 54)) % 4L)]
                        + ",half=bottom,shape=straight,waterlogged=false]";
                } else {
                    state = "minecraft:cobblestone_slab[type=bottom,waterlogged=false]";
                }
            } else if (!wall && y == base + 1 && hash(x, y, z, 55) % 7L == 0L) {
                state = hash(x, y, z, 56) % 2L == 0L ? "minecraft:oak_planks" : "minecraft:stone_bricks";
            } else if (!wall && y == base + 2 && hash(x, y, z, 57) % 11L == 0L) {
                state = "minecraft:torch";
            }
            if (state != null) {
                column[y - MIN_Y] = (char) id(state);
            }
        }
        if (Math.abs(dx) <= 3 && Math.abs(dz) <= 3) {
            column[base - MIN_Y] = (char) id(wall ? "minecraft:cobblestone" : "minecraft:stone_bricks");
        }
    }

    int height(int x, int z) {
        double h = 64.0D
            + 11.0D * (noise2(x / 43.0D, z / 43.0D, 1) - 0.5D) * 2.0D
            + 5.0D * (noise2(x / 15.0D, z / 15.0D, 2) - 0.5D) * 2.0D
            + 2.0D * (noise2(x / 5.5D, z / 5.5D, 3) - 0.5D) * 2.0D;
        return Math.max(MIN_Y + 8, Math.min(MAX_Y - 12, (int) Math.floor(h)));
    }

    private int id(String state) {
        int known = ids.getInt(state);
        if (known >= 0) {
            return known;
        }
        int id = states.size();
        states.add(state);
        ids.put(state, id);
        return id;
    }

    private double noise2(double x, double z, int salt) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = fade(x - x0);
        double fz = fade(z - z0);
        double a = unit(hash(x0, salt, z0, 101));
        double b = unit(hash(x0 + 1, salt, z0, 101));
        double c = unit(hash(x0, salt, z0 + 1, 101));
        double d = unit(hash(x0 + 1, salt, z0 + 1, 101));
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz);
    }

    private double noise3(double x, double y, double z, int salt) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = fade(x - x0);
        double fy = fade(y - y0);
        double fz = fade(z - z0);
        double c000 = unit(hash(x0, y0, z0, salt + 200));
        double c100 = unit(hash(x0 + 1, y0, z0, salt + 200));
        double c010 = unit(hash(x0, y0 + 1, z0, salt + 200));
        double c110 = unit(hash(x0 + 1, y0 + 1, z0, salt + 200));
        double c001 = unit(hash(x0, y0, z0 + 1, salt + 200));
        double c101 = unit(hash(x0 + 1, y0, z0 + 1, salt + 200));
        double c011 = unit(hash(x0, y0 + 1, z0 + 1, salt + 200));
        double c111 = unit(hash(x0 + 1, y0 + 1, z0 + 1, salt + 200));
        double x00 = lerp(c000, c100, fx);
        double x10 = lerp(c010, c110, fx);
        double x01 = lerp(c001, c101, fx);
        double x11 = lerp(c011, c111, fx);
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz);
    }

    private long hash(int x, int y, int z, int salt) {
        long h = seed ^ (salt * 0x9E3779B97F4A7C15L);
        h ^= x * 0xBF58476D1CE4E5B9L;
        h = Long.rotateLeft(h, 21) * 0x94D049BB133111EBL;
        h ^= y * 0xD6E8FEB86659FD93L;
        h = Long.rotateLeft(h, 17) * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return h & Long.MAX_VALUE;
    }

    private static double unit(long hash) {
        return (hash & 0xFFFFFFL) / (double) 0x1000000L;
    }

    private static double fade(double t) {
        return t * t * (3.0D - 2.0D * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static long cellKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
    }
}
