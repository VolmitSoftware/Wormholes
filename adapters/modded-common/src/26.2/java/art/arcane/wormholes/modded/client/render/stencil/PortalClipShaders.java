package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.client.render.dh.DhBlazeClipTransformation;
import art.arcane.wormholes.modded.client.render.sodium.SodiumClipShaders;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        SodiumClipShaders.reset();
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
        if (!loading) {
            return contents;
        }
        if (SodiumClipShaders.GLOBALS.equals(location)) {
            return SodiumClipShaders.include(contents);
        }
        return PROJECTION.equals(location) ? ClipShaderTransformation.projection(contents).orElse(contents) : contents;
    }

    public static String shader(Identifier location, ShaderType type, String contents) {
        if (!loading || type != ShaderType.VERTEX) {
            return contents;
        }
        if ("distanthorizons".equals(location.getNamespace())
            && DhBlazeClipTransformation.owns(type.idConverter().fileToId(location).getPath())) {
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
        try (InputStream input = resource.get().open()) {
            if (ClipShaderTransformation.projection(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isPresent()) {
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
