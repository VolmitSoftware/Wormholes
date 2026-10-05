package art.arcane.wormholes.modded.client.render;

import org.junit.Test;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.lang.reflect.Constructor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;

public class PortalIrisPoolTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    public void idleReusePreservesReadinessAreaBytesAndStableTieOrder() throws ReflectiveOperationException {
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> mock(ShaderPack.class));
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        Object unready = rankedEntry(200, 2048, 2048, false);
        Object smaller = rankedEntry(100, 512, 512, true);
        Object fewerBytes = rankedEntry(100, 1024, 1024, true);
        Object firstTie = rankedEntry(200, 1024, 1024, true);
        Object secondTie = rankedEntry(200, 1024, 1024, true);
        Object otherDimension = rankedEntry(300, 4096, 4096, true);
        field(otherDimension.getClass(), "dimension").set(otherDimension, new NamespacedId("minecraft:the_end"));
        idle(pool).addAll(List.of(unready, smaller, fewerBytes, firstTie, secondTie, otherDimension));
        Method reusable = PortalIrisRenderer.class.getDeclaredMethod("reusable", NamespacedId.class);
        reusable.setAccessible(true);
        for (Object expected : List.of(firstTie, secondTie, fewerBytes, smaller, unready)) {
            assertSame(expected, reusable.invoke(pool, dimension));
        }
        assertEquals(List.of(otherDimension), idle(pool));
    }

    @Test
    public void sourceReuseRequiresTheExactCapturedPackIdentity() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        assertFalse(pool.usesPack(pack));
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        assertTrue(pool.usesPack(pack));
        assertFalse(pool.usesPack(mock(ShaderPack.class)));
        assertFalse(pool.usesPack(null));
        pool.close();
        assertFalse(pool.usesPack(pack));
    }

    @Test
    public void reservationEvictsReleasedViewsWhilePreservingKeyedHistoryAndActiveCapture() throws ReflectiveOperationException {
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> mock(ShaderPack.class));
        pool.beginFrame();
        Map<Integer, Object> entries = entries(pool);
        Object active = entry(600 * MIB, 1);
        Object current = entry(600 * MIB, 0);
        Object older = entry(1800 * MIB, 0);
        entries.put(1, active);
        entries.put(2, current);
        idle(pool).add(older);
        field(PortalIrisRenderer.class, "reserved").setLong(pool, 3000 * MIB);
        reserve(pool);
        assertEquals(1200 * MIB, pool.bytes());
        assertTrue(entries.containsKey(1));
        assertTrue(entries.containsKey(2));
        assertTrue(idle(pool).isEmpty());
        closeMethod(older).invoke(verify(older));
        closeMethod(active).invoke(verify(active, never()));
        closeMethod(current).invoke(verify(current, never()));
    }

    @Test
    public void cacheTargetNeverReclaimsOrRejectsActiveViews() throws ReflectiveOperationException {
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> mock(ShaderPack.class));
        Object active = entry(1500 * MIB, 1);
        entries(pool).put(1, active);
        field(PortalIrisRenderer.class, "reserved").setLong(pool, 1500 * MIB);
        reserve(pool);
        assertSame(active, entries(pool).get(1));
        assertEquals(1500 * MIB, pool.bytes());
        closeMethod(active).invoke(verify(active, never()));
    }

    @Test
    public void authoritativeOffscreenViewsDoNotConsumeTheReleasedCacheTarget() throws ReflectiveOperationException {
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> mock(ShaderPack.class));
        pool.beginFrame();
        Object active = entry(2000 * MIB, 1);
        Object cached = entry(2000 * MIB, 0);
        entries(pool).put(1, active);
        entries(pool).put(2, cached);
        field(PortalIrisRenderer.class, "reserved").setLong(pool, 4000 * MIB);
        reserve(pool);
        assertEquals(4000 * MIB, pool.bytes());
        assertSame(cached, entries(pool).get(2));
        closeMethod(cached).invoke(verify(cached, never()));
    }

    @Test
    public void evictionPinsSharedShadowsUntilIncomingAllocationHasItsOwnReference() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        pool.beginFrame();
        PortalSharedShadows targets = mock(PortalSharedShadows.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        Object shadow = shadow(targets, 50 * MIB, 1);
        shared(pool).put(dimension, shadow);
        Object older = entry(1600 * MIB, 0);
        field(older.getClass(), "shadows").set(older, shadow);
        idle(pool).add(older);
        field(PortalIrisRenderer.class, "reserved").setLong(pool, 1650 * MIB);
        ProgramSet programs = mock(ProgramSet.class);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        try (MockedStatic<PortalIrisPipeline> pipelines = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class);
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            pipelines.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            resources.when(() -> PortalIrisResources.targets(programs, 512, 256)).thenReturn(100 * MIB);
            resources.when(() -> PortalIrisResources.shareShadows(programs)).thenReturn(true);
            Method create = PortalIrisRenderer.class.getDeclaredMethod("create", int.class, NamespacedId.class, int.class, int.class);
            create.setAccessible(true);
            entries(pool).put(2, create.invoke(pool, 2, dimension, 512, 256));
            assertEquals(150 * MIB, pool.bytes());
            assertSame(shadow, shared(pool).get(dimension));
            assertEquals(1, field(shadow.getClass(), "users").getInt(shadow));
            verify(targets, never()).close();
            pool.remove(2);
            assertEquals(150 * MIB, pool.bytes());
            verify(targets, never()).close();
            pool.close();
            assertEquals(0, pool.bytes());
            assertTrue(shared(pool).isEmpty());
            verify(targets).close();
            verify(textures.constructed().getFirst()).destroyBuffers();
        }
    }

    @Test
    public void allocationFailureRollsBackNewSharedReservation() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ProgramSet programs = mock(ProgramSet.class);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        try (MockedStatic<PortalIrisPipeline> pipelines = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<PortalSharedShadows> shadows = mockConstruction(PortalSharedShadows.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class, (target, context) -> {
                 throw new IllegalStateException("GPU allocation failed");
             });
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            pipelines.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            resources.when(() -> PortalIrisResources.targets(programs, 512, 256)).thenReturn(100 * MIB);
            resources.when(() -> PortalIrisResources.shadows(programs)).thenReturn(50 * MIB);
            resources.when(() -> PortalIrisResources.shareShadows(programs)).thenReturn(true);
            assertThrows(RuntimeException.class, () -> pool.acquire(1, environment, 512, 256));
            assertEquals(0, pool.bytes());
            assertTrue(entries(pool).isEmpty());
            assertTrue(shared(pool).isEmpty());
            verify(shadows.constructed().getFirst()).close();
        }
    }

    @Test
    public void shutdownReleasesRemainingEntriesAndAccountingAfterOneCloseFails() throws ReflectiveOperationException {
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> mock(ShaderPack.class));
        Object broken = entry(100 * MIB, 0);
        Object remaining = entry(200 * MIB, 0);
        closeMethod(broken).invoke(doThrow(new IllegalStateException("close failed")).when(broken));
        entries(pool).put(1, broken);
        entries(pool).put(2, remaining);
        field(PortalIrisRenderer.class, "reserved").setLong(pool, 300 * MIB);
        assertThrows(IllegalStateException.class, pool::close);
        assertEquals(0, pool.bytes());
        assertTrue(entries(pool).isEmpty());
        closeMethod(remaining).invoke(verify(remaining));
    }

    @Test
    public void warmupBuildsOnePipelinePerFrameAndReusesAlreadyReadySessions() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        PortalShaderContext.View view = mock(PortalShaderContext.View.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        ProgramSet programs = mock(ProgramSet.class);
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        PortalTerrainMaterials materials = new PortalTerrainMaterials(true, Map.of(), 1, PortalTerrainMaterials.Lighting.VANILLA);
        try (MockedStatic<PortalIrisPipeline> dimensions = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class);
             MockedConstruction<PortalIrisPipeline> pipelines = mockConstruction(PortalIrisPipeline.class, (pipeline, context) -> {
                 AtomicBoolean ready = new AtomicBoolean();
                 when(pipeline.materials()).thenReturn(materials);
                 when(pipeline.ready()).thenAnswer(call -> ready.get());
                 when(pipeline.warm(any())).thenAnswer(call -> {
                     ready.set(true);
                     return true;
                 });
             });
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            dimensions.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            pool.beginFrame();
            PortalShaderRenderer.Session first = pool.acquire(1, environment, 512, 256);
            PortalShaderRenderer.Session second = pool.acquire(2, environment, 512, 256);
            assertFalse(first.ready());
            assertSame(PortalTerrainMaterials.VANILLA, first.materials());
            assertThrows(IllegalStateException.class, () -> first.begin(view));
            assertFalse(first.warm(view));
            assertFalse(first.ready());
            assertSame(materials, first.materials());
            assertThrows(IllegalStateException.class, () -> first.begin(view));
            assertFalse(second.warm(view));
            assertFalse(second.ready());
            assertEquals(1, pipelines.constructed().size());
            pool.beginFrame();
            assertTrue(first.warm(view));
            assertTrue(first.ready());
            assertSame(materials, first.materials());
            assertTrue(first.warm(view));
            assertFalse(second.warm(view));
            pool.beginFrame();
            assertFalse(second.warm(view));
            assertFalse(second.ready());
            assertEquals(2, pipelines.constructed().size());
            pool.beginFrame();
            assertTrue(second.warm(view));
            assertTrue(second.ready());
            pool.close();
            for (PortalIrisPipeline pipeline : pipelines.constructed()) {
                verify(pipeline).close();
            }
            for (TextureTarget target : textures.constructed()) {
                verify(target).destroyBuffers();
            }
            assertEquals(0, pool.bytes());
        }
    }

    @Test
    public void steadyViewsAndResolutionChangesRetainTheirLinkedPipeline() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        ClientViewEnvironment.World world = mock(ClientViewEnvironment.World.class);
        when(environment.world()).thenReturn(world);
        when(world.dimensionKey()).thenReturn("minecraft:overworld");
        PortalShaderContext.View view = mock(PortalShaderContext.View.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        ProgramSet programs = mock(ProgramSet.class);
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        try (MockedStatic<PortalIrisPipeline> dimensions = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class, (target, context) -> {
                 target.width = (int) context.arguments().get(1);
                 target.height = (int) context.arguments().get(2);
                 doAnswer(call -> {
                     target.width = call.getArgument(0);
                     target.height = call.getArgument(1);
                     return null;
                 }).when(target).resize(anyInt(), anyInt());
             });
             MockedConstruction<PortalIrisPipeline> pipelines = mockConstruction(PortalIrisPipeline.class, (pipeline, context) -> {
                 AtomicBoolean ready = new AtomicBoolean();
                 when(pipeline.ready()).thenAnswer(call -> ready.get());
                 when(pipeline.warm(any())).thenAnswer(call -> {
                     ready.set(true);
                     return true;
                 });
             });
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            dimensions.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            resources.when(() -> PortalIrisResources.targets(programs, 512, 256)).thenReturn(10 * MIB);
            resources.when(() -> PortalIrisResources.targets(programs, 1024, 512)).thenReturn(40 * MIB);
            pool.beginFrame();
            PortalShaderRenderer.Session session = pool.acquire(1, environment, 512, 256);
            assertFalse(session.warm(view));
            pool.beginFrame();
            assertTrue(session.warm(view));
            for (int frame = 0; frame < 120; frame++) {
                pool.beginFrame();
                assertSame(session, pool.acquire(1, environment, 512, 256));
                assertTrue(session.warm(view));
            }
            assertSame(session, pool.acquire(1, environment, 1024, 512));
            assertTrue(session.ready());
            assertEquals(1, pipelines.constructed().size());
            assertEquals(40 * MIB, pool.bytes());
            verify(textures.constructed().getFirst()).resize(1024, 512);
            verify(pipelines.constructed().getFirst()).resize();
            doThrow(new IllegalStateException("MRT resize failed")).when(pipelines.constructed().getFirst()).resize();
            assertThrows(IllegalStateException.class, () -> pool.acquire(1, environment, 512, 256));
            assertEquals(40 * MIB, pool.bytes());
            pool.close();
            assertEquals(0, pool.bytes());
            verify(pipelines.constructed().getFirst()).close();
        }
    }

    @Test
    public void pendingResizeAndReopeningRetainTheShaderQueue() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        ClientViewEnvironment.World world = mock(ClientViewEnvironment.World.class);
        when(environment.world()).thenReturn(world);
        when(world.dimensionKey()).thenReturn("minecraft:overworld");
        PortalShaderContext.View view = mock(PortalShaderContext.View.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        ProgramSet programs = mock(ProgramSet.class);
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        try (MockedStatic<PortalIrisPipeline> dimensions = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class, (target, context) -> {
                 target.width = (int) context.arguments().get(1);
                 target.height = (int) context.arguments().get(2);
                 doAnswer(call -> {
                     target.width = call.getArgument(0);
                     target.height = call.getArgument(1);
                     return null;
                 }).when(target).resize(anyInt(), anyInt());
             });
             MockedConstruction<PortalIrisPipeline> pipelines = mockConstruction(PortalIrisPipeline.class);
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            dimensions.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            resources.when(() -> PortalIrisResources.targets(programs, 1024, 512)).thenReturn(40 * MIB);
            resources.when(() -> PortalIrisResources.targets(programs, 512, 256)).thenReturn(10 * MIB);
            pool.beginFrame();
            PortalShaderRenderer.Session pending = pool.acquire(1, environment, 1024, 512);
            assertFalse(pending.warm(view));
            PortalIrisPipeline original = pipelines.constructed().getFirst();
            assertSame(pending, pool.acquire(1, environment, 512, 256));
            assertEquals(10 * MIB, pool.bytes());
            verify(original).resize();
            assertFalse(pending.ready());
            assertThrows(IllegalStateException.class, () -> pending.begin(view));
            pool.beginFrame();
            assertFalse(pending.warm(view));
            verify(original).warm(view);
            assertEquals(1, pipelines.constructed().size());
            pool.remove(1);
            verify(original, never()).close();
            assertEquals(10 * MIB, pool.bytes());
            pool.beginFrame();
            PortalShaderRenderer.Session replacement = pool.acquire(1, environment, 512, 256);
            assertSame(pending, replacement);
            assertFalse(replacement.warm(view));
            assertEquals(1, pipelines.constructed().size());
            pool.close();
            verify(original).close();
            for (TextureTarget target : textures.constructed()) {
                verify(target).destroyBuffers();
            }
            assertEquals(0, pool.bytes());
        }
    }

    @Test
    public void failedWarmupConsumesTheFramesConstructionAllowance() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        PortalShaderContext.View view = mock(PortalShaderContext.View.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        when(pack.getProgramSet(dimension)).thenReturn(mock(ProgramSet.class));
        field(PortalIrisRenderer.class, "pack").set(pool, pack);
        try (MockedStatic<PortalIrisPipeline> dimensions = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class);
             MockedConstruction<PortalIrisPipeline> pipelines = mockConstruction(PortalIrisPipeline.class, (pipeline, context) -> {
                 throw new IllegalStateException("Shader link failed");
             })) {
            dimensions.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            pool.beginFrame();
            PortalShaderRenderer.Session failed = pool.acquire(1, environment, 512, 256);
            PortalShaderRenderer.Session pending = pool.acquire(2, environment, 512, 256);
            assertThrows(RuntimeException.class, () -> failed.warm(view));
            assertFalse(failed.ready());
            assertFalse(pending.warm(view));
            assertFalse(pending.ready());
        }
    }

    @Test
    public void unusedRendererAllocatesNothingAndOnDemandViewsHaveNoStartingCountLimit() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ProgramSet programs = mock(ProgramSet.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        try (MockedStatic<PortalIrisPipeline> dimensions = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class);
             MockedConstruction<PortalIrisPipeline> pipelines = mockConstruction(PortalIrisPipeline.class, (pipeline, context) -> {
                 AtomicBoolean ready = new AtomicBoolean();
                 when(pipeline.ready()).thenAnswer(call -> ready.get());
                 when(pipeline.warm(any())).thenAnswer(call -> {
                     ready.set(true);
                     return true;
                 });
             });
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            dimensions.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            assertEquals(0, pipelines.constructed().size());
            assertEquals(0, textures.constructed().size());
            PortalShaderRenderer.Session[] sessions = new PortalShaderRenderer.Session[12];
            pool.beginFrame();
            for (int key = 1; key <= 12; key++) {
                sessions[key - 1] = pool.acquire(key, environment, 512, 256);
                assertFalse(sessions[key - 1].ready());
            }
            assertEquals(12, entries(pool).size());
            assertEquals(0, pipelines.constructed().size());
            PortalShaderContext.View view = mock(PortalShaderContext.View.class);
            for (int frame = 0; frame < 24; frame++) {
                pool.beginFrame();
                for (int key = 1; key <= 12; key++) {
                    assertSame(sessions[key - 1], pool.acquire(key, environment, 512, 256));
                    sessions[key - 1].warm(view);
                }
            }
            assertEquals(12, pipelines.constructed().size());
            for (PortalShaderRenderer.Session session : sessions) {
                assertTrue(session.ready());
            }
            pool.remove(1);
            assertSame(sessions[0], pool.acquire(99, environment, 512, 256));
            assertEquals(12, pipelines.constructed().size());
            verify(pipelines.constructed().getFirst()).released();
            pool.close();
            for (PortalIrisPipeline pipeline : pipelines.constructed()) {
                verify(pipeline).close();
            }
        }
    }

    @Test
    public void reopeningReusesFullSizeSlotBeforeDormantSparesAndBudgetsTheSameSlot() throws ReflectiveOperationException {
        ShaderPack pack = mock(ShaderPack.class);
        PortalIrisRenderer pool = new PortalIrisRenderer(() -> pack);
        ProgramSet programs = mock(ProgramSet.class);
        NamespacedId dimension = new NamespacedId("minecraft:overworld");
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        when(pack.getProgramSet(dimension)).thenReturn(programs);
        try (MockedStatic<PortalIrisPipeline> dimensions = mockStatic(PortalIrisPipeline.class);
             MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class, (target, context) -> {
                 target.width = (int) context.arguments().get(1);
                 target.height = (int) context.arguments().get(2);
                 doAnswer(call -> {
                     target.width = call.getArgument(0);
                     target.height = call.getArgument(1);
                     return null;
                 }).when(target).resize(anyInt(), anyInt());
             });
             MockedConstruction<PortalIrisPipeline> pipelines = mockConstruction(PortalIrisPipeline.class, (pipeline, context) -> {
                 when(pipeline.ready()).thenReturn(true);
             });
             MockedConstruction<PortalTextureScope> textureScopes = mockConstruction(PortalTextureScope.class);
             MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class)) {
            dimensions.when(() -> PortalIrisPipeline.dimension(pack, environment)).thenReturn(dimension);
            resources.when(() -> PortalIrisResources.targets(programs, 16, 16)).thenReturn(MIB);
            resources.when(() -> PortalIrisResources.targets(programs, 1920, 1080)).thenReturn(100 * MIB);
            PortalShaderContext.View view = mock(PortalShaderContext.View.class);
            pool.beginFrame();
            PortalShaderRenderer.Session smaller = pool.acquire(50, environment, 16, 16);
            assertTrue(smaller.warm(view));
            PortalShaderRenderer.Session original = pool.acquire(1, environment, 1920, 1080);
            pool.beginFrame();
            pool.acquire(50, environment, 16, 16);
            assertTrue(original.warm(view));
            pool.remove(50);
            pool.remove(1);
            Method retained = PortalIrisRenderer.class.getDeclaredMethod("retained", Map.class, List.class);
            retained.setAccessible(true);
            Map<NamespacedId, PortalIrisResolution.Dimension> demand = Map.of(dimension,
                new PortalIrisResolution.Dimension(programs, List.of(0)));
            assertEquals(MIB, ((Long) retained.invoke(pool, demand,
                List.of(new PortalShaderRenderer.DemandView(2, environment, 0)))).longValue());
            for (int key = 2; key <= 10; key++) {
                pool.beginFrame();
                assertSame(original, pool.acquire(key, environment, 1920, 1080));
                pool.remove(key);
            }
            assertSame(original, pool.acquire(11, environment, 1920, 1080));
            for (int frame = 0; frame < 120; frame++) {
                pool.beginFrame();
            }
            assertSame(original, pool.acquire(11, environment, 1920, 1080));
            pool.beginFrame();
            PortalShaderRenderer.Session additional = pool.acquire(12, environment, 1920, 1080);
            assertFalse(original == additional);
            assertTrue(entries(pool).containsKey(11));
            assertSame(original, pool.acquire(11, environment, 1920, 1080));
            assertEquals(2, pipelines.constructed().size());
            assertEquals(200 * MIB, pool.bytes());
            verify(original.target(), never()).resize(anyInt(), anyInt());
            verify(pipelines.constructed().get(1), never()).resize();
            PortalIrisPipeline retainedPipeline = pipelines.constructed().get(1);
            clearInvocations(retainedPipeline);
            pool.resetHistory(11);
            assertSame(original, pool.acquire(11, environment, 1920, 1080));
            verify(retainedPipeline).released();
            verify(retainedPipeline, never()).close();
            verify(retainedPipeline, never()).resize();
            pool.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> idle(PortalIrisRenderer pool) throws ReflectiveOperationException {
        return (List<Object>) field(PortalIrisRenderer.class, "idle").get(pool);
    }

    @SuppressWarnings("unchecked")
    private static Map<NamespacedId, Object> shared(PortalIrisRenderer pool) throws ReflectiveOperationException {
        return (Map<NamespacedId, Object>) field(PortalIrisRenderer.class, "shared").get(pool);
    }

    private static Object shadow(PortalSharedShadows targets, long bytes, int users) throws ReflectiveOperationException {
        Class<?> type = Class.forName(PortalIrisRenderer.class.getName() + "$Shared");
        Constructor<?> constructor = type.getDeclaredConstructor(PortalSharedShadows.class, long.class);
        constructor.setAccessible(true);
        Object shadow = constructor.newInstance(targets, bytes);
        field(type, "users").setInt(shadow, users);
        return shadow;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> entries(PortalIrisRenderer pool) throws ReflectiveOperationException {
        return (Map<Integer, Object>) field(PortalIrisRenderer.class, "entries").get(pool);
    }

    private static Object rankedEntry(long bytes, int width, int height, boolean ready) throws ReflectiveOperationException {
        Object value = entry(bytes, 0);
        TextureTarget target = mock(TextureTarget.class);
        target.width = width;
        target.height = height;
        field(value.getClass(), "target").set(value, target);
        when(((PortalShaderRenderer.Session) value).ready()).thenReturn(ready);
        return value;
    }

    private static Object entry(long bytes, int active) throws ReflectiveOperationException {
        Class<?> type = Class.forName(PortalIrisRenderer.class.getName() + "$Entry");
        Object entry = mock(type);
        field(type, "dimension").set(entry, new NamespacedId("minecraft:overworld"));
        field(type, "bytes").setLong(entry, bytes);
        field(type, "active").setInt(entry, active);
        return entry;
    }

    private static void reserve(PortalIrisRenderer pool) throws ReflectiveOperationException {
        Method reserve = PortalIrisRenderer.class.getDeclaredMethod("reserve");
        reserve.setAccessible(true);
        reserve.invoke(pool);
    }

    private static Method closeMethod(Object entry) throws ReflectiveOperationException {
        Method close = entry.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        return close;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

}
