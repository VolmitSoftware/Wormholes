package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.wormholes.modded.client.render.iris.IrisMeshCamera;
import art.arcane.wormholes.modded.client.render.iris.IrisMeshFrame;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class IrisMeshHistoryTap {
    private static final ArrayDeque<Entered> SCOPES = new ArrayDeque<>();
    private static final Map<Key, Sample> STREAMS = new HashMap<>();
    private static final Map<BlockPos, Sample> NATIVE = new HashMap<>();
    private static boolean enabled;
    private static Sample main;
    private static String failure;
    private static int restored;

    private IrisMeshHistoryTap() {
    }

    public static void start() {
        SCOPES.clear();
        STREAMS.clear();
        NATIVE.clear();
        main = null;
        failure = null;
        restored = 0;
        enabled = true;
    }

    public static void stop() {
        enabled = false;
        SCOPES.clear();
        STREAMS.clear();
        NATIVE.clear();
        main = null;
    }

    public static boolean active() {
        return !SCOPES.isEmpty();
    }

    public static void enter(IrisMeshFrame.View view) {
        SCOPES.push(new Entered(view, new Parent(Iris.getPipelineManager().getPipelineNullable(), IrisHistoryTap.currentLayer())));
    }

    public static void drawn() {
        if (enabled && active()) {
            SCOPES.peek().draws++;
        }
    }

    public static void leave() {
        if (SCOPES.isEmpty()) {
            fail("Streamed Iris scope exited without entering");
            return;
        }
        Entered entered = SCOPES.pop();
        if (!enabled) {
            return;
        }
        if (!entered.finalized) {
            fail("Streamed camera closed without an actual shader finalization");
        }
        if (Iris.getPipelineManager().getPipelineNullable() != entered.parent) {
            fail("Streamed camera did not restore the exact parent pipeline selection");
        } else {
            restored++;
        }
    }

    public static void finalized(IrisRenderingPipeline pipeline, RenderTargets targets) {
        if (!enabled) {
            return;
        }
        RenderTarget history = targets.get(5);
        if (history == null) {
            fail("Complementary Unbound did not allocate colortex5 in a finalized camera");
            return;
        }
        int primary = history.getMainTexture();
        int alternate = history.getAltTexture();
        if (primary <= 0 || alternate <= 0 || primary == alternate || !GL11C.glIsTexture(primary) || !GL11C.glIsTexture(alternate)) {
            fail("Finalized colortex5 does not contain two distinct live GPU textures");
        }
        if (Iris.getPipelineManager().getPipelineNullable() != pipeline) {
            fail("Finalized shader pipeline differs from the actually selected pipeline");
        }
        Vector3d position = CameraUniforms.getUnshiftedCameraPosition();
        Vec3 camera = new Vec3(position.x, position.y, position.z);
        Matrix4f matrix = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
        int frame = SystemTimeUniforms.COUNTER.getAsInt();
        Entered entered = SCOPES.peek();
        if (entered != null) {
            entered.finalized = true;
            if (pipeline == entered.parent) {
                fail("Streamed camera finalized its enclosing parent pipeline");
            }
            if (!matrix.equals(entered.view.shaderView(), 0.0001F)) {
                fail("Streamed shader finalization lost the actual streamed camera modelview");
            }
            if (entered.draws == 0) {
                return;
            }
            IrisMeshCamera identity = entered.view.camera();
            BlockPos parentOrigin = entered.layer == null ? null : origin(entered.layer.view().surface().geometry());
            Key key = new Key(identity, entered.parent);
            Sample sample = new Sample(pipeline, primary, alternate, camera, matrix, frame, 1, entered.draws,
                identity, parentOrigin, entered.layer == null ? 0 : entered.layer.depth(), entered.parent);
            STREAMS.put(key, merge(STREAMS.get(key), sample, "streamed camera " + identity));
            return;
        }
        PortalLayer layer = IrisHistoryTap.currentLayer();
        Sample sample = new Sample(pipeline, primary, alternate, camera, matrix, frame, 1, 0, null,
            layer == null ? null : origin(layer.view().surface().geometry()), layer == null ? 0 : layer.depth(), null);
        if (layer == null) {
            main = merge(main, sample, "main camera");
        } else if (layer.depth() == 1) {
            NATIVE.put(sample.parentOrigin(), merge(NATIVE.get(sample.parentOrigin()), sample, "native camera " + sample.parentOrigin()));
        }
    }

    public static Sample main() {
        return main;
    }

    public static Sample nativeView(BlockPos origin) {
        return NATIVE.get(origin);
    }

    public static Sample child(BlockPos parent, BlockPos child) {
        Sample found = null;
        for (Sample sample : STREAMS.values()) {
            if (parent.equals(sample.parentOrigin()) && sample.parentDepth() == 1
                && child.equals(origin(sample.identity().traversal().getLast()))) {
                if (found != null) {
                    fail("Multiple streamed camera identities matched the same physical child under its native parent");
                    return null;
                }
                found = sample;
            }
        }
        return found;
    }

    public static List<Sample> owned() {
        List<Sample> samples = new ArrayList<>(NATIVE.values());
        samples.addAll(STREAMS.values());
        return List.copyOf(samples);
    }

    public static String failure() {
        return failure;
    }

    public static int restored() {
        return restored;
    }

    private static Sample merge(Sample previous, Sample next, String label) {
        if (previous == null) {
            return next;
        }
        if (previous.pipeline() != next.pipeline() || previous.mainTexture() != next.mainTexture()
            || previous.altTexture() != next.altTexture()) {
            fail(label + " replaced its full pipeline or actual temporal texture pair");
        }
        return new Sample(next.pipeline(), next.mainTexture(), next.altTexture(), next.camera(), next.matrix(), next.frame(),
            previous.renders() + 1, previous.draws() + next.draws(), next.identity(), next.parentOrigin(), next.parentDepth(), next.parent());
    }

    private static BlockPos origin(ApertureDescriptor geometry) {
        return new BlockPos(geometry.originX(), geometry.originY(), geometry.originZ());
    }

    private static void fail(String message) {
        if (enabled && failure == null) {
            failure = message;
        }
    }

    public record Sample(IrisRenderingPipeline pipeline, int mainTexture, int altTexture, Vec3 camera, Matrix4f matrix,
                         int frame, int renders, int draws, IrisMeshCamera identity, BlockPos parentOrigin, int parentDepth,
                         WorldRenderingPipeline parent) {
    }

    private record Key(IrisMeshCamera camera, WorldRenderingPipeline parent) {
    }

    private record Parent(WorldRenderingPipeline pipeline, PortalLayer layer) {
    }

    private static final class Entered {
        private final IrisMeshFrame.View view;
        private final WorldRenderingPipeline parent;
        private final PortalLayer layer;
        private int draws;
        private boolean finalized;

        private Entered(IrisMeshFrame.View view, Parent parent) {
            this.view = view;
            this.parent = parent.pipeline();
            this.layer = parent.layer();
        }
    }
}
