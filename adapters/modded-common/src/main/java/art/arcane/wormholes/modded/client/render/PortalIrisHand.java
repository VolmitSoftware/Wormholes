package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalHandAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalHandBuffersAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalHandFrameAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalHandProfilerAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderSystemAccess;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4fStack;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class PortalIrisHand implements AutoCloseable {
    private final Context context;
    private final Matrix4fStack modelView = new Matrix4fStack(32);

    PortalIrisHand() {
        Minecraft minecraft = Minecraft.getInstance();
        RenderBuffers buffers = new RenderBuffers(0);
        ProjectionMatrixBuffer projection = null;
        try {
            projection = new ProjectionMatrixBuffer("Wormholes prepared hand");
            context = new Context(buffers, projection, new SubmitNodeStorage(), new FeatureRenderDispatcher(buffers,
                minecraft.getModelManager(), minecraft.getAtlasManager(), minecraft.font, minecraft.gameRenderer.gameRenderState()),
                new Projection(), false, false);
        } catch (RuntimeException | Error failure) {
            try {
                if (projection != null) {
                    projection.close();
                }
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            try {
                closeResources(List.of(buffers.fixedBufferPack()::close, buffers::close));
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    boolean draw(IrisRenderingPipeline pipeline, CameraRenderState camera, boolean translucent) {
        HandRenderer renderer = HandRenderer.INSTANCE;
        IrisPortalHandAccess access = (IrisPortalHandAccess) renderer;
        if (access.wormholes$active()) {
            return false;
        }
        try (Frame frame = new Frame(access, context, modelView)) {
            Minecraft minecraft = Minecraft.getInstance();
            float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            pipeline.beginHand();
            if (translucent) {
                pipeline.beginTranslucents();
                renderer.renderTranslucent(camera.viewRotationMatrix, partialTick, minecraft.gameRenderer.mainCamera(),
                    camera, minecraft.gameRenderer, pipeline);
                frame.ended = true;
            } else {
                renderer.renderSolid(camera.viewRotationMatrix, partialTick, minecraft.gameRenderer.mainCamera(),
                    camera, minecraft.gameRenderer, pipeline);
            }
        }
        return true;
    }

    @Override
    public void close() {
        closeResources(List.of(() -> closeFrame(context),
            context.dispatcher()::close, context.projectionBuffer()::close,
            context.buffers().fixedBufferPack()::close, context.buffers()::close));
    }

    private static void closeFrame(Context context) {
        FeatureRenderDispatcher.PreparedFrame frame = ((IrisPortalHandBuffersAccess) context.dispatcher()).wormholes$frame();
        if (((IrisPortalHandFrameAccess) frame).wormholes$context() != null) {
            frame.close();
        }
    }

    private static void closeResources(List<Runnable> resources) {
        Throwable failure = null;
        for (Runnable resource : resources) {
            try {
                resource.run();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    record Context(RenderBuffers buffers, ProjectionMatrixBuffer projectionBuffer, SubmitNodeStorage submits,
                   FeatureRenderDispatcher dispatcher, Projection projection, boolean active, boolean solid) {
        static Context capture(IrisPortalHandAccess access) {
            return new Context(access.wormholes$buffers(), access.wormholes$projectionBuffer(), access.wormholes$submits(),
                access.wormholes$dispatcher(), access.wormholes$projection(), access.wormholes$active(), access.wormholes$solid());
        }

        void apply(IrisPortalHandAccess access) {
            access.wormholes$buffers(buffers);
            access.wormholes$projectionBuffer(projectionBuffer);
            access.wormholes$submits(submits);
            access.wormholes$dispatcher(dispatcher);
            access.wormholes$projection(projection);
            access.wormholes$active(active);
            access.wormholes$solid(solid);
        }
    }

    static final class Frame implements AutoCloseable {
        private final IrisPortalHandAccess access;
        private final Context previous;
        private final Context owned;
        private final Matrix4fStack previousModelView;
        private final GpuBufferSlice projection;
        private final ProjectionType projectionType;
        private final GpuBufferSlice savedProjection;
        private final ProjectionType savedProjectionType;
        private final ThreadLocal<ProfilerFiller> profiler;
        private final ProfilerFiller previousProfiler;
        private final AtomicInteger profilerCount;
        private boolean ended;

        Frame(IrisPortalHandAccess access, Context owned, Matrix4fStack modelView) {
            this.access = access;
            this.owned = owned;
            previous = Context.capture(access);
            previousModelView = RenderSystem.getModelViewStack();
            projection = RenderSystem.getProjectionMatrixBuffer();
            projectionType = RenderSystem.getProjectionType();
            savedProjection = IrisPortalRenderSystemAccess.wormholes$savedProjection();
            savedProjectionType = IrisPortalRenderSystemAccess.wormholes$savedProjectionType();
            profiler = IrisPortalHandProfilerAccess.wormholes$active();
            previousProfiler = profiler.get();
            profilerCount = IrisPortalHandProfilerAccess.wormholes$count();
            owned.apply(access);
            modelView.clear().set(previousModelView);
            IrisPortalRenderSystemAccess.wormholes$modelView(modelView);
            profiler.set(InactiveProfiler.INSTANCE);
            profilerCount.incrementAndGet();
        }

        @Override
        public void close() {
            try {
                closeFrame(owned);
            } finally {
                try {
                    if (!ended) {
                        owned.buffers().endFrame();
                    }
                } finally {
                    previous.apply(access);
                    IrisPortalRenderSystemAccess.wormholes$modelView(previousModelView);
                    RenderSystem.setProjectionMatrix(projection, projectionType);
                    IrisPortalRenderSystemAccess.wormholes$savedProjection(savedProjection);
                    IrisPortalRenderSystemAccess.wormholes$savedProjectionType(savedProjectionType);
                    if (previousProfiler == null) {
                        profiler.remove();
                    } else {
                        profiler.set(previousProfiler);
                    }
                    profilerCount.decrementAndGet();
                }
            }
        }
    }
}
