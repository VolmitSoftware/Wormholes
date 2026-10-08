package art.arcane.wormholes.modded.client.render.stencil;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Optional;

public final class PortalClipShaders {
    public static final String OPEN_GL = "OpenGL";
    public static final int PROJECTION_UBO_SIZE = 80;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier PROJECTION = Identifier.withDefaultNamespace("shaders/include/projection.glsl");
    private static volatile boolean loading;
    private static volatile boolean ready;
    private static volatile int generation;

    private PortalClipShaders() {
    }

    public static void beginLoad(ResourceManager manager) {
        loading = openGl() && transformable(manager);
    }

    public static void endLoad() {
        ready = loading;
        loading = false;
        generation++;
    }

    public static int generation() {
        return generation;
    }

    public static boolean ready() {
        return ready;
    }

    public static boolean openGl() {
        GpuDevice device = RenderSystem.tryGetDevice();
        return device != null && OPEN_GL.equals(device.getDeviceInfo().backendName());
    }

    public static String include(Identifier location, String contents) {
        if (!loading || !PROJECTION.equals(location)) {
            return contents;
        }
        return ClipShaderTransformation.projection(contents).orElse(contents);
    }

    public static String shader(Identifier location, ShaderType type, String contents) {
        if (!loading || type != ShaderType.VERTEX) {
            return contents;
        }
        return ClipShaderTransformation.vertex(type.idConverter().fileToId(location).toString(), contents).orElse(contents);
    }

    private static boolean transformable(ResourceManager manager) {
        Optional<Resource> resource = manager.getResource(PROJECTION);
        if (resource.isEmpty()) {
            LOGGER.warn("Portal views render without clipping: {} is missing", PROJECTION);
            return false;
        }
        try {
            if (ClipShaderTransformation.projection(resource.get().readAllAsString()).isPresent()) {
                return true;
            }
            LOGGER.warn("Portal views render without clipping: {} has no Projection block to extend", PROJECTION);
            return false;
        } catch (IOException failure) {
            LOGGER.warn("Portal views render without clipping: {} could not be read", PROJECTION, failure);
            return false;
        }
    }
}
