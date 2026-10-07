package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalSettingsAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PortalIrisMainPipelinesTest {
    @Test
    public void nativeDrawCleanupConsumesOnlyAnOutstandingPhaseAndRestoresFlagsOnFailure() {
        IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class, withSettings().extraInterfaces(IrisPortalRenderingAccess.class));
        IrisPortalRenderingAccess access = (IrisPortalRenderingAccess) pipeline;
        PortalIrisMainPipelines.DrawState state = new PortalIrisMainPipelines.DrawState(false, true, true);
        when(access.wormholes$phase()).thenReturn(WorldRenderingPhase.NONE);
        state.restore(pipeline);
        verify(pipeline, times(0)).setPhase(WorldRenderingPhase.NONE);
        verify(pipeline).removePhaseIfNeeded();
        verify(access).wormholes$renderingWorld(false);
        verify(access).wormholes$mainBound(true);
        assertTrue(pipeline.isBeforeTranslucent);
        when(access.wormholes$phase()).thenReturn(WorldRenderingPhase.TERRAIN_SOLID);
        doThrow(new IllegalStateException("phase cleanup failed")).when(pipeline).removePhaseIfNeeded();
        assertThrows(IllegalStateException.class, () -> state.restore(pipeline));
        verify(pipeline).setPhase(WorldRenderingPhase.NONE);
        verify(access, times(2)).wormholes$renderingWorld(false);
        verify(access, times(2)).wormholes$mainBound(true);
        assertTrue(pipeline.isBeforeTranslucent);
    }

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
    public void irisPipelineCreationReadsEveryDimensionsProgramsBeforeAnyPortalNeedsThem() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        NamespacedId modded = new NamespacedId("example:caverns");
        when(pack.getDimensionMap()).thenReturn(Map.of(modded, "world7"));
        IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class);
        assertSame(pipeline, captureWithPack(pack, pipeline));
        verify(pack).getProgramSet(DimensionId.OVERWORLD);
        verify(pack).getProgramSet(DimensionId.NETHER);
        verify(pack).getProgramSet(DimensionId.END);
        verify(pack).getProgramSet(modded);
    }

    @Test
    public void unreadablePackDimensionStillLeavesTheIrisPipelineInPlace() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        when(pack.getDimensionMap()).thenReturn(Map.of());
        when(pack.getProgramSet(DimensionId.NETHER)).thenThrow(new IllegalStateException("broken include"));
        IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class);
        assertSame(pipeline, captureWithPack(pack, pipeline));
        verify(pack).getProgramSet(DimensionId.END);
        verify(pipeline, times(0)).destroy();
    }

    @Test
    public void viewsWaitOnlyWhileAnUnregisteredMainPipelineIsStillBeingPrepared() throws ReflectiveOperationException {
        Field pending = PortalIrisMainPipelines.class.getDeclaredField("pending");
        Field preparedAt = PortalIrisMainPipelines.class.getDeclaredField("preparedAt");
        Field registered = PortalIrisMainPipelines.Entry.class.getDeclaredField("registered");
        pending.setAccessible(true);
        preparedAt.setAccessible(true);
        registered.setAccessible(true);
        PortalIrisMainPipelines.Entry entry = new PortalIrisMainPipelines.Entry(
            new PortalIrisMainPipelines.Construction(null, mock(IrisRenderingPipeline.class), null, new PortalIrisHistory(), null));
        try {
            assertFalse(PortalIrisMainPipelines.preparing());
            pending.set(null, entry);
            preparedAt.setLong(null, System.nanoTime());
            assertTrue(PortalIrisMainPipelines.preparing());
            preparedAt.setLong(null, System.nanoTime() - 1_000_000_000L);
            assertFalse(PortalIrisMainPipelines.preparing());
            preparedAt.setLong(null, System.nanoTime());
            registered.setBoolean(entry, true);
            assertFalse(PortalIrisMainPipelines.preparing());
        } finally {
            pending.set(null, null);
            preparedAt.setLong(null, 0L);
        }
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

    @Test
    @SuppressWarnings("unchecked")
    public void failedTerrainCleanupStillReleasesAllRegisteredPipelineHistories() throws ReflectiveOperationException {
        Field field = PortalIrisMainPipelines.class.getDeclaredField("HISTORIES");
        field.setAccessible(true);
        Map<IrisRenderingPipeline, PortalIrisMainPipelines.Entry> entries =
            (Map<IrisRenderingPipeline, PortalIrisMainPipelines.Entry>) field.get(null);
        AtomicInteger closed = new AtomicInteger();
        for (int index = 0; index < 2; index++) {
            PortalIrisHistory history = new PortalIrisHistory();
            try (PortalIrisHistory.Scope scope = history.constructing()) {
                PortalIrisHistory.register(state(closed, index == 0));
            }
            IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class);
            entries.put(pipeline, new PortalIrisMainPipelines.Entry(
                new PortalIrisMainPipelines.Construction(null, pipeline, null, history, null)));
        }
        AssertionError expected = new AssertionError("terrain cleanup failed");
        try (MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class)) {
            terrain.when(ClientSodiumTerrain::clear).thenThrow(expected);
            assertSame(expected, assertThrows(AssertionError.class, PortalIrisMainPipelines::destroyed));
            assertEquals(1, expected.getSuppressed().length);
            assertEquals(2, closed.get());
            assertTrue(entries.isEmpty());
        } finally {
            entries.clear();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void unshadedPreparedAttachKeepsNativeTerrainWhileStillRetiringShaderHistories() throws ReflectiveOperationException {
        Field field = PortalIrisMainPipelines.class.getDeclaredField("HISTORIES");
        field.setAccessible(true);
        Map<IrisRenderingPipeline, PortalIrisMainPipelines.Entry> entries =
            (Map<IrisRenderingPipeline, PortalIrisMainPipelines.Entry>) field.get(null);
        AtomicInteger closed = new AtomicInteger();
        PortalIrisHistory history = new PortalIrisHistory();
        try (PortalIrisHistory.Scope scope = history.constructing()) {
            PortalIrisHistory.register(state(closed, false));
        }
        IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class);
        entries.put(pipeline, new PortalIrisMainPipelines.Entry(
            new PortalIrisMainPipelines.Construction(null, pipeline, null, history, null)));
        try (MockedStatic<ClientSodiumTerrain> terrain = mockStatic(ClientSodiumTerrain.class)) {
            terrain.when(ClientSodiumTerrain::retainUnshadedHandoff).thenReturn(true);
            PortalIrisMainPipelines.destroyed();
            terrain.verify(ClientSodiumTerrain::clear, times(0));
            assertEquals(1, closed.get());
            assertTrue(entries.isEmpty());
        } finally {
            entries.clear();
        }
    }

    @Test
    public void selectingEquivalentPreparedSettingsKeepsCompiledTerrain() throws ReflectiveOperationException {
        assertSelectedReload(true, false, false);
    }

    @Test
    public void selectingIncompatiblePreparedSettingsRequestsTerrainAfterApplyingDestinationMaterials() throws ReflectiveOperationException {
        assertSelectedReload(false, false, true);
    }

    @Test
    public void selectingEquivalentSettingsPreservesAnAlreadyRequiredResourceReload() throws ReflectiveOperationException {
        assertSelectedReload(true, true, true);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void authoritativeReturnRetainsPackOwnedPipelineWithoutAdvertisingPrefetchReadiness() throws ReflectiveOperationException {
        PortalIrisMainPipelines.clearPending();
        Field historiesField = PortalIrisMainPipelines.class.getDeclaredField("HISTORIES");
        historiesField.setAccessible(true);
        Map<IrisRenderingPipeline, PortalIrisMainPipelines.Entry> histories =
            (Map<IrisRenderingPipeline, PortalIrisMainPipelines.Entry>) historiesField.get(null);
        Field settingsField = PortalIrisMainPipelines.class.getDeclaredField("SETTINGS");
        settingsField.setAccessible(true);
        Map<WorldRenderingPipeline, PortalIrisSettings> settings = (Map<WorldRenderingPipeline, PortalIrisSettings>) settingsField.get(null);
        ShaderPack pack = mock(ShaderPack.class);
        IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class);
        PortalIrisMainPipelines.Entry entry = new PortalIrisMainPipelines.Entry(
            new PortalIrisMainPipelines.Construction(pack, pipeline, null, new PortalIrisHistory(), null));
        Field registered = PortalIrisMainPipelines.Entry.class.getDeclaredField("registered");
        registered.setAccessible(true);
        registered.setBoolean(entry, true);
        histories.put(pipeline, entry);
        PortalIrisSettings destinationSettings = mock(PortalIrisSettings.class);
        PortalIrisSettings currentSettings = mock(PortalIrisSettings.class);
        when(destinationSettings.terrainCompatible(currentSettings)).thenReturn(true);
        settings.put(pipeline, destinationSettings);
        NamespacedId dimension = new NamespacedId("minecraft", "overworld");
        Map<NamespacedId, WorldRenderingPipeline> pipelines = new HashMap<>();
        pipelines.put(dimension, pipeline);
        PipelineManager manager = mock(PipelineManager.class, withSettings().extraInterfaces(PortalMainPipelineAccess.class));
        PortalMainPipelineAccess access = (PortalMainPipelineAccess) manager;
        when(access.wormholes$mainPipelines()).thenReturn(pipelines);
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel source = mock(ClientLevel.class);
        ClientLevel destination = mock(ClientLevel.class);
        minecraft.level = source;
        try (MockedStatic<IrisPlatformHelpers> platform = mockStatic(IrisPlatformHelpers.class)) {
            IrisPlatformHelpers helpers = mock(IrisPlatformHelpers.class);
            platform.when(IrisPlatformHelpers::getInstance).thenReturn(helpers);
            try (MockedStatic<Iris> iris = mockStatic(Iris.class);
                 MockedStatic<Minecraft> clients = mockStatic(Minecraft.class);
                 MockedStatic<RenderSystem> rendering = mockStatic(RenderSystem.class);
                 MockedStatic<PortalIrisSettings> snapshots = mockStatic(PortalIrisSettings.class)) {
                clients.when(Minecraft::getInstance).thenReturn(minecraft);
                iris.when(Iris::getCurrentPack).thenReturn(Optional.of(pack));
                iris.when(Iris::getPipelineManager).thenReturn(manager);
                iris.when(Iris::getCurrentDimension).thenAnswer(invocation -> {
                    assertSame(destination, minecraft.level);
                    return dimension;
                });
                snapshots.when(PortalIrisSettings::capture).thenReturn(currentSettings);
                assertFalse(PortalIrisMainPipelines.ready(destination));
                assertTrue(PortalIrisMainPipelines.authoritativeTerrainCompatible(destination));
                assertSame(source, minecraft.level);
                try (PortalIrisMainPipelines.Handoff scope = PortalIrisMainPipelines.authoritativeHandoff(destination)) {
                    assertSame(source, minecraft.level);
                    minecraft.level = destination;
                    assertTrue(PortalIrisMainPipelines.retainDimensionChange());
                    verify(access).wormholes$advanceMainVersion();
                }
                assertFalse(PortalIrisMainPipelines.retainDimensionChange());
                minecraft.level = source;
                ShaderPack changedPack = mock(ShaderPack.class);
                iris.when(Iris::getCurrentPack).thenReturn(Optional.of(changedPack));
                assertFalse(PortalIrisMainPipelines.authoritativeTerrainCompatible(destination));
                try (PortalIrisMainPipelines.Handoff scope = PortalIrisMainPipelines.authoritativeHandoff(destination)) {
                    minecraft.level = destination;
                    assertFalse(PortalIrisMainPipelines.retainDimensionChange());
                }
                minecraft.level = source;
                IllegalStateException expected = new IllegalStateException("dimension unavailable");
                iris.when(Iris::getCurrentDimension).thenThrow(expected);
                assertSame(expected, assertThrows(IllegalStateException.class,
                    () -> PortalIrisMainPipelines.authoritativeTerrainCompatible(destination)));
                assertSame(source, minecraft.level);
            }
        } finally {
            histories.remove(pipeline);
            settings.remove(pipeline);
        }
    }

    @SuppressWarnings("unchecked")
    private static WorldRenderingPipeline captureWithPack(ShaderPack pack, IrisRenderingPipeline pipeline) throws ReflectiveOperationException {
        Field field = PortalIrisMainPipelines.class.getDeclaredField("HISTORIES");
        field.setAccessible(true);
        Map<IrisRenderingPipeline, PortalIrisMainPipelines.Entry> histories =
            (Map<IrisRenderingPipeline, PortalIrisMainPipelines.Entry>) field.get(null);
        try (MockedStatic<IrisPlatformHelpers> platform = mockStatic(IrisPlatformHelpers.class)) {
            platform.when(IrisPlatformHelpers::getInstance).thenReturn(mock(IrisPlatformHelpers.class));
            try (MockedStatic<Iris> iris = mockStatic(Iris.class);
                 MockedStatic<PortalIrisSettings> snapshots = mockStatic(PortalIrisSettings.class)) {
                iris.when(Iris::getCurrentPack).thenReturn(Optional.of(pack));
                snapshots.when(PortalIrisSettings::capture).thenReturn(mock(PortalIrisSettings.class));
                return PortalIrisMainPipelines.capture(() -> pipeline);
            }
        } finally {
            histories.remove(pipeline);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertSelectedReload(boolean compatible, boolean pendingReload, boolean expectedReload) throws ReflectiveOperationException {
        Field field = PortalIrisMainPipelines.class.getDeclaredField("SETTINGS");
        field.setAccessible(true);
        Map<WorldRenderingPipeline, PortalIrisSettings> settings = (Map<WorldRenderingPipeline, PortalIrisSettings>) field.get(null);
        IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class);
        PortalIrisSettings previous = mock(PortalIrisSettings.class);
        PortalIrisSettings destination = mock(PortalIrisSettings.class);
        when(destination.terrainCompatible(previous)).thenReturn(compatible);
        settings.put(pipeline, destination);
        Minecraft minecraft = mock(Minecraft.class);
        LevelExtractor extractor = mock(LevelExtractor.class);
        Field extractorField = Minecraft.class.getDeclaredField("levelExtractor");
        extractorField.setAccessible(true);
        extractorField.set(minecraft, extractor);
        Field reload = WorldRenderingSettings.class.getDeclaredField("reloadRequired");
        reload.setAccessible(true);
        boolean original = reload.getBoolean(WorldRenderingSettings.INSTANCE);
        reload.setBoolean(WorldRenderingSettings.INSTANCE, pendingReload);
        try (MockedStatic<Minecraft> clients = mockStatic(Minecraft.class);
             MockedStatic<PortalIrisSettings> snapshots = mockStatic(PortalIrisSettings.class)) {
            clients.when(Minecraft::getInstance).thenReturn(minecraft);
            snapshots.when(PortalIrisSettings::capture).thenReturn(previous);
            PortalIrisMainPipelines.selected(null, pipeline);
            verify(destination).apply();
            verify(extractor, times(expectedReload ? 1 : 0)).allChanged();
            if (expectedReload) {
                InOrder order = inOrder(destination, extractor);
                order.verify(destination).apply();
                order.verify(extractor).allChanged();
            }
            assertFalse(WorldRenderingSettings.INSTANCE.isReloadRequired());
            assertSame(previous, settings.get(pipeline));
        } finally {
            settings.remove(pipeline);
            reload.setBoolean(WorldRenderingSettings.INSTANCE, original);
        }
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
