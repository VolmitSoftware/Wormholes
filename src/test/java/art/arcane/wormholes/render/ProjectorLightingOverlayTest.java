package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectorLightingOverlayTest {
    @Test
    public void overlayWritesOnlyClaimedRemoteLitCellsInTargetSection() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(4);
        ProjectionWorldView view = stubView(12, 7, 0);
        claims.put(packKey(3, 70, 5), new ProjectedBlockClaim(null, view, packKey(100, 40, 100), false));
        claims.put(packKey(3, 90, 5), new ProjectedBlockClaim(null, view, packKey(100, 41, 100), false));
        claims.put(packKey(35, 70, 5), new ProjectedBlockClaim(null, view, packKey(100, 42, 100), false));
        claims.put(packKey(4, 70, 5), new ProjectedBlockClaim(null, null, ProjectedBlockClaim.NO_REMOTE_KEY, true));

        LightData data = apply(claims, stubView(3, 4, 0), true).get(0L);
        byte[] skyArr = data.getSkyLightArray()[0];
        byte[] blockArr = data.getBlockLightArray()[0];

        int nibbleIdx = ((70 - 64) << 8) | (5 << 4) | 3;
        assertEquals(12, readNibble(skyArr, nibbleIdx));
        assertEquals(7, readNibble(blockArr, nibbleIdx));
        int maskAirIdx = ((70 - 64) << 8) | (5 << 4) | 4;
        assertEquals(3, readNibble(skyArr, maskAirIdx));
        assertEquals(4, readNibble(blockArr, maskAirIdx));
        int untouched = 0;
        for (int i = 0; i < 4096; i++) {
            if (i == nibbleIdx) {
                continue;
            }
            if (readNibble(skyArr, i) == 3 && readNibble(blockArr, i) == 4) {
                untouched++;
            }
        }
        assertEquals(4095, untouched);
    }

    @Test
    public void overlayMixesSourceDarkenIntoLocalSkyExactlyLikeTheFullScan() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(1);
        ProjectionWorldView view = stubView(15, 0, 11);
        claims.put(packKey(0, 64, 0), new ProjectedBlockClaim(null, view, packKey(200, 40, 200), false));

        LightData data = apply(claims, stubView(0, 0, 11), true).get(0L);
        byte[] skyArr = data.getSkyLightArray()[0];
        byte[] blockArr = data.getBlockLightArray()[0];

        int rawSky = 15;
        int rawBlock = 0;
        int sourceSkyBrightness = Math.max(0, rawSky - 11);
        int expectedSky = Math.min(15, sourceSkyBrightness + 11);
        int target = Math.max(rawBlock, sourceSkyBrightness);
        int expectedBlock = target > 15 - 11 ? target : rawBlock;
        assertEquals(expectedSky, readNibble(skyArr, 0));
        assertEquals(expectedBlock, readNibble(blockArr, 0));
    }

    @Test
    public void overlaySkipsUnavailableLightAndOutOfRangeRemoteY() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(2);
        ProjectionWorldView unavailableView = stubView(-1, -1, 0);
        ProjectionWorldView normalView = stubView(5, 5, 0);
        claims.put(packKey(0, 64, 0), new ProjectedBlockClaim(null, unavailableView, packKey(0, 40, 0), false));
        claims.put(packKey(1, 64, 0), new ProjectedBlockClaim(null, normalView, packKey(0, 5000, 0), false));

        LightData data = apply(claims, stubView(9, 9, 0), true).get(0L);
        byte[] skyArr = data.getSkyLightArray()[0];
        byte[] blockArr = data.getBlockLightArray()[0];

        assertEquals(9, readNibble(skyArr, 0));
        assertEquals(9, readNibble(blockArr, 0));
        assertEquals(9, readNibble(skyArr, 1));
        assertEquals(9, readNibble(blockArr, 1));
    }

    @Test
    public void fullBrightClaimsOverrideBothChannelsWithoutSourceLighting() {
        ProjectionWorldView view = stubView(8, 7, 0);
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(2);
        claims.put(packKey(0, 64, 0), new ProjectedBlockClaim(
            null, view, packKey(20, 64, 20), false, ProjectedBlockClaim.LightingPolicy.SOURCE));
        claims.put(packKey(1, 64, 0), new ProjectedBlockClaim(
            null, null, ProjectedBlockClaim.NO_REMOTE_KEY, false,
            ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT));

        LightData data = apply(claims, stubView(2, 3, 0), false).get(0L);
        byte[] skyArr = data.getSkyLightArray()[0];
        byte[] blockArr = data.getBlockLightArray()[0];

        assertEquals(2, readNibble(skyArr, 0));
        assertEquals(3, readNibble(blockArr, 0));
        assertEquals(15, readNibble(skyArr, 1));
        assertEquals(15, readNibble(blockArr, 1));
    }

    @Test
    public void writeLightNibblePacksLowAndHighNibbles() {
        byte[] skyArr = new byte[2048];
        byte[] blockArr = new byte[2048];

        ProjectorLighting.writeLightNibble(skyArr, blockArr, 0, 5, 9);
        ProjectorLighting.writeLightNibble(skyArr, blockArr, 1, 12, 3);

        assertEquals(5, readNibble(skyArr, 0));
        assertEquals(12, readNibble(skyArr, 1));
        assertEquals(9, readNibble(blockArr, 0));
        assertEquals(3, readNibble(blockArr, 1));
    }

    @Test
    public void oneClaimScanProducesExactArraysAcrossChunksAndSections() {
        CountingClaims claims = new CountingClaims();
        ProjectionWorldView source = stubView(15, 2, 3);
        ProjectionWorldView unavailable = stubView(-1, -1, 0);
        int[] chunkCoordinates = {-2, 0, 3};
        int[] sections = {-4, 0, 4, 19};
        for (int chunk : chunkCoordinates) {
            for (int section : sections) {
                for (int offset = 0; offset < 32; offset++) {
                    int x = (chunk << 4) + (offset & 15);
                    int y = (section << 4) + (offset >> 4);
                    int z = (chunk << 4) + 5;
                    ProjectedBlockClaim claim = switch (offset % 4) {
                        case 0 -> new ProjectedBlockClaim(null, source, packKey(100, 80, 100), false);
                        case 1 -> new ProjectedBlockClaim(null, null, ProjectedBlockClaim.NO_REMOTE_KEY,
                            false, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);
                        case 2 -> new ProjectedBlockClaim(null, unavailable, packKey(100, 80, 100), false);
                        default -> new ProjectedBlockClaim(null, source, packKey(100, 400, 100), false);
                    };
                    claims.put(packKey(x, y, z), claim);
                }
            }
        }
        claims.put(packKey(0, -65, 0), new ProjectedBlockClaim(null, source, packKey(100, 80, 100), false));
        claims.put(packKey(0, 320, 0), new ProjectedBlockClaim(null, source, packKey(100, 80, 100), false));

        Map<Long, LightData> packets = apply(claims, stubView(3, 4, 6), true);

        assertEquals(1, claims.entryScans);
        assertEquals(3, packets.size());
        byte[] expectedSky = new byte[2048];
        byte[] expectedBlock = new byte[2048];
        Arrays.fill(expectedSky, (byte) 0x33);
        Arrays.fill(expectedBlock, (byte) 0x44);
        for (int offset = 0; offset < 32; offset++) {
            if (offset % 4 > 1) {
                continue;
            }
            int nibbleIndex = ((offset >> 4) << 8) | (5 << 4) | (offset & 15);
            int byteIndex = nibbleIndex >> 1;
            if ((nibbleIndex & 1) == 0) {
                expectedSky[byteIndex] = (byte) ((expectedSky[byteIndex] & 0xF0) | 15);
                expectedBlock[byteIndex] = (byte) ((expectedBlock[byteIndex] & 0xF0) | 12);
            } else {
                expectedSky[byteIndex] = (byte) ((expectedSky[byteIndex] & 0x0F) | 0xF0);
                expectedBlock[byteIndex] = (byte) ((expectedBlock[byteIndex] & 0x0F) | 0xF0);
            }
        }
        for (int chunk : chunkCoordinates) {
            LightData packet = packets.get(chunkKey(chunk, chunk));
            assertEquals(sections.length, packet.getSkyLightArray().length);
            assertEquals(sections.length, packet.getBlockLightArray().length);
            assertEquals(sections.length, packet.getSkyLightMask().cardinality());
            assertEquals(sections.length, packet.getBlockLightMask().cardinality());
            for (int index = 0; index < sections.length; index++) {
                assertTrue(packet.getSkyLightMask().get(sections[index] + 5));
                assertArrayEquals(expectedSky, packet.getSkyLightArray()[index]);
                assertArrayEquals(expectedBlock, packet.getBlockLightArray()[index]);
            }
        }
    }

    private static Map<Long, LightData> apply(Long2ObjectMap<ProjectedBlockClaim> claims,
                                             ProjectionWorldView localView,
                                             boolean sourceLightingEnabled) {
        boolean adaptiveLighting = Settings.ADAPTIVE_LIGHTING;
        Settings.ADAPTIVE_LIGHTING = false;
        try {
            Map<Long, LightData> packets = new HashMap<Long, LightData>();
            ProjectorLighting lighting = new ProjectorLighting(
                (observer, chunkX, chunkZ) -> true,
                (observer, chunkX, chunkZ, data) -> packets.put(chunkKey(chunkX, chunkZ), data));
            Player observer = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, arguments) -> "isOnline".equals(method.getName()) ? Boolean.TRUE : null);
            lighting.apply(observer, localView, claims, null, sourceLightingEnabled);
            return packets;
        } finally {
            Settings.ADAPTIVE_LIGHTING = adaptiveLighting;
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static final class CountingClaims extends Long2ObjectOpenHashMap<ProjectedBlockClaim> {
        private int entryScans;

        @Override
        public Long2ObjectMap.FastEntrySet<ProjectedBlockClaim> long2ObjectEntrySet() {
            entryScans++;
            return super.long2ObjectEntrySet();
        }
    }

    private static int readNibble(byte[] arr, int nibbleIdx) {
        int byteIdx = nibbleIdx >> 1;
        if ((nibbleIdx & 1) == 0) {
            return arr[byteIdx] & 0x0F;
        }
        return (arr[byteIdx] >> 4) & 0x0F;
    }

    private static ProjectionWorldView stubView(int sky, int block, int skyDarken) {
        return new ProjectionWorldView() {
            @Override
            public World getWorld() {
                return null;
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
            public BlockData sampleBlockData(int x, int y, int z) {
                return null;
            }

            @Override
            public String sampleBiome(int x, int y, int z) {
                return null;
            }

            @Override
            public int getLight(int x, int y, int z) {
                if (sky < 0 || block < 0) {
                    return ProjectionWorldView.LIGHT_UNAVAILABLE;
                }
                return ProjectionWorldView.packLight(sky, block);
            }

            @Override
            public int getSkyDarken() {
                return skyDarken;
            }
        };
    }

    private static long packKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
    }
}
