package art.arcane.wormholes.modded.client.render.dh;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DhClipTransformation {
    public static final String PLANE = "wormholes_DhClipPlane";
    public static final String INVERSE = "wormholes_DhClipInverse";
    public static final String ENABLED = "wormholes_DhClipEnabled";

    private static final String MAIN = "wormholes_dhClipMain";
    private static final Pattern MAIN_FUNCTION = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final Set<String> VERTICES = Set.of("assets/distanthorizons/shaders/terrain/gl/vert.vert",
        "assets/distanthorizons/shaders/generic/gl/instanced/vert.vert", "assets/distanthorizons/shaders/generic/gl/direct/vert.vert");

    private DhClipTransformation() {
    }

    public static String transform(String path, String source) {
        if (!VERTICES.contains(path)) {
            return source;
        }
        if (source.contains(PLANE) || source.contains(INVERSE) || source.contains(ENABLED) || source.contains(MAIN)
            || source.contains("gl_ClipDistance")) {
            throw new IllegalArgumentException("Distant Horizons vertex shader uses reserved portal clipping identifiers: " + path);
        }
        Matcher main = MAIN_FUNCTION.matcher(source);
        if (!main.find()) {
            throw new IllegalArgumentException("Distant Horizons vertex shader has no main function: " + path);
        }
        int start = main.start();
        int end = main.end();
        if (main.find()) {
            throw new IllegalArgumentException("Distant Horizons vertex shader has multiple main functions: " + path);
        }
        return source.substring(0, start) + "void " + MAIN + "()" + source.substring(end)
            + "\nuniform vec4 " + PLANE + ";\nuniform mat4 " + INVERSE + ";\nuniform bool " + ENABLED + ";\n"
            + "void main() { " + MAIN + "(); gl_ClipDistance[0] = " + ENABLED + " ? dot(" + INVERSE
            + " * gl_Position, " + PLANE + ") : 1.0; }\n";
    }
}
