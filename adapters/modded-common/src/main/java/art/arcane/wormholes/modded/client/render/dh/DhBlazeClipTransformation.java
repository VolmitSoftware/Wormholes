package art.arcane.wormholes.modded.client.render.dh;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DhBlazeClipTransformation {
    public static final String BLOCK = "wormholes_DhClipBlock";
    private static final String MAIN = "wormholes_dhBlazeMain";
    private static final Pattern MAIN_FUNCTION = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final Set<String> VERTICES = Set.of("terrain/blaze/vert", "generic/blaze/vert");

    private DhBlazeClipTransformation() {
    }

    public static boolean owns(String shader) {
        return VERTICES.contains(shader);
    }

    public static String transform(String shader, String source) {
        if (!owns(shader)) {
            return source;
        }
        if (source.contains(BLOCK) || source.contains(MAIN) || source.contains(DhClipTransformation.PLANE)
            || source.contains(DhClipTransformation.ENABLED) || source.contains("gl_ClipDistance")) {
            throw new IllegalArgumentException("Distant Horizons Blaze vertex shader uses reserved portal clipping identifiers: " + shader);
        }
        Matcher main = MAIN_FUNCTION.matcher(source);
        if (!main.find()) {
            throw new IllegalArgumentException("Distant Horizons Blaze vertex shader has no main function: " + shader);
        }
        int start = main.start();
        int end = main.end();
        if (main.find()) {
            throw new IllegalArgumentException("Distant Horizons Blaze vertex shader has multiple main functions: " + shader);
        }
        return source.substring(0, start) + "void " + MAIN + "()" + source.substring(end)
            + "\nlayout(std140) uniform " + BLOCK + " { vec4 " + DhClipTransformation.PLANE + "; bool "
            + DhClipTransformation.ENABLED + "; };\n"
            + "void main() { " + MAIN + "(); gl_ClipDistance[0] = " + DhClipTransformation.ENABLED
            + " ? dot(gl_Position, " + DhClipTransformation.PLANE + ") : 1.0; }\n";
    }
}
