package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import org.joml.Vector2i;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.WeakHashMap;
import net.irisshaders.iris.targets.RenderTargets;

final class PortalIrisResources {
    private static final Map<ProgramSet, Set<Integer>> allocations = new WeakHashMap<>();
    private static long revision;

    private PortalIrisResources() {
    }

    static long targets(ProgramSet programs, int width, int height) {
        PackDirectives directives = programs.getPackDirectives();
        Set<Integer> allocated = allocations.get(programs);
        long bytes = (long) width * height * 16;
        for (Map.Entry<Integer, PackRenderTargetDirectives.RenderTargetSettings> entry
            : directives.getRenderTargetDirectives().getRenderTargetSettings().entrySet()) {
            if (allocated != null && !allocated.contains(entry.getKey())) {
                continue;
            }
            Vector2i size = directives.getTextureScaleOverride(entry.getKey(), width, height);
            bytes = Math.addExact(bytes, texture(size.x, size.y, 1, entry.getValue().getInternalFormat()) * 2);
        }
        for (ImageInformation image : programs.getPack().getIrisCustomImages()) {
            int imageWidth = image.isRelative() ? (int) (width * image.relativeWidth()) : image.width();
            int imageHeight = image.isRelative() ? (int) (height * image.relativeHeight()) : image.height();
            bytes = Math.addExact(bytes, texture(imageWidth, imageHeight, Math.max(1, image.depth()), image.internalTextureFormat()));
        }
        for (BuiltShaderStorageInfo buffer : programs.getPack().getBufferObjects().values()) {
            long size = buffer.relative() ? Math.multiplyExact(buffer.size(),
                Math.multiplyExact((long) (width * buffer.scaleX()), (long) (height * buffer.scaleY()))) : buffer.size();
            bytes = Math.addExact(bytes, size);
        }
        ShaderPack pack = programs.getPack();
        for (Map<String, CustomTextureData> textures : pack.getCustomTextureDataMap().values()) {
            for (CustomTextureData data : textures.values()) {
                bytes = Math.addExact(bytes, customTexture(data));
            }
        }
        for (CustomTextureData data : pack.getIrisCustomTextureDataMap().values()) {
            bytes = Math.addExact(bytes, customTexture(data));
        }
        bytes = Math.addExact(bytes, pack.getCustomNoiseTexture() == null
            ? (long) directives.getNoiseTextureResolution() * directives.getNoiseTextureResolution() * 4
            : customTexture(pack.getCustomNoiseTexture()));
        return bytes;
    }

    static long revision() {
        return revision;
    }

    static void allocated(ProgramSet programs, RenderTargets targets) {
        Set<Integer> previous = allocations.get(programs);
        Set<Integer> current = previous == null ? new HashSet<>() : new HashSet<>(previous);
        for (int index = 0; index < targets.getRenderTargetCount(); index++) {
            if (targets.get(index) != null) {
                current.add(index);
            }
        }
        if (!current.equals(previous)) {
            allocations.put(programs, Set.copyOf(current));
            revision++;
        }
    }

    static long shadows(ProgramSet programs) {
        PackShadowDirectives directives = programs.getPackDirectives().getShadowDirectives();
        int count = programs.getPack().hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR) ? 8 : 2;
        long bytes = (long) directives.getResolution() * directives.getResolution() * 8 * 4 / 3;
        for (int index = 0; index < count; index++) {
            PackShadowDirectives.SamplingSettings settings = directives.getColorSamplingSettings().get(index);
            InternalTextureFormat format = settings == null ? InternalTextureFormat.RGBA8 : settings.getFormat();
            bytes = Math.addExact(bytes, texture(directives.getResolution(), directives.getResolution(), 1, format) * 2);
        }
        return bytes;
    }

    static boolean shareShadows(ProgramSet programs) {
        for (PackShadowDirectives.SamplingSettings settings
            : programs.getPackDirectives().getShadowDirectives().getColorSamplingSettings().values()) {
            if (!settings.getClear()) {
                return false;
            }
        }
        return true;
    }

    static long texture(int width, int height, int depth, InternalTextureFormat format) {
        if (width < 1 || height < 1 || depth < 1) {
            throw new IllegalArgumentException("Shader texture dimensions must be positive");
        }
        long pixels = Math.multiplyExact(Math.multiplyExact((long) width, height), depth);
        return Math.multiplyExact(pixels, pixelBytes(format)) * 4 / 3;
    }

    static int pixelBytes(InternalTextureFormat format) {
        return switch (format) {
            case RGBA2, RGBA4, R3_G3_B2, RGB5_A1, RGB565, RGB10_A2, RGB10_A2UI, R11F_G11F_B10F, RGB9_E5, RGBA -> 4;
            default -> {
                String name = format.name();
                int components = name.startsWith("RGBA") ? 4 : name.startsWith("RGB") ? 3 : name.startsWith("RG") ? 2 : 1;
                int bits = name.contains("32") ? 32 : name.contains("16") ? 16 : 8;
                yield (components == 3 ? 4 : components) * bits / 8;
            }
        };
    }

    private static long customTexture(CustomTextureData data) {
        return switch (data) {
            case CustomTextureData.RawData1D raw -> texture(raw.getSizeX(), 1, 1, raw.getInternalFormat());
            case CustomTextureData.RawData2D raw -> texture(raw.getSizeX(), raw.getSizeY(), 1, raw.getInternalFormat());
            case CustomTextureData.RawData3D raw -> texture(raw.getSizeX(), raw.getSizeY(), raw.getSizeZ(), raw.getInternalFormat());
            case CustomTextureData.PngData png -> {
                ByteBuffer header = ByteBuffer.wrap(png.getContent()).order(ByteOrder.BIG_ENDIAN);
                if (header.remaining() < 24 || header.getLong(0) != 0x89504E470D0A1A0AL) {
                    throw new IllegalArgumentException("Shader custom texture is not a PNG image");
                }
                yield texture(header.getInt(16), header.getInt(20), 1, InternalTextureFormat.RGBA8);
            }
            default -> 0;
        };
    }
}
