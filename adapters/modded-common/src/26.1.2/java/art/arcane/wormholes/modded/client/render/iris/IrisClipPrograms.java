package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.dh.DhClipPrograms;
import com.mojang.logging.LogUtils;
import io.github.douira.glsl_transformer.ast.transform.TransformationException;
import io.github.douira.glsl_transformer.parser.ParsingException;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.pipeline.transform.parameter.SodiumParameters;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.lwjgl.opengl.GL20C;
import org.slf4j.Logger;

import java.util.Map;

public final class IrisClipPrograms {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SKY_PREFIX = "sky_";

    private IrisClipPrograms() {
    }

    public static Map<PatchShaderType, String> transform(String name, Parameters parameters, Map<PatchShaderType, String> sources) {
        if (sources == null || !clipped(name, parameters)) {
            return null;
        }
        try {
            return switch (parameters.patch) {
                case DH_TERRAIN, DH_GENERIC -> IrisClipTransformation.transformDistant(sources);
                default -> IrisClipTransformation.transform(sources);
            };
        } catch (IllegalArgumentException | IllegalStateException | ParsingException | TransformationException failure) {
            LOGGER.warn("Portal views draw shader program {} without the portal clip plane", name, failure);
            return null;
        }
    }

    public static Binding bind(int program) {
        for (int distance = 0; distance < IrisClipTransformation.MAX_DISTANCES; distance++) {
            int location = GL20C.glGetUniformLocation(program, IrisClipTransformation.uniform(distance));
            if (location >= 0) {
                return new Binding(location, distance);
            }
        }
        return Binding.NONE;
    }

    public static DistantBinding bindDistant(int program) {
        return new DistantBinding(bind(program), GL20C.glGetUniformLocation(program, IrisClipTransformation.DISTANT_DEPTH));
    }

    public static void applyDistant(DistantBinding binding) {
        IrisClipPlanes.apply(binding.clipping().location(), binding.clipping().distance(), DhClipPrograms.plane());
        if (binding.depth() < 0) {
            return;
        }
        GL20C.glUniform2f(binding.depth(), 1.0F, 0.0F);
    }

    static boolean clipped(String name, Parameters parameters) {
        return switch (parameters.patch) {
            case VANILLA -> name != null && !name.startsWith(SKY_PREFIX) && !shadowPass(parameters, name);
            case SODIUM, DH_TERRAIN, DH_GENERIC -> !shadowPass(parameters, name);
            case COMPOSITE, COMPUTE -> false;
        };
    }

    private static boolean shadowPass(Parameters parameters, String name) {
        if (parameters instanceof SodiumParameters sodium) {
            return sodium.shadow;
        }
        return parameters.getTextureStage() == TextureStage.GBUFFERS_AND_SHADOW && name != null
            && (name.equals("shadow") || name.startsWith("shadow_") || name.endsWith("_shadow") || name.startsWith("dh_shadow_"));
    }

    public record Binding(int location, int distance) {
        public static final Binding NONE = new Binding(-1, -1);
    }

    public record DistantBinding(Binding clipping, int depth) {
    }
}
