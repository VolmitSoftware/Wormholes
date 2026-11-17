package art.arcane.wormholes.modded.client.render.stencil;

import com.mojang.blaze3d.shaders.Program;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL20C;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Patches every core vertex shader to write {@code gl_ClipDistance[0]} from a {@code WormholesClipPlane} uniform, and
 * uploads {@link PortalClipState} into each program as it is applied. A shader that fails to compile with the patch is
 * recompiled from its original source and counted; while any core program failed, layers use the oblique projection
 * fallback instead of clip distances. Render thread only.
 */
public final class PortalClipShaders {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int UNKNOWN = -2;

    private static final Int2ObjectOpenHashMap<List<String>> originals = new Int2ObjectOpenHashMap<>();
    private static final Int2IntOpenHashMap locations = new Int2IntOpenHashMap();
    private static boolean loading;
    private static int patched;
    private static int failed;
    private static boolean ready;
    private static int generation;

    static {
        locations.defaultReturnValue(UNKNOWN);
    }

    private PortalClipShaders() {
    }

    /** Called before core shaders reload. */
    public static void beginLoad() {
        loading = true;
        patched = 0;
        failed = 0;
        locations.clear();
        originals.clear();
    }

    /** Called after core shaders reload. */
    public static void endLoad() {
        loading = false;
        ready = patched > 0 && failed == 0;
        originals.clear();
        generation++;
        if (failed > 0) {
            LOGGER.warn("Portal clip planes: {} of {} patched vertex shaders failed to compile; portal layers clip by oblique projection",
                failed, patched + failed);
        } else {
            LOGGER.info("Portal clip planes: patched {} vertex shaders", patched);
        }
    }

    public static int generation() {
        return generation;
    }

    /** Whether layers can clip with {@code gl_ClipDistance}; otherwise they use the oblique projection. */
    public static boolean ready() {
        return ready;
    }

    public static int failures() {
        return failed;
    }

    /** Rewrites a preprocessed vertex shader for the program named {@code name}; other stages pass through. */
    public static List<String> source(int shader, Program.Type type, String name, List<String> source) {
        if (type != Program.Type.VERTEX) {
            return source;
        }
        Optional<String> transformed = ClipShaderTransformation.vertex(name, String.join("", source));
        if (transformed.isEmpty()) {
            return source;
        }
        originals.put(shader, source);
        return List.of(transformed.get());
    }

    /**
     * Inspects a compile status; when a patched shader failed, recompiles its original source and returns that status,
     * so a third-party shader the patch cannot handle still loads.
     */
    public static int compiled(int shader, int status) {
        List<String> original = originals.remove(shader);
        if (original == null) {
            return status;
        }
        if (status != 0) {
            if (loading) {
                patched++;
            }
            return status;
        }
        failed++;
        LOGGER.warn("Portal clip plane patch broke a vertex shader; compiling it unpatched: {}", GL20C.glGetShaderInfoLog(shader, 4096).trim());
        GL20C.glShaderSource(shader, String.join("", original));
        GL20C.glCompileShader(shader);
        return GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS);
    }

    /** Uploads the current clip plane into a program that was just applied. */
    public static void apply(ShaderInstance shader) {
        int program = shader.getId();
        int location = locations.get(program);
        if (location == UNKNOWN) {
            location = GL20C.glGetUniformLocation(program, ClipShaderTransformation.CLIP_PLANE);
            locations.put(program, location);
        }
        if (location >= 0) {
            Vector4fc plane = PortalClipState.current();
            GL20C.glUniform4f(location, plane.x(), plane.y(), plane.z(), plane.w());
        }
    }
}
