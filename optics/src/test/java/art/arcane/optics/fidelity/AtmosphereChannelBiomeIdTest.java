package art.arcane.optics.fidelity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import org.junit.jupiter.api.Test;

import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.view.ContentView;

final class AtmosphereChannelBiomeIdTest {
    private static final FidelityOptions FIDELITY = new FidelityOptions(0.005D, 0.6D, true, false, false, 8, 24.0D, 8);

    @Test
    void overridesUseTheDestinationViewBiomeIdOncePerQuartCell() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim<String, Destination>> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim<String, Destination>>();
        for (int qy = 0; qy < 4; qy++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    int x = qx << 2;
                    int y = 64 + (qy << 2);
                    int z = qz << 2;
                    claims.put(CellKeys.pack(x, y, z), new ProjectedBlockClaim<String, Destination>("stone", null,
                        CellKeys.pack(x + 100, y, z + 100), false));
                }
            }
        }
        Destination destination = new Destination();
        AtmosphereChannel<String, Destination> channel = new AtmosphereChannel<String, Destination>();

        Long2IntOpenHashMap overrides = channel.update(new AtmosphereChannel.Scan<String, Destination>(claims, destination, true), FIDELITY);

        assertEquals(64, overrides.size());
        for (long cell : overrides.keySet()) {
            assertEquals(Destination.BIOME, overrides.get(cell));
        }
        assertEquals(64, destination.lookups, "each destination quart cell is resolved once");
        assertNull(channel.update(new AtmosphereChannel.Scan<String, Destination>(claims, destination, false), FIDELITY),
            "an unchanged scan between refreshes resends nothing");
        assertEquals(64, destination.lookups);
    }

    private static final class Destination implements ContentView<String, String> {
        private static final int BIOME = 13;

        private int lookups;

        @Override
        public UUID worldId() {
            return null;
        }

        @Override
        public String material(int x, int y, int z) {
            return null;
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return "test:biome";
        }

        @Override
        public int biomeId(int x, int y, int z) {
            lookups++;
            return BIOME;
        }

        @Override
        public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return LIGHT_UNAVAILABLE;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }

        @Override
        public int getMinHeight() {
            return -64;
        }

        @Override
        public int getMaxHeight() {
            return 320;
        }

        @Override
        public String sampleBlockData(int x, int y, int z) {
            return null;
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
    }
}
