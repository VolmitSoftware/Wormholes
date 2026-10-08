package art.arcane.wormholes.modded.client.render.stencil;

import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClipShaderTransformationTest {
    private static final String PROJECTION = """
        #ifndef MINECRAFT_PROJECTION_GLSL
        #define MINECRAFT_PROJECTION_GLSL

        layout(std140) uniform Projection {
            mat4 ProjMat;
        };

        #endif
        """;
    private static final String TERRAIN = """
        #version 330
        #include <minecraft:projection.glsl>
        layout(location = 0) in vec3 Position;
        void main() {
            gl_Position = ProjMat * vec4(Position, 1.0);
        }
        """;

    @Test
    public void projectionBlockGainsTheClipPlaneAfterTheMatrix() {
        Optional<String> transformed = ClipShaderTransformation.projection(PROJECTION);
        assertTrue(transformed.isPresent());
        String source = transformed.get();
        assertTrue(source.indexOf("mat4 ProjMat;") < source.indexOf("vec4 " + ClipShaderTransformation.CLIP_PLANE + ";"));
        assertTrue(source.indexOf("vec4 " + ClipShaderTransformation.CLIP_PLANE + ";") < source.indexOf("};"));
        assertTrue(source.indexOf("#define " + ClipShaderTransformation.CLIP_PLANE_DEFINE + "\n") < source.indexOf("layout(std140) uniform Projection"));
    }

    @Test
    public void projectionWithoutTheVanillaBlockIsLeftAlone() {
        assertFalse(ClipShaderTransformation.projection("uniform Other { mat4 ProjMat; };").isPresent());
    }

    @Test
    public void worldVertexShaderWritesTheClipDistanceAfterItsOwnMain() {
        String source = ClipShaderTransformation.vertex("minecraft:core/terrain", TERRAIN).orElseThrow();
        assertEquals(1, count(source, "void main()"));
        assertTrue(source.contains("void wormholes_main()"));
        assertTrue(source.indexOf("wormholes_main();") > source.indexOf("void main()"));
        assertTrue(source.contains("gl_ClipDistance[0] = dot(gl_Position, " + ClipShaderTransformation.CLIP_PLANE + ");"));
    }

    @Test
    public void skyAndShadersWithoutTheProjectionIncludeAreNeverClipped() {
        assertTrue(ClipShaderTransformation.vertex("minecraft:core/sky", TERRAIN).orElseThrow().contains("gl_ClipDistance[0] = 1.0;"));
        String screen = "#version 330\nvoid main(void) {\n    gl_Position = vec4(0.0);\n}\n";
        assertTrue(ClipShaderTransformation.vertex("minecraft:core/screenquad", screen).orElseThrow().contains("gl_ClipDistance[0] = 1.0;"));
    }

    @Test
    public void shadersThatAlreadyClipOrHaveSeveralMainsAreLeftAlone() {
        assertFalse(ClipShaderTransformation.vertex("wormholes:core/portal", TERRAIN.replace("}", "gl_ClipDistance[0] = 1.0;\n}")).isPresent());
        String twice = "#ifdef A\nvoid main() {}\n#else\nvoid main() {}\n#endif\n";
        assertFalse(ClipShaderTransformation.vertex("minecraft:core/odd", twice).isPresent());
        assertFalse(ClipShaderTransformation.vertex("minecraft:core/none", "#version 330\n").isPresent());
    }

    private static int count(String source, String token) {
        int found = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            found++;
            index = source.indexOf(token, index + token.length());
        }
        return found;
    }
}
