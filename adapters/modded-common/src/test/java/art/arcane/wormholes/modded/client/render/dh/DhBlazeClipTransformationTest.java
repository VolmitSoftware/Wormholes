package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.wormholes.modded.client.render.stencil.ClipShaderTransformation;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DhBlazeClipTransformationTest {
    @Test
    public void bundledTerrainAndGenericShadersUseUniformBlocksAndRetainOriginalBodies() throws IOException {
        for (String shader : new String[]{"terrain/blaze/vert", "generic/blaze/vert"}) {
            String source = resource(shader + ".vsh");
            String result = DhBlazeClipTransformation.transform(shader, source);
            String originalBody = source.replaceFirst("\\bvoid\\s+main\\s*\\(\\s*\\)", "void wormholes_dhBlazeMain()");

            assertTrue(result.contains("layout(std140) uniform wormholes_DhClipBlock"));
            assertTrue(result.contains("vec4 wormholes_DhClipPlane; bool wormholes_DhClipEnabled;"));
            assertTrue(result.contains("void main() { wormholes_dhBlazeMain(); gl_ClipDistance[0] = wormholes_DhClipEnabled"
                + " ? dot(gl_Position, wormholes_DhClipPlane) : 1.0; }"));
            assertEquals(originalBody, result.substring(0, originalBody.length()));
            assertFalse(ClipShaderTransformation.vertex("distanthorizons:" + shader, result).isPresent());
        }
    }

    @Test
    public void postProcessingAndFragmentsStayUnchanged() throws IOException {
        String source = resource("apply/blaze/vert.vsh");
        assertSame(source, DhBlazeClipTransformation.transform("apply/blaze/vert", source));
        String fragment = resource("terrain/blaze/frag.fsh");
        assertSame(fragment, DhBlazeClipTransformation.transform("terrain/blaze/frag", fragment));
    }

    @Test
    public void ownedShadersRejectConflictingClippingAndAmbiguousEntryPoints() {
        assertThrows(IllegalArgumentException.class, () -> DhBlazeClipTransformation.transform("terrain/blaze/vert", ""));
        assertThrows(IllegalArgumentException.class, () -> DhBlazeClipTransformation.transform("terrain/blaze/vert",
            "void main() {} void main(void) {}"));
        assertThrows(IllegalArgumentException.class, () -> DhBlazeClipTransformation.transform("generic/blaze/vert",
            "void main() { gl_ClipDistance[0] = 1.0; }"));
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = DhBlazeClipTransformationTest.class.getClassLoader()
            .getResourceAsStream("assets/distanthorizons/shaders/" + path)) {
            if (stream == null) {
                throw new IOException("Missing Distant Horizons shader: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
