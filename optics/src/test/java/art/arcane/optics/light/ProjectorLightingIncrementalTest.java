package art.arcane.optics.light;

import art.arcane.optics.spi.OpticsMetrics;
import art.arcane.optics.view.ContentView;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import art.arcane.optics.fidelity.BlockEntitySample;

import org.junit.jupiter.api.Test;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.claim.ProjectionClaimSet;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.view.WorldChangeTracker;


public final class ProjectorLightingIncrementalTest {
    private static final Object OBSERVER = new Object();
    private static final String DATA = "stone";
    private int sectionBudget = Integer.MAX_VALUE;

    @Test
    public void sparseEditsMatchFreshRebuildAcrossDenseSectionsAndChunks() {
        MutableLightView local = new MutableLightView(3, 4);
        MutableLightView source = new MutableLightView(12, 7);
        MutableLightView unavailable = new MutableLightView(-1, -1);
        CountingClaims claims = new CountingClaims();
        for (int nibble = 0; nibble < 4096; nibble++) {
            claims.put(keyForNibble(-1, -4, 2, nibble), sourceClaim(source));
        }
        claims.put(CellKeys.pack(1, 64, 1), fullBrightClaim());
        claims.put(CellKeys.pack(35, 100, -3), sourceClaim(source));
        claims.put(CellKeys.pack(35, 101, -3), sourceClaim(unavailable));
        claims.put(CellKeys.pack(0, 320, 0), fullBrightClaim());
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting<Object, String, MutableLightView> lighting = lighting(local, client);
        lighting.apply(OBSERVER, local, claims, null);
        assertMatchesRebuild(client, local, claims, true);
        int scans = claims.entryScans;

        for (int pass = 0; pass < 12; pass++) {
            LongOpenHashSet dirty = new LongOpenHashSet();
            for (int offset = 0; offset < 80; offset++) {
                int nibble = (pass * 113 + offset * 37) & 4095;
                long key = keyForNibble(-1, -4, 2, nibble);
                dirty.add(key);
                switch ((offset + pass) % 4) {
                    case 0 -> claims.remove(key);
                    case 1 -> claims.put(key, fullBrightClaim());
                    case 2 -> claims.put(key, sourceClaim(source));
                    default -> claims.put(key, new ProjectedBlockClaim<String, MutableLightView>(DATA, null,
                        ProjectedBlockClaim.NO_REMOTE_KEY, true));
                }
            }
            lighting.apply(OBSERVER, local, claims, dirty);
            assertEquals(scans, claims.entryScans);
            assertMatchesRebuild(client, local, claims, true);
            scans = claims.entryScans;
        }

        long removedSection = CellKeys.pack(1, 64, 1);
        claims.remove(removedSection);
        lighting.apply(OBSERVER, local, claims, LongSet.of(removedSection));
        assertMatchesRebuild(client, local, claims, true);
    }

    @Test
    public void dirtyUpdatesReadCurrentSourceLightWithoutAnotherGlobalScan() {
        MutableLightView local = new MutableLightView(3, 4);
        MutableLightView source = new MutableLightView(10, 6);
        CountingClaims claims = new CountingClaims();
        long first = CellKeys.pack(1, 64, 1);
        long second = CellKeys.pack(2, 64, 1);
        claims.put(first, sourceClaim(source));
        claims.put(second, sourceClaim(source));
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting<Object, String, MutableLightView> lighting = lighting(local, client);
        lighting.apply(OBSERVER, local, claims, LongSet.of(first, second));
        assertEquals(1, claims.entryScans);

        source.sky = 7;
        source.block = 11;
        claims.put(first, fullBrightClaim());
        lighting.apply(OBSERVER, local, claims, LongSet.of(first));

        assertEquals(1, claims.entryScans);
        SectionLight light = client.get(CellKeys.pack(0, 4, 0));
        assertEquals(15, nibble(light.sky, 17));
        assertEquals(7, nibble(light.sky, 18));
        assertEquals(11, nibble(light.block, 18));
        lighting.apply(OBSERVER, local, claims, LongSet.of());
        assertEquals(1, claims.entryScans);
        assertFalse(lighting.hasPendingUpdates());
    }

    @Test
    public void chunkDiscardRetainsUnchangedOverlayCellsAndRevertResetsTheIndex() {
        MutableLightView local = new MutableLightView(3, 4);
        MutableLightView source = new MutableLightView(8, 7);
        CountingClaims claims = new CountingClaims();
        long first = CellKeys.pack(1, 64, 1);
        long second = CellKeys.pack(2, 64, 1);
        claims.put(first, fullBrightClaim());
        claims.put(second, sourceClaim(source));
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting<Object, String, MutableLightView> lighting = lighting(local, client);
        lighting.apply(OBSERVER, local, claims, null);
        lighting.discardChunk(0, 0);
        client.clear();
        claims.put(first, sourceClaim(source));

        lighting.apply(OBSERVER, local, claims, LongSet.of(first));

        assertEquals(1, claims.entryScans);
        assertMatchesRebuild(client, local, claims, true);
        lighting.revert(OBSERVER, local);
        assertTrue(lighting.isIdle());
        int scans = claims.entryScans;
        lighting.apply(OBSERVER, local, claims, LongSet.of(first));
        assertEquals(scans + 1, claims.entryScans);
        assertMatchesRebuild(client, local, claims, true);
        lighting.discard();
        client.clear();
        scans = claims.entryScans;
        lighting.apply(OBSERVER, local, claims, LongSet.of(second));
        assertEquals(scans + 1, claims.entryScans);
        assertMatchesRebuild(client, local, claims, true);
    }

    @Test
    public void sourcePolicyViewIdentityAndWorldBoundsInvalidateMembership() {
        MutableLightView local = new MutableLightView(3, 4);
        MutableLightView source = new MutableLightView(8, 7);
        CountingClaims claims = new CountingClaims();
        long sourceKey = CellKeys.pack(1, 64, 1);
        long brightKey = CellKeys.pack(2, 64, 1);
        long highKey = CellKeys.pack(1, 96, 1);
        claims.put(sourceKey, sourceClaim(source));
        claims.put(brightKey, fullBrightClaim());
        claims.put(highKey, fullBrightClaim());
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting<Object, String, MutableLightView> lighting = lighting(local, client);
        lighting.apply(OBSERVER, local, claims, claims.keySet(), true);
        assertEquals(1, claims.entryScans);

        lighting.apply(OBSERVER, local, claims, claims.keySet(), false);
        assertEquals(2, claims.entryScans);
        assertMatchesRebuild(client, local, claims, false);
        int scans = claims.entryScans;
        lighting.apply(OBSERVER, local, claims, claims.keySet(), true);
        assertEquals(scans + 1, claims.entryScans);
        assertMatchesRebuild(client, local, claims, true);

        MutableLightView replacement = new MutableLightView(3, 4);
        scans = claims.entryScans;
        lighting.apply(OBSERVER, replacement, claims, claims.keySet(), true);
        assertEquals(scans + 1, claims.entryScans);
        replacement.maxHeight = 96;
        scans = claims.entryScans;
        lighting.apply(OBSERVER, replacement, claims, claims.keySet(), true);
        assertEquals(scans + 1, claims.entryScans);
        client.remove(CellKeys.pack(0, 6, 0));
        assertMatchesRebuild(client, replacement, claims, true);
    }

    @Test
    public void pendingBudgetUsesLiveSourcesWithoutRescanningTheClaimMap() {
        sectionBudget = 1;
        MutableLightView local = new MutableLightView(3, 4);
        MutableLightView source = new MutableLightView(8, 7);
        CountingClaims claims = new CountingClaims();
        for (int section = 4; section < 7; section++) {
            claims.put(CellKeys.pack(1, section << 4, 1), sourceClaim(source));
        }
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting<Object, String, MutableLightView> lighting = lighting(local, client);
        lighting.apply(OBSERVER, local, claims, claims.keySet());
        assertEquals(1, client.size());
        assertEquals(1, claims.entryScans);
        assertTrue(lighting.hasPendingUpdates());
        client.clear();
        source.sky = 6;
        source.block = 12;

        lighting.apply(OBSERVER, local, claims, LongSet.of());
        lighting.apply(OBSERVER, local, claims, LongSet.of());

        assertEquals(1, claims.entryScans);
        assertEquals(2, client.size());
        assertFalse(lighting.hasPendingUpdates());
        for (SectionLight light : client.values()) {
            assertEquals(6, nibble(light.sky, 17));
            assertEquals(12, nibble(light.block, 17));
        }
    }

    @Test
    public void overlappingPortalWinnerAndReleaseUseArbitratedLightSources() {
        MutableLightView local = new MutableLightView(3, 4);
        MutableLightView source = new MutableLightView(8, 7);
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting<Object, String, MutableLightView> lighting = lighting(local, client);
        ProjectionClaimSet<ProjectedBlockClaim<String, MutableLightView>> claims = new ProjectionClaimSet<ProjectedBlockClaim<String, MutableLightView>>();
        Long2ObjectOpenHashMap<ProjectedBlockClaim<String, MutableLightView>> sourceClaims = new Long2ObjectOpenHashMap<ProjectedBlockClaim<String, MutableLightView>>();
        Long2ObjectOpenHashMap<ProjectedBlockClaim<String, MutableLightView>> brightClaims = new Long2ObjectOpenHashMap<ProjectedBlockClaim<String, MutableLightView>>();
        long key = CellKeys.pack(1, 64, 1);
        sourceClaims.put(key, sourceClaim(source));
        brightClaims.put(key, fullBrightClaim());
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        ProjectionClaimSet.ProjectionClaimSetResult result = claims.replacePortalClaims(first, "first", 4.0D, sourceClaims);
        lighting.apply(OBSERVER, local, claims.getWinningClaims(), result.getDirtyLightingKeys());
        assertMatchesRebuild(client, local, claims.getWinningClaims(), true);

        result = claims.replacePortalClaims(second, "second", 1.0D, brightClaims);
        lighting.apply(OBSERVER, local, claims.getWinningClaims(), result.getDirtyLightingKeys());
        assertEquals(15, nibble(client.get(CellKeys.pack(0, 4, 0)).block, 17));
        assertMatchesRebuild(client, local, claims.getWinningClaims(), true);

        result = claims.releasePortal(second);
        lighting.apply(OBSERVER, local, claims.getWinningClaims(), result.getDirtyLightingKeys());
        assertEquals(7, nibble(client.get(CellKeys.pack(0, 4, 0)).block, 17));
        assertMatchesRebuild(client, local, claims.getWinningClaims(), true);
        result = claims.releasePortal(first);
        lighting.apply(OBSERVER, local, claims.getWinningClaims(), result.getDirtyLightingKeys());
        assertMatchesRebuild(client, local, claims.getWinningClaims(), true);
        assertTrue(lighting.isIdle());
    }

    private ProjectorLighting<Object, String, MutableLightView> lighting(MutableLightView local, Map<Long, SectionLight> client) {
        return new ProjectorLighting<>(new ProjectorLighting.Host<>() {
            @Override
            public boolean isOnline(Object observer) {
                return true;
            }

            @Override
            public boolean isChunkSent(Object observer, int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public void send(Object observer, ProjectorLighting.ChunkLight light) {
                receive(client, local, light);
            }

            @Override
            public int sectionBudget() {
                return sectionBudget;
            }

            @Override
            public WorldChangeTracker tracker() {
                return null;
            }
        }, OpticsMetrics.none());
    }

    private static void receive(Map<Long, SectionLight> client, MutableLightView local,
                                ProjectorLighting.ChunkLight data) {
        int arrayIndex = 0;
        for (int maskIndex = data.skyMask().nextSetBit(0); maskIndex >= 0;
             maskIndex = data.skyMask().nextSetBit(maskIndex + 1)) {
            int section = maskIndex + (local.minHeight >> 4) - 1;
            client.put(CellKeys.pack(data.chunkX(), section, data.chunkZ()),
                new SectionLight(data.skyArrays()[arrayIndex], data.blockArrays()[arrayIndex]));
            arrayIndex++;
        }
    }

    private void assertMatchesRebuild(Map<Long, SectionLight> client, MutableLightView local,
                                            Long2ObjectMap<ProjectedBlockClaim<String, MutableLightView>> claims, boolean sourceLighting) {
        Map<Long, SectionLight> rebuilt = new HashMap<Long, SectionLight>();
        ProjectorLighting<Object, String, MutableLightView> fresh = lighting(local, rebuilt);
        fresh.apply(OBSERVER, local, claims, null, sourceLighting);
        assertTrue(client.keySet().containsAll(rebuilt.keySet()));
        byte[] baselineSky = new byte[2048];
        byte[] baselineBlock = new byte[2048];
        Arrays.fill(baselineSky, (byte) ((local.sky << 4) | local.sky));
        Arrays.fill(baselineBlock, (byte) ((local.block << 4) | local.block));
        for (Map.Entry<Long, SectionLight> entry : client.entrySet()) {
            SectionLight expected = rebuilt.get(entry.getKey());
            assertArrayEquals(expected == null ? baselineSky : expected.sky, entry.getValue().sky);
            assertArrayEquals(expected == null ? baselineBlock : expected.block, entry.getValue().block);
        }
    }

    private static long keyForNibble(int chunkX, int section, int chunkZ, int nibble) {
        return CellKeys.pack((chunkX << 4) + (nibble & 15),
            (section << 4) + (nibble >> 8), (chunkZ << 4) + ((nibble >> 4) & 15));
    }

    private static ProjectedBlockClaim<String, MutableLightView> sourceClaim(MutableLightView source) {
        return new ProjectedBlockClaim<String, MutableLightView>(DATA, source, CellKeys.pack(20, 64, 20), false);
    }

    private static ProjectedBlockClaim<String, MutableLightView> fullBrightClaim() {
        return new ProjectedBlockClaim<String, MutableLightView>(DATA, null, ProjectedBlockClaim.NO_REMOTE_KEY, false,
            ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);
    }

    private static int nibble(byte[] bytes, int index) {
        return (bytes[index >> 1] >> ((index & 1) << 2)) & 15;
    }

    private record SectionLight(byte[] sky, byte[] block) {
    }

    private static final class CountingClaims extends Long2ObjectOpenHashMap<ProjectedBlockClaim<String, MutableLightView>> {
        private int entryScans;

        @Override
        public Long2ObjectMap.FastEntrySet<ProjectedBlockClaim<String, MutableLightView>> long2ObjectEntrySet() {
            entryScans++;
            return super.long2ObjectEntrySet();
        }
    }

    private static final class MutableLightView implements ContentView<String, String> {
        private int minHeight = -64;
        private int maxHeight = 320;
        private int sky;
        private int block;

        private MutableLightView(int sky, int block) {
            this.sky = sky;
            this.block = block;
        }

        @Override
        public UUID worldId() {
            return null;
        }

        @Override
        public int getMinHeight() {
            return minHeight;
        }

        @Override
        public int getMaxHeight() {
            return maxHeight;
        }

        @Override
        public String sampleBlockData(int x, int y, int z) {
            return null;
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public int biomeId(int x, int y, int z) {
            return -1;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return sky < 0 || block < 0 ? LIGHT_UNAVAILABLE : ContentView.packLight(sky, block);
        }

        @Override
        public String material(int x, int y, int z) {
            return null;
        }

        @Override
        public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
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

        @Override
        public int getSkyDarken() {
            return 0;
        }
    }
}
