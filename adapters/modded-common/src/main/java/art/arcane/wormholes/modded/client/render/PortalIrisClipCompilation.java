package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.util.Map;
import java.util.Set;

public final class PortalIrisClipCompilation implements AutoCloseable {
    private static PortalIrisClipCompilation current;

    private final PortalIrisClipCompilation previous;
    private final boolean drawable;
    private final boolean terrain;
    private int distance = -1;
    private Set<Integer> existingDistances = Set.of();

    private PortalIrisClipCompilation(ShaderKey key) {
        previous = current;
        drawable = drawable(key);
        terrain = switch (key) {
            case TERRAIN_SOLID, TERRAIN_CUTOUT, TERRAIN_TRANSLUCENT -> true;
            default -> false;
        };
        current = this;
    }

    static PortalIrisClipCompilation open(ShaderKey key) {
        return new PortalIrisClipCompilation(key);
    }

    static boolean drawable(ShaderKey key) {
        return !key.isShadow() && switch (key) {
            case SKY_BASIC, SKY_BASIC_COLOR, SKY_TEXTURED, SKY_TEXTURED_COLOR, CLOUDS, CLOUDS_SODIUM -> false;
            default -> true;
        };
    }

    public static boolean active() {
        return current != null && current.drawable;
    }

    public static PortalIrisClipping.Result transform(Map<PatchShaderType, String> sources) {
        PortalIrisClipCompilation scope = current;
        if (scope == null || !scope.drawable) {
            return null;
        }
        PortalIrisClipping.Result transformed = PortalIrisClipping.transform(sources,
            GL11C.glGetInteger(GL30C.GL_MAX_CLIP_DISTANCES), scope.terrain);
        scope.distance = transformed.clipDistance();
        scope.existingDistances = transformed.existingDistances();
        return transformed;
    }

    Set<Integer> existingDistances() {
        return existingDistances;
    }

    int distance() {
        return distance;
    }

    @Override
    public void close() {
        current = previous;
    }
}
