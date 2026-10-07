package art.arcane.optics.stream;

import java.util.Locale;
import java.util.UUID;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.state.StateProperties;
import art.arcane.optics.view.BlockStates;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.view.ContentView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

final class SessionWorld implements ContentView<String, String> {
    static final String AIR = "minecraft:air";
    static final String OCCLUDED = "#occluded";
    static final BlockStates<String, String> BLOCKS = new Blocks();
    private static final String[] ORES = {"minecraft:coal_ore", "minecraft:iron_ore", "minecraft:diamond_ore"};

    private final UUID id;
    private final Long2ObjectOpenHashMap<String> overrides;

    SessionWorld(long seed) {
        this.id = new UUID(seed, ~seed);
        this.overrides = new Long2ObjectOpenHashMap<String>();
    }

    void set(int x, int y, int z, String state) {
        overrides.put(CellKeys.pack(x, y, z), state);
    }

    static String materialOf(String block) {
        int bracket = block.indexOf('[');
        String name = bracket < 0 ? block : block.substring(0, bracket);
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
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
        return null;
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
        return 0;
    }

    @Override
    public int getMaxHeight() {
        return 128;
    }

    @Override
    public String sampleBlockData(int x, int y, int z) {
        String override = overrides.get(CellKeys.pack(x, y, z));
        if (override != null) {
            return override;
        }
        int height = 64 + Math.floorMod(x * 7 + z * 13, 5) - 2;
        if (y > height) {
            if (Math.floorMod(x * 31 + z * 17, 11) == 0 && y <= height + 3) {
                return "minecraft:oak_log[axis=y]";
            }
            return AIR;
        }
        if (y == height) {
            return "minecraft:grass_block[snowy=false]";
        }
        if (y > height - 3) {
            return "minecraft:dirt";
        }
        int hash = Math.floorMod(x * 73856093 ^ y * 19349663 ^ z * 83492791, 23);
        return hash < ORES.length ? ORES[hash] : "minecraft:stone";
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

    private static final class Blocks implements BlockStates<String, String> {
        @Override
        public String air() {
            return AIR;
        }

        @Override
        public String occluded() {
            return OCCLUDED;
        }

        @Override
        public boolean isOccluded(String block) {
            return OCCLUDED.equals(block);
        }

        @Override
        public String material(String block) {
            return materialOf(block);
        }

        @Override
        public String materialName(String material) {
            return material == null ? null : material.toUpperCase(Locale.ROOT);
        }

        @Override
        public boolean blockEntityCandidate(String material) {
            return false;
        }

        @Override
        public boolean isAir(String material) {
            return material == null || material.endsWith("air");
        }

        @Override
        public boolean isOccluding(String material) {
            return !isAir(material) && !material.contains("glass") && !material.contains("leaves");
        }

        @Override
        public boolean occludes(String block) {
            return block != null && isOccluding(material(block));
        }

        @Override
        public boolean requiresTransform(String block) {
            return false;
        }

        @Override
        public String transform(String block, AxisPermutation permutation) {
            return block;
        }

        @Override
        public StateProperties properties(String block) {
            return StateProperties.EMPTY;
        }

        @Override
        public String withProperties(String block, StateProperties properties) {
            return block;
        }
    }
}
