package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalHandAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalHandBuffersAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalHandFrameAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalHandProfilerAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderSystemAccess;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4fStack;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.ArgumentMatchers.same;

public class PortalIrisHandTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void throwingDrawRestoresSourceResourcesStacksAndConcurrentProfilerOwnership() throws Exception {
        Fixture fixture = new Fixture();
        ThreadLocal<ProfilerFiller> profiler = profiler();
        AtomicInteger count = count();
        ProfilerFiller prior = profiler.get();
        ProfilerFiller source = mock(ProfilerFiller.class);
        profiler.set(source);
        int sourceCount = count.incrementAndGet();
        AtomicBoolean otherOwner = new AtomicBoolean();
        try (MockedStatic<RenderSystem> rendering = mockStatic(RenderSystem.class);
             MockedStatic<IrisPortalRenderSystemAccess> system = mockStatic(IrisPortalRenderSystemAccess.class);
             MockedStatic<IrisPortalHandProfilerAccess> profiling = mockStatic(IrisPortalHandProfilerAccess.class)) {
            fixture.bind(rendering, system, profiling, profiler, count);
            assertThrows(IllegalStateException.class, () -> {
                try (PortalIrisHand.Frame frame = new PortalIrisHand.Frame(fixture.access, fixture.owned, fixture.warmStack)) {
                    assertSame(InactiveProfiler.INSTANCE, Profiler.get());
                    fixture.warmStack.pushMatrix().translate(1, 2, 3);
                    count.incrementAndGet();
                    otherOwner.set(true);
                    throw new IllegalStateException("native hand draw failed");
                }
            });
            assertSame(source, profiler.get());
            assertEquals(sourceCount + 1, count.get());
            fixture.verifyRestored(system, rendering);
            verify(fixture.frame).close();
            verify(fixture.owned.buffers()).endFrame();
            verify(fixture.source.buffers(), never()).endFrame();
        } finally {
            if (otherOwner.get()) {
                count.decrementAndGet();
            }
            count.decrementAndGet();
            restore(profiler, prior);
        }
    }

    @Test
    public void cleanupFailureStillRestoresSourceAndInactiveFramesAreNotClosedTwice() throws Exception {
        Fixture fixture = new Fixture();
        ThreadLocal<ProfilerFiller> profiler = profiler();
        AtomicInteger count = count();
        ProfilerFiller prior = profiler.get();
        int priorCount = count.get();
        try (MockedStatic<RenderSystem> rendering = mockStatic(RenderSystem.class);
             MockedStatic<IrisPortalRenderSystemAccess> system = mockStatic(IrisPortalRenderSystemAccess.class);
             MockedStatic<IrisPortalHandProfilerAccess> profiling = mockStatic(IrisPortalHandProfilerAccess.class)) {
            fixture.bind(rendering, system, profiling, profiler, count);
            doThrow(new IllegalStateException("prepared hand cleanup failed")).when(fixture.frame).close();
            PortalIrisHand.Frame frame = new PortalIrisHand.Frame(fixture.access, fixture.owned, fixture.warmStack);
            assertThrows(IllegalStateException.class, frame::close);
            assertSame(prior, profiler.get());
            assertEquals(priorCount, count.get());
            fixture.verifyRestored(system, rendering);
            verify(fixture.owned.buffers()).endFrame();

            when(((IrisPortalHandFrameAccess) fixture.frame).wormholes$context()).thenReturn(null);
            PortalIrisHand.Frame completed = new PortalIrisHand.Frame(fixture.access, fixture.owned, fixture.warmStack);
            Field ended = PortalIrisHand.Frame.class.getDeclaredField("ended");
            ended.setAccessible(true);
            ended.setBoolean(completed, true);
            completed.close();
            verify(fixture.frame).close();
            verify(fixture.owned.buffers()).endFrame();
            assertSame(prior, profiler.get());
            assertEquals(priorCount, count.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<ProfilerFiller> profiler() throws Exception {
        Field field = Profiler.class.getDeclaredField("ACTIVE");
        field.setAccessible(true);
        return (ThreadLocal<ProfilerFiller>) field.get(null);
    }

    private static AtomicInteger count() throws Exception {
        Field field = Profiler.class.getDeclaredField("ACTIVE_COUNT");
        field.setAccessible(true);
        return (AtomicInteger) field.get(null);
    }

    private static void restore(ThreadLocal<ProfilerFiller> profiler, ProfilerFiller prior) {
        if (prior == null) {
            profiler.remove();
        } else {
            profiler.set(prior);
        }
    }

    private static final class Fixture {
        private final IrisPortalHandAccess access = mock(IrisPortalHandAccess.class);
        private final FeatureRenderDispatcher dispatcher = mock(FeatureRenderDispatcher.class,
            withSettings().extraInterfaces(IrisPortalHandBuffersAccess.class));
        private final FeatureRenderDispatcher.PreparedFrame frame = mock(FeatureRenderDispatcher.PreparedFrame.class,
            withSettings().extraInterfaces(IrisPortalHandFrameAccess.class));
        private final PortalIrisHand.Context source = context(mock(FeatureRenderDispatcher.class), true, true);
        private final PortalIrisHand.Context owned = context(dispatcher, false, false);
        private final Matrix4fStack sourceStack = new Matrix4fStack(32);
        private final Matrix4fStack warmStack = new Matrix4fStack(32);
        private final GpuBufferSlice projection = mock(GpuBufferSlice.class);
        private final GpuBufferSlice saved = mock(GpuBufferSlice.class);
        private final FeatureFrameContext frameContext = mock(FeatureFrameContext.class);

        private Fixture() {
            when(access.wormholes$buffers()).thenReturn(source.buffers());
            when(access.wormholes$projectionBuffer()).thenReturn(source.projectionBuffer());
            when(access.wormholes$submits()).thenReturn(source.submits());
            when(access.wormholes$dispatcher()).thenReturn(source.dispatcher());
            when(access.wormholes$projection()).thenReturn(source.projection());
            when(access.wormholes$active()).thenReturn(source.active());
            when(access.wormholes$solid()).thenReturn(source.solid());
            when(((IrisPortalHandBuffersAccess) dispatcher).wormholes$frame()).thenReturn(frame);
            when(((IrisPortalHandFrameAccess) frame).wormholes$context()).thenReturn(frameContext);
        }

        private void bind(MockedStatic<RenderSystem> rendering, MockedStatic<IrisPortalRenderSystemAccess> system,
                          MockedStatic<IrisPortalHandProfilerAccess> profiling, ThreadLocal<ProfilerFiller> profiler,
                          AtomicInteger count) {
            rendering.when(RenderSystem::getModelViewStack).thenReturn(sourceStack);
            rendering.when(RenderSystem::getProjectionMatrixBuffer).thenReturn(projection);
            rendering.when(RenderSystem::getProjectionType).thenReturn(ProjectionType.PERSPECTIVE);
            system.when(IrisPortalRenderSystemAccess::wormholes$savedProjection).thenReturn(saved);
            system.when(IrisPortalRenderSystemAccess::wormholes$savedProjectionType).thenReturn(ProjectionType.ORTHOGRAPHIC);
            profiling.when(IrisPortalHandProfilerAccess::wormholes$active).thenReturn(profiler);
            profiling.when(IrisPortalHandProfilerAccess::wormholes$count).thenReturn(count);
        }

        private void verifyRestored(MockedStatic<IrisPortalRenderSystemAccess> system, MockedStatic<RenderSystem> rendering) {
            verify(access).wormholes$buffers(source.buffers());
            verify(access).wormholes$projectionBuffer(source.projectionBuffer());
            verify(access).wormholes$submits(source.submits());
            verify(access).wormholes$dispatcher(source.dispatcher());
            verify(access).wormholes$projection(source.projection());
            verify(access).wormholes$active(source.active());
            verify(access).wormholes$solid(source.solid());
            system.verify(() -> IrisPortalRenderSystemAccess.wormholes$modelView(same(sourceStack)));
            rendering.verify(() -> RenderSystem.setProjectionMatrix(projection, ProjectionType.PERSPECTIVE));
            system.verify(() -> IrisPortalRenderSystemAccess.wormholes$savedProjection(saved));
            system.verify(() -> IrisPortalRenderSystemAccess.wormholes$savedProjectionType(ProjectionType.ORTHOGRAPHIC));
            assertEquals(new Matrix4fStack(32), sourceStack);
        }

        private static PortalIrisHand.Context context(FeatureRenderDispatcher dispatcher, boolean active, boolean solid) {
            return new PortalIrisHand.Context(mock(RenderBuffers.class), mock(ProjectionMatrixBuffer.class),
                new SubmitNodeStorage(), dispatcher, new Projection(), active, solid);
        }
    }
}
