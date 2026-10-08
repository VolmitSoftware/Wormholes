package art.arcane.wormholes.modded.client.render.stencil;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

final class LayerResources implements AutoCloseable {
    private static final int UNIFORM_USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;
    private static final int VISIBLE_SECTIONS = 4096;
    private static final int NEARBY_SECTIONS = 64;

    private final int depth;
    private final LevelRenderState state = new LevelRenderState();
    private final LevelTargetBundle targets = new LevelTargetBundle();
    private final SubmitNodeStorage submits = new SubmitNodeStorage();
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = new ObjectArrayList<>(VISIBLE_SECTIONS);
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> nearby = new ObjectArrayList<>(NEARBY_SECTIONS);
    private final PortalCamera camera = new PortalCamera();
    private final LightmapRenderState light = new LightmapRenderState();
    private RenderBuffers buffers;
    private FeatureRenderDispatcher features;
    private GlobalSettingsUniform globals;
    private GpuBuffer projection;
    private GpuBuffer fog;
    private boolean used;

    LayerResources(int depth) {
        this.depth = depth;
    }

    LevelRenderState state() {
        return state;
    }

    LevelTargetBundle targets() {
        return targets;
    }

    SubmitNodeStorage submits() {
        return submits;
    }

    ObjectArrayList<SectionRenderDispatcher.RenderSection> visible() {
        return visible;
    }

    ObjectArrayList<SectionRenderDispatcher.RenderSection> nearby() {
        return nearby;
    }

    PortalCamera camera() {
        return camera;
    }

    LightmapRenderState light() {
        return light;
    }

    FeatureRenderDispatcher features(Minecraft minecraft) {
        used = true;
        if (features == null) {
            buffers = new RenderBuffers(0);
            features = new FeatureRenderDispatcher(buffers, minecraft.getModelManager(), minecraft.getAtlasManager(), minecraft.font,
                minecraft.gameRenderer.gameRenderState());
        }
        return features;
    }

    GlobalSettingsUniform globals() {
        if (globals == null) {
            globals = new GlobalSettingsUniform();
        }
        return globals;
    }

    GpuBufferSlice projection(Matrix4fc matrix, Vector4fc clipPlane) {
        if (projection == null) {
            projection = RenderSystem.getDevice().createBuffer(() -> "Wormholes portal layer " + depth + " projection", UNIFORM_USAGE,
                PortalClipShaders.PROJECTION_UBO_SIZE);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, PortalClipShaders.PROJECTION_UBO_SIZE).putMat4f(matrix).putVec4(clipPlane).get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(projection.slice(), data);
        }
        return projection.slice();
    }

    GpuBufferSlice fog(FogData data) {
        if (fog == null) {
            fog = RenderSystem.getDevice().createBuffer(() -> "Wormholes portal layer " + depth + " fog", UNIFORM_USAGE, FogRenderer.FOG_UBO_SIZE);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bytes = Std140Builder.onStack(stack, FogRenderer.FOG_UBO_SIZE).putVec4(data.color).putFloat(data.environmentalStart)
                .putFloat(data.environmentalEnd).putFloat(data.renderDistanceStart).putFloat(data.renderDistanceEnd).putFloat(data.skyEnd)
                .putFloat(data.cloudEnd).get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(fog.slice(), bytes);
        }
        return fog.slice(0L, FogRenderer.FOG_UBO_SIZE);
    }

    void endFrame() {
        if (used && buffers != null) {
            buffers.endFrame();
        }
        used = false;
    }

    @Override
    public void close() {
        if (features != null) {
            features.close();
            features = null;
        }
        if (buffers != null) {
            buffers.close();
            buffers = null;
        }
        if (globals != null) {
            globals.close();
            globals = null;
        }
        if (projection != null) {
            projection.close();
            projection = null;
        }
        if (fog != null) {
            fog.close();
            fog = null;
        }
    }
}
