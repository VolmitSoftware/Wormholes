package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.MinecraftTestBase;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.QueuedSectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import org.joml.Vector3d;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class SodiumTaskSweepTest extends MinecraftTestBase {
    private static final float DISTANCE = 128.0F;

    @Test
    public void previouslyEmptyDirtySectionsAreQueuedEvenWhenAbsentFromRenderableTerrain() {
        Terrain terrain = new Terrain();
        RenderSection dirty = section(1, ChunkUpdateTypes.REBUILD, false);
        terrain.add(dirty);
        List<Long> rendered = new ArrayList<>();
        terrain.renderable.prepareForTraversal();
        terrain.renderable.traverse((x, y, z) -> rendered.add(SectionPos.asLong(x, y, z)), viewport(8.0D), DISTANCE);
        assertTrue(rendered.isEmpty());
        SodiumTaskSweep.Work work = SodiumTaskSweep.arrival(terrain.sections, viewport(8.0D), DISTANCE);
        assertFalse(work.complete());
        assertEquals(1, work.pending().size());
        assertEquals(SectionPos.asLong(1, 4, 0), work.pending().dequeueNextSectionPos());
        assertEquals(ChunkUpdateTypes.REBUILD, dirty.getPendingUpdate());
    }

    @Test
    public void submittedDirtyBuildsRemainUnreadyUntilUploaded() {
        Terrain terrain = new Terrain();
        RenderSection dirty = section(0, 0, false);
        SodiumArrivalSection arrival = (SodiumArrivalSection) dirty;
        when(arrival.wormholes$buildsPending()).thenReturn(true);
        terrain.add(dirty);
        SodiumTaskSweep.Work submitted = SodiumTaskSweep.arrival(terrain.sections, viewport(8.0D), DISTANCE);
        assertTrue(submitted.pending().isEmpty());
        assertFalse(submitted.complete());
        assertTrue(submitted.hasRunningBuilds());
        when(arrival.wormholes$terrainComplete()).thenReturn(true);
        when(arrival.wormholes$buildsPending()).thenReturn(false);
        assertFalse(submitted.hasRunningBuilds());
        assertTrue(SodiumTaskSweep.arrival(terrain.sections, viewport(8.0D), DISTANCE).complete());
    }

    @Test
    public void readinessUsesTheExactWorldAndViewportInsteadOfAnotherResidentPreparation() {
        Terrain first = new Terrain();
        first.add(section(0, 0, true));
        first.add(section(80, ChunkUpdateTypes.INITIAL_BUILD, false));
        Terrain second = new Terrain();
        second.add(section(0, ChunkUpdateTypes.REBUILD, false));
        assertTrue(SodiumTaskSweep.arrival(first.sections, viewport(8.0D), DISTANCE).complete());
        assertFalse(SodiumTaskSweep.arrival(first.sections, viewport(1288.0D), DISTANCE).complete());
        assertFalse(SodiumTaskSweep.arrival(second.sections, viewport(8.0D), DISTANCE).complete());
    }

    @Test
    public void queuedReplacementsAreReadInsteadOfStaleSectionsDuringSafeReadPhases() {
        Terrain terrain = new Terrain();
        RenderSection previous = section(0, 0, true);
        terrain.add(previous);
        terrain.storage.startSafeReadPhase();
        RenderSection replacement = section(0, ChunkUpdateTypes.INITIAL_BUILD, false);
        terrain.add(replacement);
        assertEquals(previous, terrain.storage.getCurrent(0, 4, 0));
        assertFalse(SodiumTaskSweep.arrival(terrain.sections, viewport(8.0D), DISTANCE).complete());
        terrain.forest.remove(0, 4, 0);
        terrain.storage.queueRemove(SectionPos.asLong(0, 4, 0));
        assertTrue(SodiumTaskSweep.arrival(terrain.sections, viewport(8.0D), DISTANCE).complete());
        terrain.storage.endSafeReadPhase();
    }

    @Test
    public void distanceSelectionRetainsIntersectingSectionsAndExcludesFarTasks() {
        Terrain terrain = new Terrain();
        terrain.add(section(8, ChunkUpdateTypes.REBUILD, false));
        terrain.add(section(9, ChunkUpdateTypes.REBUILD, false));
        SodiumTaskSweep.Work work = SodiumTaskSweep.arrival(terrain.sections, viewport(8.0D), DISTANCE);
        assertFalse(work.complete());
        assertEquals(1, work.unfinished().size());
        assertEquals(1, work.pending().size());
        assertEquals(SectionPos.asLong(8, 4, 0), work.pending().dequeueNextSectionPos());
    }

    @Test
    public void distanceSelectionPreservesFractionalNegativeCameraCoordinates() {
        Terrain terrain = new Terrain();
        terrain.add(section(-9, ChunkUpdateTypes.REBUILD, false));
        terrain.add(section(-10, ChunkUpdateTypes.REBUILD, false));
        SodiumTaskSweep.Work work = SodiumTaskSweep.arrival(terrain.sections, viewport(-0.25D), DISTANCE);
        assertEquals(1, work.unfinished().size());
        assertEquals(1, work.pending().size());
        assertEquals(SectionPos.asLong(-9, 4, 0), work.pending().dequeueNextSectionPos());
    }

    @Test
    public void farSectionsCannotWrapIntoNearbyPackedTaskPositions() {
        Terrain terrain = new Terrain();
        terrain.add(section(0, 0, true));
        terrain.add(section(1024, ChunkUpdateTypes.INITIAL_BUILD, false));
        SodiumTaskSweep.Work work = SodiumTaskSweep.arrival(terrain.sections, viewport(8.0D), DISTANCE);
        assertTrue(work.complete());
        assertTrue(work.pending().isEmpty());
        assertTrue(work.unfinished().isEmpty());
    }

    @Test
    public void readinessWaitsForNearbyRunningJobsWithoutWaitingForFarJobs() {
        Terrain terrain = new Terrain();
        RenderSection far = section(80, 0, false);
        when(((SodiumArrivalSection) far).wormholes$buildsPending()).thenReturn(true);
        terrain.add(far);
        assertTrue(SodiumTaskSweep.arrival(terrain.sections, viewport(8.0D), DISTANCE).complete());
        RenderSection near = section(0, 0, false);
        when(((SodiumArrivalSection) near).wormholes$buildsPending()).thenReturn(true);
        terrain.add(near);
        SodiumTaskSweep.Work work = SodiumTaskSweep.arrival(terrain.sections, viewport(8.0D), DISTANCE);
        assertFalse(work.complete());
        assertTrue(work.pending().isEmpty());
        assertEquals(1, work.unfinished().size());
        assertTrue(work.hasRunningBuilds());
    }

    @Test
    public void resweepingDoesNotRequeueSubmittedOrCompletedSections() {
        Terrain terrain = new Terrain();
        RenderSection section = section(0, ChunkUpdateTypes.INITIAL_BUILD, false);
        terrain.add(section);
        DeferredTaskList initial = SodiumTaskSweep.pending(terrain.sections, viewport(8.0D), DISTANCE, false);
        assertEquals(1, initial.size());
        when(section.getPendingUpdate()).thenReturn(0);
        assertTrue(SodiumTaskSweep.pending(terrain.sections, viewport(8.0D), DISTANCE, false).isEmpty());
        when(((SodiumArrivalSection) section).wormholes$terrainComplete()).thenReturn(true);
        assertTrue(SodiumTaskSweep.arrival(terrain.sections, viewport(8.0D), DISTANCE).complete());
        when(section.getPendingUpdate()).thenReturn(ChunkUpdateTypes.REBUILD);
        when(((SodiumArrivalSection) section).wormholes$terrainComplete()).thenReturn(false);
        assertEquals(1, SodiumTaskSweep.pending(terrain.sections, viewport(8.0D), DISTANCE, false).size());
    }

    private static Viewport viewport(double x) {
        return new Viewport(PortalClipFrustum.OPEN, new Vector3d(x, 72.0D, 8.0D));
    }

    private static RenderSection section(int x, int pending, boolean complete) {
        RenderSection section = mock(RenderSection.class, withSettings().extraInterfaces(SodiumArrivalSection.class));
        when(section.getChunkX()).thenReturn(x);
        when(section.getChunkY()).thenReturn(4);
        when(section.getChunkZ()).thenReturn(0);
        when(section.getCenterX()).thenReturn(x * 16 + 8);
        when(section.getCenterY()).thenReturn(72);
        when(section.getCenterZ()).thenReturn(8);
        when(section.getPendingUpdate()).thenReturn(pending);
        when(((SodiumArrivalSection) section).wormholes$terrainComplete()).thenReturn(complete);
        return section;
    }

    private static final class Terrain {
        private final SodiumPortalSections sections = mock(SodiumPortalSections.class);
        private final QueuedSectionStorage storage = new QueuedSectionStorage();
        private final RemovableMultiForest forest = new RemovableMultiForest(DISTANCE);
        private final RemovableMultiForest renderable = new RemovableMultiForest(DISTANCE);

        private Terrain() {
            ClientLevel level = mock(ClientLevel.class);
            when(level.getHeight()).thenReturn(384);
            when(sections.wormholes$level()).thenReturn(level);
            when(sections.wormholes$storage()).thenReturn(storage);
            when(sections.wormholes$arrivalSections()).thenReturn(forest);
            when(sections.wormholes$renderable()).thenReturn(renderable);
        }

        private void add(RenderSection section) {
            storage.queuePut(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()), section);
            forest.add(section);
        }
    }
}
