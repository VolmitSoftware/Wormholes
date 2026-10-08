package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayerMath;
import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

public final class SodiumLayers {
    private static final LayerContextStack<SodiumTerrainState> STACK = new LayerContextStack<>();
    private static final Vector4f NO_PLANE = new Vector4f();
    private static int frame = Integer.MIN_VALUE;

    private SodiumLayers() {
    }

    public static int slot() {
        return STACK.slot();
    }

    public static DynamicGpuDataStorage.DynamicGpuData clipped(DynamicGpuDataStorage.DynamicGpuData globals, ChunkRenderMatrices matrices) {
        int offset = SodiumClipShaders.planeOffset();
        if (offset < 0 || SodiumTerrainBackend.shaderTerrain()) {
            return globals;
        }
        return new SodiumClipUniforms(globals, offset, plane(matrices));
    }

    static void begin(PortalLayer layer) {
        RenderSystem.assertOnRenderThread();
        LevelRendererExtension renderer = (LevelRendererExtension) layer.renderer();
        if (!layer.shared()) {
            SodiumLayerTerrain.reloadIfResized(renderer.sodium$getWorldRenderer());
        }
        SodiumLayerTerrain terrain = new SodiumLayerTerrain(layer, renderer);
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
    }

    static void end(PortalLayer layer) {
        if (!(STACK.top() instanceof SodiumLayerTerrain terrain) || terrain.layer() != layer) {
            throw new IllegalStateException("Sodium portal layer " + layer.depth() + " closed out of order");
        }
        STACK.exit(terrain);
    }

    static int nextFrame() {
        return frame++;
    }

    private static Vector4f plane(ChunkRenderMatrices matrices) {
        if (!(STACK.top() instanceof SodiumLayerTerrain terrain)) {
            return NO_PLANE;
        }
        PortalLayer layer = terrain.layer();
        Vec3 camera = layer.camera().pos;
        Vector4f view = PortalLayerMath.viewPlane(layer.worldClipPlane(), new Vec3d(camera.x, camera.y, camera.z), matrices.modelView());
        return PortalLayerMath.clipPlane(view, matrices.projection());
    }
}
