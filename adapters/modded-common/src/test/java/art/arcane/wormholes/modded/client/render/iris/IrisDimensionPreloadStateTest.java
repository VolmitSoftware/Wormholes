package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.PortalFramebufferScope;
import art.arcane.wormholes.modded.client.render.PortalTextureScope;
import art.arcane.wormholes.modded.mixin.client.IrisPortalPipelineAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalStorageBufferAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalStorageBuffersAccess;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ServiceLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class IrisDimensionPreloadStateTest extends MinecraftTestBase {
    @Test
    public void vanillaFallbackIsReportedAsFailureInsteadOfReady() {
        List<LogEvent> events = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("iris-preload-test", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        Logger logger = (Logger) LogManager.getLogger(IrisPipelines.class);
        logger.addAppender(appender);
        try (Scene scene = new Scene()) {
            when(scene.manager.preparePipeline(any())).thenReturn(mock(VanillaRenderingPipeline.class));
            IrisPipelines.create("minecraft:the_nether");
            scene.assertRestored();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        assertEquals(1, events.size());
        assertEquals(Level.ERROR, events.getFirst().getLevel());
        assertNotNull(events.getFirst().getThrown());
    }

    @Test
    public void successfulNonCurrentPreloadRestoresTheSelectedCameraAndEveryCapturedState() {
        try (Scene scene = new Scene()) {
            scene.prepare(null);
            IrisPipelines.create("minecraft:the_nether");
            scene.assertRestored();
            assertEquals(List.of(scene.mainBuffer, scene.destinationBuffer), scene.activeBuffers);
            verify((IrisPortalStorageBufferAccess) scene.destinationBuffer, never()).wormholes$destroy();
            verify((IrisPortalRenderingAccess) scene.destination).wormholes$initializedBlockIds(true);
            scene.uniforms.verify(ProgramUniforms::clearActiveUniforms);
            scene.samplers.verify(ProgramSamplers::clearActiveSamplers);
        }
    }

    @Test
    public void failedNonCurrentPreloadRestoresStateAfterPartialPipelineInitialization() {
        try (Scene scene = new Scene()) {
            scene.prepare(new IllegalStateException("shader compile failed"));
            IrisPipelines.create("minecraft:the_nether");
            scene.assertRestored();
            assertEquals(List.of(scene.mainBuffer), scene.activeBuffers);
            verify((IrisPortalStorageBufferAccess) scene.destinationBuffer).wormholes$destroy();
            verify((IrisPortalStorageBufferAccess) scene.mainBuffer, never()).wormholes$destroy();
        }
    }

    @Test
    public void optionalCompileFailureDoesNotEnterTheGlobalIrisFallbackHandler() {
        try (Scene scene = new Scene()) {
            AtomicBoolean globalFallback = new AtomicBoolean();
            RuntimeException failure = new IllegalStateException("cloud program compile failed");
            when(scene.manager.preparePipeline(any())).thenAnswer(invocation -> {
                scene.activeBuffers.add(scene.destinationBuffer);
                IrisPipelines.compilationFailed(failure);
                globalFallback.set(true);
                scene.activeBuffers.clear();
                return mock(VanillaRenderingPipeline.class);
            });
            IrisPipelines.create("minecraft:the_nether");
            assertFalse(globalFallback.get());
            assertEquals(List.of(scene.mainBuffer), scene.activeBuffers);
            verify((IrisPortalStorageBufferAccess) scene.destinationBuffer).wormholes$destroy();
            scene.assertRestored();
            IrisPipelines.compilationFailed(failure);
        }
    }

    @Test
    public void activeDimensionKeepsTheNormalIrisFallbackHandler() {
        try (Scene scene = new Scene()) {
            AtomicBoolean globalFallback = new AtomicBoolean();
            VanillaRenderingPipeline fallback = mock(VanillaRenderingPipeline.class);
            when(scene.manager.preparePipeline(any())).thenAnswer(invocation -> {
                IrisPipelines.compilationFailed(new IllegalStateException("active shader failed"));
                globalFallback.set(true);
                scene.selected.set(fallback);
                return fallback;
            });
            IrisPipelines.create("minecraft:overworld");
            assertTrue(globalFallback.get());
            assertSame(fallback, scene.selected.get());
            scene.buffers.verifyNoInteractions();
        }
    }

    @Test
    public void fatalNonCurrentPreloadRestoresStateBeforePropagatingTheFailure() {
        try (Scene scene = new Scene()) {
            AssertionError failure = new AssertionError("shader allocation failed");
            scene.prepare(failure);
            assertSame(failure, assertThrows(AssertionError.class, () -> IrisPipelines.create("minecraft:the_nether")));
            scene.assertRestored();
            assertEquals(List.of(scene.mainBuffer), scene.activeBuffers);
            verify((IrisPortalStorageBufferAccess) scene.destinationBuffer).wormholes$destroy();
            IrisPipelines.compilationFailed(new IllegalStateException("outside preloading"));
        }
    }

    @Test
    public void currentDimensionCreationKeepsItsNewSelectionAndSettingsWithoutCapturingThem() {
        try (Scene scene = new Scene()) {
            scene.prepare(null);
            IrisPipelines.create("minecraft:overworld");
            assertSame(scene.destination, scene.selected.get());
            assertEquals("destination", scene.world.get());
            assertEquals("destination", scene.captured.get());
            assertEquals(21, scene.storageBinding.get());
            assertEquals(22, scene.framebufferBinding.get());
            assertEquals(0, scene.textures.constructed().size());
            scene.settings.verifyNoInteractions();
            scene.states.verifyNoInteractions();
            scene.framebuffers.verifyNoInteractions();
            scene.uniforms.verifyNoInteractions();
            scene.samplers.verifyNoInteractions();
        }
    }

    @Test
    public void nonCurrentPreloadPreservesAnInitiallyUnselectedPipeline() {
        try (Scene scene = new Scene()) {
            scene.selected.set(null);
            scene.prepare(null);
            IrisPipelines.create("minecraft:the_end");
            assertSame(null, scene.selected.get());
            assertEquals("main", scene.world.get());
            assertEquals("main", scene.captured.get());
            assertEquals(11, scene.storageBinding.get());
            assertEquals(12, scene.framebufferBinding.get());
        }
    }

    private static final class Scene implements AutoCloseable {
        private final PipelineManager manager = mock(PipelineManager.class, withSettings().extraInterfaces(IrisPortalPipelineAccess.class));
        private final WorldRenderingPipeline main = mock(WorldRenderingPipeline.class);
        private final IrisRenderingPipeline destination = mock(IrisRenderingPipeline.class,
            withSettings().extraInterfaces(IrisPortalRenderingAccess.class));
        private final ShaderStorageBuffer mainBuffer = mock(ShaderStorageBuffer.class,
            withSettings().extraInterfaces(IrisPortalStorageBufferAccess.class));
        private final ShaderStorageBuffer destinationBuffer = mock(ShaderStorageBuffer.class,
            withSettings().extraInterfaces(IrisPortalStorageBufferAccess.class));
        private final List<ShaderStorageBuffer> activeBuffers = new ArrayList<>(List.of(mainBuffer));
        private final AtomicReference<WorldRenderingPipeline> selected = new AtomicReference<>(main);
        private final AtomicReference<String> world = new AtomicReference<>("main");
        private final AtomicReference<String> captured = new AtomicReference<>("main");
        private final AtomicInteger storageBinding = new AtomicInteger(11);
        private final AtomicInteger framebufferBinding = new AtomicInteger(12);
        private final MockedStatic<IrisPlatformHelpers> platform = platform();
        private final MockedStatic<Iris> iris;
        private final MockedStatic<IrisWorldSettings> settings = mockStatic(IrisWorldSettings.class);
        private final MockedStatic<IrisCapturedState> states = mockStatic(IrisCapturedState.class);
        private final MockedStatic<PortalFramebufferScope> framebuffers = mockStatic(PortalFramebufferScope.class);
        private final MockedStatic<ProgramUniforms> uniforms = mockStatic(ProgramUniforms.class);
        private final MockedStatic<ProgramSamplers> samplers = mockStatic(ProgramSamplers.class);
        private final MockedStatic<IrisPortalStorageBuffersAccess> buffers = mockStatic(IrisPortalStorageBuffersAccess.class);
        private final MockedConstruction<PortalTextureScope> textures;

        private Scene() {
            platform.when(IrisPlatformHelpers::getInstance).thenReturn(mock(IrisPlatformHelpers.class));
            iris = mockStatic(Iris.class);
            iris.when(Iris::getPipelineManager).thenReturn(manager);
            iris.when(Iris::getCurrentDimension).thenReturn(new NamespacedId("minecraft:overworld"));
            buffers.when(IrisPortalStorageBuffersAccess::wormholes$activeBuffers).thenReturn(activeBuffers);
            doAnswer(invocation -> {
                storageBinding.set(0);
                return null;
            }).when((IrisPortalStorageBufferAccess) destinationBuffer).wormholes$destroy();
            when(manager.getPipelineNullable()).thenAnswer(invocation -> selected.get());
            doAnswer(invocation -> {
                selected.set(invocation.getArgument(0));
                return null;
            }).when((IrisPortalPipelineAccess) manager).wormholes$pipeline(any());
            settings.when(IrisWorldSettings::capture).thenAnswer(invocation -> {
                String before = world.get();
                IrisWorldSettings snapshot = mock(IrisWorldSettings.class);
                doAnswer(restore -> {
                    world.set(before);
                    return null;
                }).when(snapshot).apply();
                return snapshot;
            });
            states.when(IrisCapturedState::capture).thenAnswer(invocation -> {
                String before = captured.get();
                IrisCapturedState snapshot = mock(IrisCapturedState.class);
                doAnswer(restore -> {
                    captured.set(before);
                    return null;
                }).when(snapshot).restore();
                return snapshot;
            });
            textures = mockConstruction(PortalTextureScope.class, (scope, context) -> {
                int before = storageBinding.get();
                doAnswer(invocation -> {
                    storageBinding.set(before);
                    return null;
                }).when(scope).close();
            });
            framebuffers.when(PortalFramebufferScope::capture).thenAnswer(invocation -> {
                int before = framebufferBinding.get();
                PortalFramebufferScope scope = mock(PortalFramebufferScope.class);
                doAnswer(restore -> {
                    framebufferBinding.set(before);
                    return null;
                }).when(scope).close();
                return scope;
            });
        }

        private static MockedStatic<IrisPlatformHelpers> platform() {
            Class<? extends IrisPlatformHelpers> provider = ServiceLoader.load(IrisPlatformHelpers.class)
                .stream().findFirst().orElseThrow().type();
            try (MockedConstruction<? extends IrisPlatformHelpers> services = mockConstruction(provider)) {
                return mockStatic(IrisPlatformHelpers.class);
            }
        }

        private void prepare(Throwable failure) {
            when(manager.preparePipeline(any())).thenAnswer(invocation -> {
                selected.set(destination);
                world.set("destination");
                captured.set("destination");
                storageBinding.set(21);
                framebufferBinding.set(22);
                activeBuffers.add(destinationBuffer);
                if (failure != null) {
                    throw failure;
                }
                return destination;
            });
        }

        private void assertRestored() {
            assertSame(main, selected.get());
            assertEquals("main", world.get());
            assertEquals("main", captured.get());
            assertEquals(11, storageBinding.get());
            assertEquals(12, framebufferBinding.get());
            assertEquals(1, textures.constructed().size());
            verify(textures.constructed().getFirst()).close();
        }

        @Override
        public void close() {
            IrisPipelines.destroyed();
            buffers.close();
            textures.close();
            samplers.close();
            uniforms.close();
            framebuffers.close();
            states.close();
            settings.close();
            iris.close();
            platform.close();
        }
    }
}
