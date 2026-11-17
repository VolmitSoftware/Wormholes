package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import com.mojang.blaze3d.systems.RenderSystem;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.world.WorldRendererExtended;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4dc;
import org.joml.Vector4f;

public final class SodiumLayers {
    private static final LayerContextStack<SodiumTerrainState> STACK = new LayerContextStack<>();
    private static final float[] KEEP_ALL = {0.0F, 0.0F, 0.0F, 1.0F};
    private static int frame = Integer.MIN_VALUE;
    private static int epoch;

    private SodiumLayers() {
    }

    public static int slot() {
        return STACK.slot();
    }

    /** Counts main-view terrain setups, so each layer renderer pumps its builds once per frame. */
    public static void frameStarted() {
        epoch++;
    }

    static int epoch() {
        return epoch;
    }

    /** Frame ids for Sodium's visibility stamps, kept apart from the level renderer's own counter. */
    static int nextFrame() {
        return frame++;
    }

    static void begin(PortalLayer layer) {
        RenderSystem.assertOnRenderThread();
        WorldRendererExtended renderer = (WorldRendererExtended) layer.renderer();
        managed(() -> {
            if (!layer.shared()) {
                SodiumLayerTerrain.reloadIfResized(renderer.sodium$getWorldRenderer());
            }
            SodiumLayerTerrain terrain = new SodiumLayerTerrain(layer, renderer.sodium$getWorldRenderer());
            STACK.enter(layer.depth(), terrain);
            try {
                terrain.prepare();
            } catch (RuntimeException | Error failure) {
                try {
                    STACK.exit(terrain);
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        });
    }

    static void end(PortalLayer layer) {
        if (!(STACK.top() instanceof SodiumLayerTerrain terrain) || terrain.layer() != layer) {
            throw new IllegalStateException("Sodium portal layer " + layer.depth() + " closed out of order");
        }
        STACK.exit(terrain);
    }

    /** The open layer's destination plane in the clip space of {@code projection * modelView}, or a plane that keeps all. */
    public static float[] clipPlane(Matrix4fc projection, Matrix4fc modelView) {
        if (!(STACK.top() instanceof SodiumLayerTerrain terrain)) {
            return KEEP_ALL;
        }
        PortalLayer layer = terrain.layer();
        Vector4dc world = layer.worldClipPlane();
        Vec3 camera = layer.camera().pos();
        Vector4f plane = new Vector4f((float) world.x(), (float) world.y(), (float) world.z(),
            (float) (world.w() + world.x() * camera.x + world.y() * camera.y + world.z() * camera.z));
        new Matrix4f(projection).mul(modelView).invert().transpose().transform(plane);
        return new float[]{plane.x, plane.y, plane.z, plane.w};
    }

    /** Runs Sodium device work from outside Sodium's own managed render calls. */
    static void managed(Runnable work) {
        RenderDevice.enterManagedCode();
        try {
            work.run();
        } finally {
            RenderDevice.exitManagedCode();
        }
    }
}
