package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.ViewStreamLimits;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

/** The colour-and-depth targets streamed views render into: one scratch target per nesting depth, one per travel view, and the aperture layer. */
final class PortalRenderTargets implements AutoCloseable {
    static final int DEPTHS = ViewStreamLimits.MAX_GEOMETRY_DEPTH;

    private final Target[] scratch = new Target[DEPTHS];
    private final PortalFeatureRenderer[] features = new PortalFeatureRenderer[DEPTHS];
    private final Target layer = new Target();
    private final Map<Integer, Target> travel = new HashMap<>();

    PortalRenderTargets() {
        for (int depth = 0; depth < DEPTHS; depth++) {
            scratch[depth] = new Target();
        }
    }

    TextureTarget travel(int key, int width, int height) {
        return travel.computeIfAbsent(key, ignored -> new Target()).resize(width, height);
    }

    TextureTarget layer(int width, int height) {
        return layer.resize(width, height);
    }

    TextureTarget scratch(int depth, int width, int height) {
        return scratch[depth].resize(width, height);
    }

    PortalFeatureRenderer features(int depth) {
        if (features[depth] == null) {
            features[depth] = new PortalFeatureRenderer();
        }
        return features[depth];
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
        }
    }

    private static final class Target implements AutoCloseable {
        private TextureTarget texture;

        private TextureTarget resize(int width, int height) {
            if (texture == null || texture.width != width || texture.height != height) {
                if (texture != null) {
                    texture.destroyBuffers();
                }
                texture = new TextureTarget(width, height, true, Minecraft.ON_OSX);
                texture.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            }
            return texture;
        }

        private long bytes() {
            return texture == null ? 0 : (long) texture.width * texture.height * 8L;
        }

        @Override
        public void close() {
            if (texture != null) {
                texture.destroyBuffers();
                texture = null;
            }
        }
    }
}
