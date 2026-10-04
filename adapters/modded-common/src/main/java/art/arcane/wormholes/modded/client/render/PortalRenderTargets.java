package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import com.mojang.renderpearl.api.GpuFormat;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.Minecraft;
import java.util.HashMap;
import java.util.Map;

final class PortalRenderTargets implements AutoCloseable {
    static final int DEPTHS = ClientViewProtocol.MAX_GEOMETRY_DEPTH;

    private final TextureTarget[] scratch = new TextureTarget[DEPTHS];
    private final PortalFeatureRenderer[] features = new PortalFeatureRenderer[DEPTHS];
    private final ProjectionMatrixBuffer[] projections = new ProjectionMatrixBuffer[DEPTHS];
    private final SkyRenderer[] skies = new SkyRenderer[DEPTHS];
    private TextureTarget layer;
    private final Map<Integer, TextureTarget> travel = new HashMap<>();
    private final Map<Integer, SkyRenderer> travelSkies = new HashMap<>();

    boolean available(int depth) {
        TextureTarget target = scratch[depth];
        return target != null && target.getColorTexture() != null && target.getDepthTexture() != null;
    }

    TextureTarget travel(int key) {
        return travel.get(key);
    }

    TextureTarget travel(int key, int width, int height) {
        TextureTarget previous = travel.get(key);
        TextureTarget target = resize(previous, width, height);
        travel.put(key, target);
        if (previous != target) {
            SkyRenderer sky = travelSkies.remove(key);
            if (sky != null) {
                sky.close();
            }
        }
        return target;
    }

    SkyRenderer travelSky(int key) {
        SkyRenderer sky = travelSkies.get(key);
        if (sky == null) {
            Minecraft minecraft = Minecraft.getInstance();
            sky = new SkyRenderer(minecraft.getTextureManager(), minecraft.getAtlasManager(), travel.get(key));
            travelSkies.put(key, sky);
        }
        return sky;
    }

    void releaseTravel(int key) {
        SkyRenderer sky = travelSkies.remove(key);
        if (sky != null) {
            sky.close();
        }
        TextureTarget target = travel.remove(key);
        if (target != null) {
            target.destroyBuffers();
        }
    }

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
        for (TextureTarget target : travel.values()) {
            bytes += (long) target.width * target.height * 8;
        }
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
        for (SkyRenderer sky : travelSkies.values()) {
            sky.close();
        }
        travelSkies.clear();
        for (TextureTarget target : travel.values()) {
            target.destroyBuffers();
        }
        travel.clear();
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
