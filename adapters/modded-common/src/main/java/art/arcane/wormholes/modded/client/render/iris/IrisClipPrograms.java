package art.arcane.wormholes.modded.client.render.iris;

import com.mojang.logging.LogUtils;
import io.github.douira.glsl_transformer.ast.transform.TransformationException;
import io.github.douira.glsl_transformer.parser.ParsingException;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.pipeline.transform.transformer.CommonTransformer;
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
            return IrisClipTransformation.transform(sources);
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

    static boolean clipped(String name, Parameters parameters) {
        return switch (parameters.patch) {
            case VANILLA -> name != null && !name.startsWith(SKY_PREFIX) && !CommonTransformer.isShadowPass(parameters, name);
            case SODIUM -> !CommonTransformer.isShadowPass(parameters, name);
            case DH_TERRAIN, DH_GENERIC, COMPOSITE, COMPUTE -> false;
        };
    }

    public record Binding(int location, int distance) {
        public static final Binding NONE = new Binding(-1, -1);
    }
}
