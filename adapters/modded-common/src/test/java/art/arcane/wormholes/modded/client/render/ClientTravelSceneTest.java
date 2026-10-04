package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.Test;
import org.junit.BeforeClass;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

public class ClientTravelSceneTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void freshReturnMetadataPreservesSnapshotsWhileChangedHaloRetiresOnlyAffectedSections() {
        ClientLevel level = level();
        LevelChunk chunk = level.getChunkSource().getChunk(0, 0, ChunkStatus.FULL, false);
        LevelChunkSection section = chunk.getSection(0);
        when(section.hasOnlyAir()).thenReturn(false);
        ClientViewMessage.TravelBegin original = begin();
        ClientViewMessage.TravelWorld world = new ClientViewMessage.TravelWorld("minecraft:overworld", "minecraft:overworld",
            9, false, false, 63, 0, 256);
        when(original.world()).thenReturn(world);
        ClientViewEnvironment initial = PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY);
        when(original.environment()).thenReturn(initial);
        RenderSectionRegion region = mock(RenderSectionRegion.class);
        try (MockedConstruction<RenderRegionCache> caches = mockConstruction(RenderRegionCache.class,
            (cache, context) -> when(cache.createRegion(eq(level), anyLong())).thenReturn(region))) {
            ClientTravelScene scene = new ClientTravelScene(level, original);
            Map<ClientViewMessage.TravelCoordinate, byte[]> payloads = new HashMap<>();
            for (ClientViewMessage.TravelCoordinate coordinate : original.chunks()) {
                payloads.put(coordinate, new byte[]{1});
            }
            scene.nativeColumns(payloads);
            for (int batch = 0; batch < 7; batch++) {
                advance(scene, Long.MAX_VALUE);
            }
            long key = SectionPos.asLong(0, 5, 0);
            long far = SectionPos.asLong(0, 12, 0);
            long revision = scene.revision(key);
            long stable = scene.revision(far);
            ClientTravelScene.MeshIdentity identity = scene.meshIdentity(key);
            int constructed = caches.constructed().size();
            ClientViewMessage.TravelBegin next = mock(ClientViewMessage.TravelBegin.class);
            when(next.world()).thenReturn(world);
            List<ClientViewMessage.TravelCoordinate> manifest = original.chunks();
            when(next.chunks()).thenReturn(manifest);
            ClientViewEnvironment current = new ClientViewEnvironment(initial.gameTime() + 20, initial.sky(), initial.fog(),
                initial.lighting(), initial.clouds(), initial.transform(), initial.dimension(), initial.world());
            when(next.environment()).thenReturn(current);
            when(next.arrival()).thenReturn(new ClientViewMessage.TravelPose(1.5, 80, 0.5, 180, 15));
            scene.rebind(next);
            assertSame(current, scene.environment());
            assertSame(region, scene.world(key));
            assertSame(identity, scene.meshIdentity(key));
            assertEquals(revision, scene.revision(key));
            assertTrue(scene.complete());
            scene.advance();
            assertEquals(constructed, caches.constructed().size());
            payloads.put(new ClientViewMessage.TravelCoordinate(0, 0), new byte[]{2});
            scene.nativeColumns(payloads);
            scene.changedSection(key);
            assertEquals(-1, scene.revision(key));
            assertEquals(stable, scene.revision(far));
            advance(scene, Long.MAX_VALUE);
            assertTrue(scene.revision(key) > revision);
            assertFalse(identity.same(scene.meshIdentity(key)));
            assertEquals(stable, scene.revision(far));
            when(next.world()).thenReturn(new ClientViewMessage.TravelWorld("minecraft:overworld", "minecraft:overworld",
                10, false, false, 63, 0, 256));
            assertThrows(IllegalArgumentException.class, () -> scene.rebind(next));
            assertSame(current, scene.environment());
        }
    }

    @Test
    public void allAirInteriorIsDrawableOnlyAfterBoundedSnapshotsAndIncludesEveryDirection() {
        ClientLevel level = level();
        try (MockedConstruction<RenderRegionCache> ignored = mockConstruction(RenderRegionCache.class)) {
            ClientTravelScene scene = new ClientTravelScene(level, begin());
            assertFalse(scene.complete());
            int expected = 0;
            for (LongIterator keys = scene.sectionKeys().iterator(); keys.hasNext();) {
                long key = keys.nextLong();
                assertEquals(-1, scene.revision(key));
                assertFalse(scene.empty(key));
                assertNull(scene.world(key));
                expected++;
            }
            assertEquals(25 * 16, expected);
            advance(scene, Long.MAX_VALUE);
            assertEquals(64, capturedSections(scene));
            assertFalse(scene.complete());
            for (int batch = 0; batch < 6; batch++) {
                advance(scene, Long.MAX_VALUE);
            }
            assertTrue(scene.complete());
            assertTrue(scene.fullWorld());
            int count = 0;
            for (LongIterator keys = scene.sectionKeys().iterator(); keys.hasNext();) {
                long key = keys.nextLong();
                assertTrue(scene.empty(key));
                assertTrue(scene.revision(key) > 0);
                assertTrue(Math.abs(SectionPos.x(key)) <= 2);
                assertTrue(Math.abs(SectionPos.z(key)) <= 2);
                count++;
            }
            assertEquals(25 * 16, count);
            assertEquals(-1, scene.revision(SectionPos.asLong(3, 5, 0)));
        }
    }

    @Test
    public void changedSectionRetiresOnlyItsHaloAndPreservesOtherHeights() {
        ClientLevel level = level();
        try (MockedConstruction<RenderRegionCache> ignored = mockConstruction(RenderRegionCache.class)) {
            ClientTravelScene scene = new ClientTravelScene(level, begin());
            for (int batch = 0; batch < 7; batch++) {
                advance(scene, Long.MAX_VALUE);
            }
            long changed = SectionPos.asLong(-2, 5, -2);
            long unaffected = SectionPos.asLong(-2, 10, -2);
            long old = scene.revision(changed);
            long stable = scene.revision(unaffected);
            scene.changedSection(SectionPos.asLong(-3, 5, -3));
            assertFalse(scene.complete());
            assertEquals(-1, scene.revision(changed));
            assertEquals(stable, scene.revision(unaffected));
            advance(scene, Long.MAX_VALUE);
            assertTrue(scene.complete());
            assertTrue(scene.revision(changed) > old);
            assertEquals(stable, scene.revision(unaffected));
        }
    }

    @Test
    public void interiorChangesQueueOnlyTwentySevenSectionsAndKeepInitialBatchThroughput() {
        ClientLevel level = level();
        try (MockedConstruction<RenderRegionCache> ignored = mockConstruction(RenderRegionCache.class)) {
            ClientTravelScene scene = new ClientTravelScene(level, begin());
            for (int batch = 0; batch < 7; batch++) {
                advance(scene, Long.MAX_VALUE);
            }
            long unchanged = SectionPos.asLong(0, 12, 0);
            long unchangedRevision = scene.revision(unchanged);
            int count = 0;
            for (LongIterator keys = scene.changedSection(SectionPos.asLong(0, 5, 0)).iterator(); keys.hasNext();) {
                long key = keys.nextLong();
                assertTrue(SectionPos.y(key) >= 4 && SectionPos.y(key) <= 6);
                assertEquals(-1, scene.revision(key));
                count++;
            }
            assertEquals(27, count);
            assertEquals(unchangedRevision, scene.revision(unchanged));
            advance(scene, Long.MAX_VALUE);
            assertTrue(scene.complete());
            assertEquals(unchangedRevision, scene.revision(unchanged));
            int boundaryCount = 0;
            for (LongIterator keys = scene.changedSection(SectionPos.asLong(0, -1, 0)).iterator(); keys.hasNext();) {
                assertEquals(0, SectionPos.y(keys.nextLong()));
                boundaryCount++;
            }
            assertEquals(9, boundaryCount);
            advance(scene, Long.MAX_VALUE);
            assertTrue(scene.complete());
        }
    }

    @Test
    public void expiredSnapshotBudgetMakesOneSectionOfProgressWithoutDrainingTheQueue() {
        ClientLevel level = level();
        try (MockedConstruction<RenderRegionCache> ignored = mockConstruction(RenderRegionCache.class)) {
            ClientTravelScene scene = new ClientTravelScene(level, begin());
            advance(scene, 0L);
            assertEquals(1, capturedSections(scene));
            assertFalse(scene.complete());
            advance(scene, Long.MAX_VALUE);
            assertEquals(65, capturedSections(scene));
        }
    }

    private static int capturedSections(ClientTravelScene scene) {
        int count = 0;
        for (LongIterator keys = scene.sectionKeys().iterator(); keys.hasNext();) {
            if (scene.revision(keys.nextLong()) >= 0) {
                count++;
            }
        }
        return count;
    }

    private static void advance(ClientTravelScene scene, long deadline) {
        try {
            Method advance = ClientTravelScene.class.getDeclaredMethod("advance", long.class);
            advance.setAccessible(true);
            advance.invoke(scene, deadline);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ClientLevel level() {
        ClientLevel level = mock(ClientLevel.class);
        ClientChunkCache cache = mock(ClientChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        LevelChunkSection section = mock(LevelChunkSection.class);
        when(chunk.getSection(anyInt())).thenReturn(section);
        when(section.hasOnlyAir()).thenReturn(true);
        when(level.getChunkSource()).thenReturn(cache);
        when(level.getMinSectionY()).thenReturn(0);
        when(level.getSectionsCount()).thenReturn(16);
        when(cache.getChunk(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(false))).thenReturn(chunk);
        when(chunk.getMinSectionY()).thenReturn(0);
        when(chunk.getSectionsCount()).thenReturn(16);
        return level;
    }

    private static ClientViewMessage.TravelBegin begin() {
        ClientViewMessage.TravelBegin begin = mock(ClientViewMessage.TravelBegin.class);
        List<ClientViewMessage.TravelCoordinate> columns = new ArrayList<>();
        for (int z = -3; z <= 3; z++) {
            for (int x = -3; x <= 3; x++) {
                columns.add(new ClientViewMessage.TravelCoordinate(x, z));
            }
        }
        when(begin.chunks()).thenReturn(columns);
        when(begin.arrival()).thenReturn(new ClientViewMessage.TravelPose(0, 80, 0, 0, 0));
        return begin;
    }
}
