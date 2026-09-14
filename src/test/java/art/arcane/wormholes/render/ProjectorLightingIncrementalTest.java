package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectorLightingIncrementalTest {
    private static final Player OBSERVER = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
        new Class<?>[] {Player.class},
        (proxy, method, arguments) -> "isOnline".equals(method.getName()) ? Boolean.TRUE : null);
    private static final BlockData DATA = (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
        new Class<?>[] {BlockData.class}, (proxy, method, arguments) -> switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> null;
        });

    private boolean adaptiveLighting;
    private int sectionBudget;

    @BeforeEach
    public void disableBudget() {
        adaptiveLighting = Settings.ADAPTIVE_LIGHTING;
        sectionBudget = Settings.LIGHTING_MAX_SECTIONS_PER_PASS;
        Settings.ADAPTIVE_LIGHTING = false;
    }

    @AfterEach
    public void restoreBudget() {
        Settings.ADAPTIVE_LIGHTING = adaptiveLighting;
        Settings.LIGHTING_MAX_SECTIONS_PER_PASS = sectionBudget;
    }

    @Test
    public void sparseEditsMatchFreshRebuildAcrossDenseSectionsAndChunks() {
        MutableLightView local = new MutableLightView(3, 4);
        MutableLightView source = new MutableLightView(12, 7);
        MutableLightView unavailable = new MutableLightView(-1, -1);
        CountingClaims claims = new CountingClaims();
        for (int nibble = 0; nibble < 4096; nibble++) {
            claims.put(keyForNibble(-1, -4, 2, nibble), sourceClaim(source));
        }
        claims.put(ProjectionCellKey.pack(1, 64, 1), fullBrightClaim());
        claims.put(ProjectionCellKey.pack(35, 100, -3), sourceClaim(source));
        claims.put(ProjectionCellKey.pack(35, 101, -3), sourceClaim(unavailable));
        claims.put(ProjectionCellKey.pack(0, 320, 0), fullBrightClaim());
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting lighting = lighting(local, client);
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
                    default -> claims.put(key, new ProjectedBlockClaim(DATA, null,
                        ProjectedBlockClaim.NO_REMOTE_KEY, true));
                }
            }
            lighting.apply(OBSERVER, local, claims, dirty);
            assertEquals(scans, claims.entryScans);
            assertMatchesRebuild(client, local, claims, true);
            scans = claims.entryScans;
        }

        long removedSection = ProjectionCellKey.pack(1, 64, 1);
        claims.remove(removedSection);
        lighting.apply(OBSERVER, local, claims, LongSet.of(removedSection));
        assertMatchesRebuild(client, local, claims, true);
    }

    @Test
    public void dirtyUpdatesReadCurrentSourceLightWithoutAnotherGlobalScan() {
        MutableLightView local = new MutableLightView(3, 4);
        MutableLightView source = new MutableLightView(10, 6);
        CountingClaims claims = new CountingClaims();
        long first = ProjectionCellKey.pack(1, 64, 1);
        long second = ProjectionCellKey.pack(2, 64, 1);
        claims.put(first, sourceClaim(source));
        claims.put(second, sourceClaim(source));
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting lighting = lighting(local, client);
        lighting.apply(OBSERVER, local, claims, LongSet.of(first, second));
        assertEquals(1, claims.entryScans);

        source.sky = 7;
        source.block = 11;
        claims.put(first, fullBrightClaim());
        lighting.apply(OBSERVER, local, claims, LongSet.of(first));

        assertEquals(1, claims.entryScans);
        SectionLight light = client.get(ProjectionCellKey.pack(0, 4, 0));
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
        long first = ProjectionCellKey.pack(1, 64, 1);
        long second = ProjectionCellKey.pack(2, 64, 1);
        claims.put(first, fullBrightClaim());
        claims.put(second, sourceClaim(source));
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting lighting = lighting(local, client);
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
        long sourceKey = ProjectionCellKey.pack(1, 64, 1);
        long brightKey = ProjectionCellKey.pack(2, 64, 1);
        long highKey = ProjectionCellKey.pack(1, 96, 1);
        claims.put(sourceKey, sourceClaim(source));
        claims.put(brightKey, fullBrightClaim());
        claims.put(highKey, fullBrightClaim());
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting lighting = lighting(local, client);
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
        client.remove(ProjectionCellKey.pack(0, 6, 0));
        assertMatchesRebuild(client, replacement, claims, true);
    }

    @Test
    public void pendingBudgetUsesLiveSourcesWithoutRescanningTheClaimMap() {
        Settings.ADAPTIVE_LIGHTING = true;
        Settings.LIGHTING_MAX_SECTIONS_PER_PASS = 1;
        MutableLightView local = new MutableLightView(3, 4);
        MutableLightView source = new MutableLightView(8, 7);
        CountingClaims claims = new CountingClaims();
        for (int section = 4; section < 7; section++) {
            claims.put(ProjectionCellKey.pack(1, section << 4, 1), sourceClaim(source));
        }
        Map<Long, SectionLight> client = new HashMap<Long, SectionLight>();
        ProjectorLighting lighting = lighting(local, client);
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
        ProjectorLighting lighting = lighting(local, client);
        ProjectionClaimSet claims = new ProjectionClaimSet();
        Long2ObjectOpenHashMap<ProjectedBlockClaim> sourceClaims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>();
        Long2ObjectOpenHashMap<ProjectedBlockClaim> brightClaims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>();
        long key = ProjectionCellKey.pack(1, 64, 1);
        sourceClaims.put(key, sourceClaim(source));
        brightClaims.put(key, fullBrightClaim());
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        ProjectionClaimSet.ProjectionClaimSetResult result = claims.replacePortalClaims(first, "first", 4.0D, sourceClaims);
        lighting.apply(OBSERVER, local, claims.getWinningClaims(), result.getDirtyLightingKeys());
        assertMatchesRebuild(client, local, claims.getWinningClaims(), true);

        result = claims.replacePortalClaims(second, "second", 1.0D, brightClaims);
        lighting.apply(OBSERVER, local, claims.getWinningClaims(), result.getDirtyLightingKeys());
        assertEquals(15, nibble(client.get(ProjectionCellKey.pack(0, 4, 0)).block, 17));
        assertMatchesRebuild(client, local, claims.getWinningClaims(), true);

        result = claims.releasePortal(second);
        lighting.apply(OBSERVER, local, claims.getWinningClaims(), result.getDirtyLightingKeys());
        assertEquals(7, nibble(client.get(ProjectionCellKey.pack(0, 4, 0)).block, 17));
        assertMatchesRebuild(client, local, claims.getWinningClaims(), true);
        result = claims.releasePortal(first);
        lighting.apply(OBSERVER, local, claims.getWinningClaims(), result.getDirtyLightingKeys());
        assertMatchesRebuild(client, local, claims.getWinningClaims(), true);
        assertTrue(lighting.isIdle());
    }

    private static ProjectorLighting lighting(MutableLightView local, Map<Long, SectionLight> client) {
        return new ProjectorLighting((observer, x, z) -> true,
            (observer, x, z, data) -> receive(client, local, x, z, data));
    }

    private static void receive(Map<Long, SectionLight> client, MutableLightView local,
                                int chunkX, int chunkZ, LightData data) {
        int arrayIndex = 0;
        for (int maskIndex = data.getSkyLightMask().nextSetBit(0); maskIndex >= 0;
             maskIndex = data.getSkyLightMask().nextSetBit(maskIndex + 1)) {
            int section = maskIndex + (local.minHeight >> 4) - 1;
            client.put(ProjectionCellKey.pack(chunkX, section, chunkZ),
                new SectionLight(data.getSkyLightArray()[arrayIndex], data.getBlockLightArray()[arrayIndex]));
            arrayIndex++;
        }
    }

    private static void assertMatchesRebuild(Map<Long, SectionLight> client, MutableLightView local,
                                            Long2ObjectMap<ProjectedBlockClaim> claims, boolean sourceLighting) {
        Map<Long, SectionLight> rebuilt = new HashMap<Long, SectionLight>();
        ProjectorLighting fresh = lighting(local, rebuilt);
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
        return ProjectionCellKey.pack((chunkX << 4) + (nibble & 15),
            (section << 4) + (nibble >> 8), (chunkZ << 4) + ((nibble >> 4) & 15));
    }

    private static ProjectedBlockClaim sourceClaim(ProjectionWorldView source) {
        return new ProjectedBlockClaim(DATA, source, ProjectionCellKey.pack(20, 64, 20), false);
    }

    private static ProjectedBlockClaim fullBrightClaim() {
        return new ProjectedBlockClaim(DATA, null, ProjectedBlockClaim.NO_REMOTE_KEY, false,
            ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);
    }

    private static int nibble(byte[] bytes, int index) {
        return (bytes[index >> 1] >> ((index & 1) << 2)) & 15;
    }

    private record SectionLight(byte[] sky, byte[] block) {
    }

    private static final class CountingClaims extends Long2ObjectOpenHashMap<ProjectedBlockClaim> {
        private int entryScans;

        @Override
        public Long2ObjectMap.FastEntrySet<ProjectedBlockClaim> long2ObjectEntrySet() {
            entryScans++;
            return super.long2ObjectEntrySet();
        }
    }

    private static final class MutableLightView implements ProjectionWorldView {
        private int minHeight = -64;
        private int maxHeight = 320;
        private int sky;
        private int block;

        private MutableLightView(int sky, int block) {
            this.sky = sky;
            this.block = block;
        }

        @Override
        public World getWorld() {
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
        public BlockData sampleBlockData(int x, int y, int z) {
            return null;
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return sky < 0 || block < 0 ? LIGHT_UNAVAILABLE : ProjectionWorldView.packLight(sky, block);
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }
    }
}
