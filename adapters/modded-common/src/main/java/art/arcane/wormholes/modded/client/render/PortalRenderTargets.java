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
    private final Map<Integer, Target> travel = new HashMap<>();

    PortalRenderTargets() {
        for (int depth = 0; depth < DEPTHS; depth++) {
            scratch[depth] = new Target();
        }
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
        return travel.computeIfAbsent(key, ignored -> new Target()).resize(width, height);
    }

    SkyRenderer travelSky(int key) {
        return travel.get(key).sky();
    }

    void releaseTravel(int key) {
        Target target = travel.remove(key);
        if (target != null) {
            target.close();
        }
    }

    TextureTarget layer(int width, int height) {
        return layer.resize(width, height);
    }

    TextureTarget scratch(int depth, int width, int height) {
        return scratch[depth].resize(width, height);
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
        long bytes = layer.bytes();
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
        private SkyRenderer sky;

        private TextureTarget resize(int width, int height) {
            if (texture == null || texture.width != width || texture.height != height) {
                if (texture != null) {
                    texture.destroyBuffers();
                }
                texture = new TextureTarget("Wormholes portal layer", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
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
            return texture == null ? 0 : (long) texture.width * texture.height * 8;
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
