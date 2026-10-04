package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalSettingsAccess;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class PortalIrisMainPipelinesTest {
    @Test
    public void failedNormalFactoryClosesHistoryAndRestoresConstructionScope() {
        AtomicInteger closed = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("factory failed");
        assertSame(expected, assertThrows(IllegalStateException.class, () -> PortalIrisMainPipelines.capture(() -> {
            PortalIrisHistory.register(state(closed, false));
            throw expected;
        })));
        assertEquals(1, closed.get());
        assertFalse(PortalIrisHistory.capturing());
        assertFalse(PortalIrisShaderLoading.deferred());
    }

    @Test
    public void nonShaderFactoryReleasesUnusedHistoryWithoutDestroyingReturnedPipeline() {
        AtomicInteger closed = new AtomicInteger();
        WorldRenderingPipeline pipeline = mock(WorldRenderingPipeline.class);
        assertSame(pipeline, PortalIrisMainPipelines.capture(() -> {
            PortalIrisHistory.register(state(closed, false));
            return pipeline;
        }));
        assertEquals(1, closed.get());
        verify(pipeline, times(0)).destroy();
        assertFalse(PortalIrisHistory.capturing());
    }

    @Test
    public void failedHistoryCleanupStillDestroysUnregisteredPipelineExactlyOnce() {
        AtomicInteger closed = new AtomicInteger();
        PortalIrisHistory history = new PortalIrisHistory();
        try (PortalIrisHistory.Scope scope = history.constructing()) {
            PortalIrisHistory.register(state(closed, true));
        }
        IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class);
        PortalIrisMainPipelines.Entry entry = new PortalIrisMainPipelines.Entry(
            new PortalIrisMainPipelines.Construction(null, pipeline, null, history, null));
        assertThrows(IllegalStateException.class, entry::close);
        entry.close();
        assertEquals(1, closed.get());
        verify(pipeline, times(1)).destroy();
    }

    @Test
    public void gpuCleanupFailureDoesNotRetainHistoryOwnership() {
        AtomicInteger closed = new AtomicInteger();
        PortalIrisHistory history = new PortalIrisHistory();
        try (PortalIrisHistory.Scope scope = history.constructing()) {
            PortalIrisHistory.register(state(closed, false));
        }
        IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class);
        doThrow(new IllegalStateException("destroy failed")).when(pipeline).destroy();
        PortalIrisMainPipelines.Entry entry = new PortalIrisMainPipelines.Entry(
            new PortalIrisMainPipelines.Construction(null, pipeline, null, history, null));
        assertThrows(IllegalStateException.class, entry::close);
        entry.close();
        assertEquals(1, closed.get());
        verify(pipeline, times(1)).destroy();
    }

    @Test
    public void dimensionSettingsRestoreSourceFlagsAfterDestinationConstruction() {
        WorldRenderingSettings settings = mock(WorldRenderingSettings.class, withSettings().useConstructor()
            .defaultAnswer(CALLS_REAL_METHODS).extraInterfaces(IrisPortalSettingsAccess.class));
        if (settings.getEntityIds() == null) {
            settings.setEntityIds(new Object2IntOpenHashMap<>());
        }
        if (settings.getItemIds() == null) {
            settings.setItemIds(new Object2IntOpenHashMap<>());
        }
        PortalIrisSettings original = PortalIrisSettings.capture(settings);
        try {
            settings.setAmbientOcclusionLevel(0.65f);
            settings.setUseSeparateAo(true);
            settings.setDisableDirectionalShading(true);
            settings.setVoxelizeLightBlocks(true);
            settings.setSeparateEntityDraws(true);
            settings.setBreaksAnisotropy(true);
            PortalIrisSettings source = PortalIrisSettings.capture(settings);
            settings.setAmbientOcclusionLevel(1.0f);
            settings.setUseSeparateAo(false);
            settings.setDisableDirectionalShading(false);
            settings.setVoxelizeLightBlocks(false);
            settings.setSeparateEntityDraws(false);
            settings.setBreaksAnisotropy(false);
            source.apply(settings);
            assertEquals(0.65f, settings.getAmbientOcclusionLevel(), 0.0f);
            assertTrue(settings.shouldUseSeparateAo());
            assertTrue(settings.shouldDisableDirectionalShading());
            assertTrue(settings.shouldVoxelizeLightBlocks());
            assertTrue(settings.shouldSeparateEntityDraws());
            assertTrue(settings.breaksAnisotropy());
        } finally {
            original.apply(settings);
        }
    }

    @Test
    public void ordinaryNonShaderPipelineNeverReceivesShaderSettings() throws ReflectiveOperationException {
        WorldRenderingPipeline pipeline = mock(WorldRenderingPipeline.class);
        PortalIrisMainPipelines.selected(null, pipeline);
        PortalIrisMainPipelines.selected(null, pipeline);
        Field settings = PortalIrisMainPipelines.class.getDeclaredField("SETTINGS");
        settings.setAccessible(true);
        Map<?, ?> retained = (Map<?, ?>) settings.get(null);
        assertFalse(retained.containsKey(pipeline));
    }

    private static PortalIrisHistory.State state(AtomicInteger closed, boolean fail) {
        return new PortalIrisHistory.State() {
            @Override
            public void wormholes$resetHistory() {
            }

            @Override
            public void close() {
                closed.incrementAndGet();
                if (fail) {
                    throw new IllegalStateException("history failed");
                }
            }
        };
    }
}
