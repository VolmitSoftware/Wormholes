package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import art.arcane.optics.stream.ViewStreamLimits;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.Minecraft;
import java.util.HashMap;
import java.util.Map;

final class PortalRenderTargets implements AutoCloseable {
    static final int DEPTHS = ViewStreamLimits.MAX_GEOMETRY_DEPTH;

    private final Target[] scratch = new Target[DEPTHS];
    private final PortalFeatureRenderer[] features = new PortalFeatureRenderer[DEPTHS];
    private final ProjectionMatrixBuffer[] projections = new ProjectionMatrixBuffer[DEPTHS];
    private final Target layer = new Target();
    private final Target viewDepth = new Target();
    private final Target depthCopy = new Target();
    private ProjectionMatrixBuffer viewDepthTransform;
    private final Map<Integer, Target> travel = new HashMap<>();
    private GpuFormat colorFormat = GpuFormat.RGBA8_UNORM;

    PortalRenderTargets() {
        for (int depth = 0; depth < DEPTHS; depth++) {
            scratch[depth] = new Target();
        }
    }

    /** The colour format of every view, layer and travel target; changing it releases them so they come back in the new format. */
    void colorFormat(GpuFormat format) {
        if (colorFormat == format) {
            return;
        }
        for (Target target : travel.values()) {
            target.close();
        }
        travel.clear();
        layer.close();
        for (Target target : scratch) {
            target.close();
        }
        colorFormat = format;
    }

    boolean available(int depth) {
        TextureTarget target = scratch[depth].texture;
        return target != null && target.getColorTexture() != null && target.getDepthTexture() != null;
    }

    TextureTarget travel(int key) {
        Target target = travel.get(key);
        return target == null ? null : target.texture;
    }

    TextureTarget travel(int key, int width, int height) {
        return travel.computeIfAbsent(key, ignored -> new Target()).resize(colorFormat, width, height);
    }

    SkyRenderer travelSky(int key) {
        return travel.get(key).sky();
    }

    TextureTarget layer(int width, int height) {
        return layer.resize(colorFormat, width, height);
    }

    TextureTarget viewDepth(int width, int height) {
        return viewDepth.resize(GpuFormat.RGBA8_UNORM, width, height);
    }

    TextureTarget depthCopy(int width, int height, GpuFormat depthFormat) {
        return depthCopy.resize(GpuFormat.RGBA8_UNORM, width, height, depthFormat);
    }

    ProjectionMatrixBuffer viewDepthTransform() {
        if (viewDepthTransform == null) {
            viewDepthTransform = new ProjectionMatrixBuffer("Portal view depth");
        }
        return viewDepthTransform;
    }

    TextureTarget scratch(int depth, int width, int height) {
        return scratch[depth].resize(colorFormat, width, height);
    }

    SkyRenderer sky(int depth) {
        return scratch[depth].sky();
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
        long bytes = layer.bytes() + viewDepth.bytes() + depthCopy.bytes();
        for (Target target : travel.values()) {
            bytes += target.bytes();
        }
        for (Target target : scratch) {
            bytes += target.bytes();
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
        for (Target target : travel.values()) {
            target.close();
        }
        travel.clear();
        layer.close();
        viewDepth.close();
        depthCopy.close();
        if (viewDepthTransform != null) {
            viewDepthTransform.close();
            viewDepthTransform = null;
        }
        for (int depth = 0; depth < DEPTHS; depth++) {
            scratch[depth].close();
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

    private static final class Target implements AutoCloseable {
        private TextureTarget texture;
        private GpuFormat colorFormat;
        private GpuFormat depthFormat;
        private SkyRenderer sky;

        private TextureTarget resize(GpuFormat colorFormat, int width, int height) {
            return resize(colorFormat, width, height, GpuFormat.D32_FLOAT);
        }

        private TextureTarget resize(GpuFormat colorFormat, int width, int height, GpuFormat depthFormat) {
            if (texture == null || texture.width != width || texture.height != height || this.colorFormat != colorFormat || this.depthFormat != depthFormat) {
                if (texture != null) {
                    texture.destroyBuffers();
                }
                texture = new TextureTarget("Wormholes portal layer", width, height, colorFormat, depthFormat);
                this.colorFormat = colorFormat;
                this.depthFormat = depthFormat;
                if (sky != null) {
                    sky.close();
                    sky = null;
                }
            }
            return texture;
        }

        private SkyRenderer sky() {
            if (sky == null) {
                Minecraft minecraft = Minecraft.getInstance();
                sky = new SkyRenderer(minecraft.getTextureManager(), minecraft.getAtlasManager(), texture);
            }
            return sky;
        }

        private long bytes() {
            return texture == null ? 0 : (long) texture.width * texture.height * (colorFormat == GpuFormat.RGBA16_FLOAT ? 12 : 8);
        }

        @Override
        public void close() {
            if (sky != null) {
                sky.close();
                sky = null;
            }
            if (texture != null) {
                texture.destroyBuffers();
                texture = null;
            }
        }
    }
}
