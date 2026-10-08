package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11C;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

public final class IrisHistoryTap {
    private static final ArrayDeque<Entered> LAYERS = new ArrayDeque<>();
    private static final Map<BlockPos, Sample> VIEWS = new HashMap<>();
    private static boolean enabled;
    private static Sample main;
    private static String failure;

    private IrisHistoryTap() {
    }

    public static void start() {
        LAYERS.clear();
        VIEWS.clear();
        main = null;
        failure = null;
        enabled = true;
    }

    public static void stop() {
        enabled = false;
        LAYERS.clear();
        VIEWS.clear();
        main = null;
    }

    public static void enter(PortalLayer layer) {
        if (enabled) {
            LAYERS.push(new Entered(layer, Iris.getPipelineManager().getPipelineNullable()));
        }
    }

    public static void leave() {
        if (!enabled) {
            return;
        }
        if (LAYERS.isEmpty()) {
            fail("Iris portal layer exited without an entry");
            return;
        }
        Entered entered = LAYERS.pop();
        if (Iris.getPipelineManager().getPipelineNullable() != entered.parent()) {
            fail("Iris parent pipeline selection was not restored");
        }
    }

    public static void rendered(IrisRenderingPipeline pipeline, RenderTargets targets) {
        if (!enabled) {
            return;
        }
        RenderTarget history = targets.get(5);
        if (history == null) {
            fail("Selected shader pack " + Iris.getCurrentPackName() + " did not allocate the required colortex5 buffer");
            return;
        }
        if (!GL11C.glIsTexture(history.getMainTexture()) || !GL11C.glIsTexture(history.getAltTexture())) {
            fail("Shader colortex5 does not reference live GPU textures");
        }
        Vector3d position = CameraUniforms.getUnshiftedCameraPosition();
        Vec3 camera = new Vec3(position.x, position.y, position.z);
        Matrix4f matrix = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        Sample sample = new Sample(pipeline, history.getMainTexture(), history.getAltTexture(), camera, matrix, 1,
            SystemTimeUniforms.COUNTER.getAsInt());
        if (LAYERS.isEmpty()) {
            main = merge(main, sample, "main view");
            return;
        }
        PortalLayer layer = LAYERS.peek().layer();
        if (camera.distanceTo(layer.camera().pos) > 0.0001D || !matrix.equals(layer.camera().viewRotationMatrix, 0.0001F)) {
            fail("Iris camera uniforms differ from the portal's virtual camera");
        }
        if (layer.depth() != 1) {
            return;
        }
        BlockPos origin = new BlockPos(layer.view().surface().geometry().originX(), layer.view().surface().geometry().originY(),
            layer.view().surface().geometry().originZ());
        VIEWS.put(origin, merge(VIEWS.get(origin), sample, "portal " + origin));
    }

    public static Sample main() {
        return main;
    }

    public static Sample view(BlockPos origin) {
        return VIEWS.get(origin);
    }

    public static String failure() {
        return failure;
    }

    private static Sample merge(Sample previous, Sample next, String label) {
        if (previous == null) {
            return next;
        }
        if (previous.pipeline() != next.pipeline() || previous.mainTexture() != next.mainTexture()
            || previous.altTexture() != next.altTexture()) {
            fail(label + " replaced its pipeline or temporal textures between consecutive renders");
        }
        return new Sample(next.pipeline(), next.mainTexture(), next.altTexture(), next.camera(), next.matrix(), previous.renders() + 1,
            next.frame());
    }

    private static void fail(String message) {
        if (failure == null) {
            failure = message;
        }
    }

    public record Sample(IrisRenderingPipeline pipeline, int mainTexture, int altTexture, Vec3 camera, Matrix4f matrix, int renders, int frame) {
    }

    private record Entered(PortalLayer layer, WorldRenderingPipeline parent) {
    }
}
