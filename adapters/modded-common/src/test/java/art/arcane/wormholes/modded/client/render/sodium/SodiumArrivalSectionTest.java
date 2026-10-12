package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.SodiumArrivalSectionMixin;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJob;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SodiumArrivalSectionTest extends MinecraftTestBase {
    @Test
    public void dirtyGeometryIsNotReadyBetweenSubmissionAndFinalUpload() throws ReflectiveOperationException {
        Section section = new Section();
        section.built = true;
        List<ChunkJob> jobs = new ArrayList<>();
        set(section, "runningJobs", jobs);
        set(section, "pendingUpdateType", ChunkUpdateTypes.REBUILD);
        assertFalse(section.wormholes$terrainComplete());
        set(section, "pendingUpdateType", 0);
        jobs.add(mock(ChunkJob.class));
        assertFalse(section.wormholes$terrainComplete());
        jobs.clear();
        set(section, "pendingBuildOutput", mock(ChunkBuildOutput.class));
        assertTrue(section.wormholes$buildsPending());
        assertFalse(section.wormholes$terrainComplete());
        set(section, "pendingBuildOutput", null);
        assertFalse(section.wormholes$buildsPending());
        assertTrue(section.wormholes$terrainComplete());
    }

    @Test
    public void translucentSortOutputsAndUnbuiltOrDisposedSectionsCannotBeReady() throws ReflectiveOperationException {
        Section section = new Section();
        set(section, "runningJobs", List.of());
        assertFalse(section.wormholes$terrainComplete());
        section.built = true;
        set(section, "pendingDynamicSortOutput", mock(ChunkSortOutput.class));
        assertFalse(section.wormholes$terrainComplete());
        set(section, "pendingDynamicSortOutput", null);
        assertTrue(section.wormholes$terrainComplete());
        section.disposed = true;
        assertFalse(section.wormholes$terrainComplete());
    }

    @Test
    public void fallbackStealsOnlyJobsThatHaveNotStartedAndRetainsTheirUploadBarrier() throws ReflectiveOperationException {
        Section section = new Section();
        section.built = true;
        ChunkJob queued = mock(ChunkJob.class);
        ChunkJob running = mock(ChunkJob.class);
        when(running.isStarted()).thenReturn(true);
        set(section, "runningJobs", List.of(queued, running));
        ChunkBuilder builder = mock(ChunkBuilder.class);
        section.wormholes$stealBuilds(builder);
        verify(builder).tryStealTask(queued);
        verify(builder, never()).tryStealTask(running);
        assertTrue(section.wormholes$buildsPending());
        assertFalse(section.wormholes$terrainComplete());
    }

    private static void set(Section section, String name, Object value) throws ReflectiveOperationException {
        Field field = SodiumArrivalSectionMixin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(section, value);
    }

    private static final class Section extends SodiumArrivalSectionMixin {
        private boolean built;
        private boolean disposed;

        @Override
        public boolean isBuilt() {
            return built;
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }
}
