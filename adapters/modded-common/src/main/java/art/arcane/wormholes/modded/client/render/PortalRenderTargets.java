package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.Minecraft;

final class PortalRenderTargets implements AutoCloseable {
    static final int DEPTHS = ClientViewProtocol.MAX_GEOMETRY_DEPTH;

    private final TextureTarget[] scratch = new TextureTarget[DEPTHS];
    private final PortalFeatureRenderer[] features = new PortalFeatureRenderer[DEPTHS];
    private final ProjectionMatrixBuffer[] projections = new ProjectionMatrixBuffer[DEPTHS];
    private final SkyRenderer[] skies = new SkyRenderer[DEPTHS];
    private TextureTarget layer;

    TextureTarget layer(int width, int height) {
        layer = resize(layer, width, height);
        return layer;
    }

    TextureTarget scratch(int depth, int width, int height) {
        TextureTarget previous = scratch[depth];
        scratch[depth] = resize(previous, width, height);
        if (previous != scratch[depth] && skies[depth] != null) {
            skies[depth].close();
            skies[depth] = null;
        }
        return scratch[depth];
    }

    SkyRenderer sky(int depth) {
        if (skies[depth] == null) {
            Minecraft minecraft = Minecraft.getInstance();
            skies[depth] = new SkyRenderer(minecraft.getTextureManager(), minecraft.getAtlasManager(), scratch[depth]);
        }
        return skies[depth];
    }

    PortalFeatureRenderer features(int depth) {
        if (features[depth] == null) {
            features[depth] = new PortalFeatureRenderer();
        }
        return features[depth];
    }

    ProjectionMatrixBuffer projection(int depth) {
        if (projections[depth] == null) {
            projections[depth] = new ProjectionMatrixBuffer("Portal projection " + depth);
        }
        return projections[depth];
    }

    long bytes() {
        long bytes = layer == null ? 0 : (long) layer.width * layer.height * 8;
        for (TextureTarget target : scratch) {
            if (target != null) {
                bytes += (long) target.width * target.height * 8;
            }
        }
        return bytes;
    }

    void endFrame() {
        for (PortalFeatureRenderer renderer : features) {
            if (renderer != null) {
                renderer.endFrame();
            }
        }
    }

    @Override
    public void close() {
        if (layer != null) {
            layer.destroyBuffers();
            layer = null;
        }
        for (int depth = 0; depth < DEPTHS; depth++) {
            if (skies[depth] != null) {
                skies[depth].close();
                skies[depth] = null;
            }
            if (scratch[depth] != null) {
                scratch[depth].destroyBuffers();
                scratch[depth] = null;
            }
            if (features[depth] != null) {
                features[depth].close();
                features[depth] = null;
            }
            if (projections[depth] != null) {
                projections[depth].close();
                projections[depth] = null;
            }
        }
    }

    private static TextureTarget resize(TextureTarget target, int width, int height) {
        if (target == null || target.width != width || target.height != height) {
            if (target != null) {
                target.destroyBuffers();
            }
            return new TextureTarget("Wormholes portal layer", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }
        return target;
    }
}
