package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.client.render.stencil.ClipShaderTransformation;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SodiumClipShadersTest {
    private static final String GLOBALS = """
        layout(std140) uniform u_Globals {
            mat4 u_ProjectionMatrix;
            mat4 u_ModelViewMatrix;

            vec4 u_FogColor;
            vec2 u_EnvironmentFog;
            vec2 u_RenderFog;

            vec2 u_TexelSize;
            vec2 u_TexCoordShrink;

            float u_FadePeriodInv;
            bool u_UseRGSS;
        };""";

    @Test
    public void theClipPlaneIsAppendedAfterTheLastSodiumGlobal() {
        SodiumClipShaders.Globals globals = SodiumClipShaders.transform(GLOBALS).orElseThrow();
        String source = globals.source();
        String member = "vec4 " + ClipShaderTransformation.CLIP_PLANE + ";";
        assertTrue(source.indexOf("bool u_UseRGSS;") < source.indexOf(member));
        assertTrue(source.indexOf(member) < source.indexOf("};"));
        assertTrue(source.indexOf("#define " + ClipShaderTransformation.CLIP_PLANE_DEFINE + "\n") < source.indexOf("layout(std140)"));
        assertEquals(192, globals.planeOffset());
    }

    @Test
    public void thePlaneOffsetFollowsStd140Alignment() {
        assertEquals(16, offset("float a;"));
        assertEquals(16, offset("vec3 a;\n    float b;"));
        assertEquals(80, offset("mat4 a;\n    int b;"));
        assertEquals(64, offset("vec2 a;\n    mat3 b;"));
        assertEquals(32, offset("uint a; ivec4 b;"));
        assertEquals(16, offset("// leading note\n    vec2 a; // trailing note\n    uvec2 b;"));
    }

    @Test
    public void unknownMembersArraysAndMissingBlocksAreLeftAlone() {
        assertFalse(SodiumClipShaders.transform(block("sampler2D a;")).isPresent());
        assertFalse(SodiumClipShaders.transform(block("float a[4];")).isPresent());
        assertFalse(SodiumClipShaders.transform("uniform Other {\n    mat4 a;\n};").isPresent());
        assertFalse(SodiumClipShaders.transform(SodiumClipShaders.transform(GLOBALS).orElseThrow().source()).isPresent());
    }

    private static int offset(String members) {
        return SodiumClipShaders.transform(block(members)).orElseThrow().planeOffset();
    }

    private static String block(String members) {
        return "layout(std140) uniform u_Globals {\n    " + members + "\n};\n";
    }
}
