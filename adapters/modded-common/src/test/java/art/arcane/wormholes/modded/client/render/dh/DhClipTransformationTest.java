package art.arcane.wormholes.modded.client.render.dh;

import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DhClipTransformationTest {
    private static final String TERRAIN = "assets/distanthorizons/shaders/terrain/gl/vert.vert";
    private static final String INSTANCED = "assets/distanthorizons/shaders/generic/gl/instanced/vert.vert";
    private static final String DIRECT = "assets/distanthorizons/shaders/generic/gl/direct/vert.vert";

    @Test
    public void terrainClipsAfterMicroOffsetCurvatureAndJitter() {
        String source = "#version 330 core\nin uvec4 vPosition; uniform mat4 uCombinedMatrix; uniform vec3 uModelOffset;"
            + " void main() { vec3 vertexWorldPos = vec3(vPosition.xyz) + uModelOffset;"
            + " vertexWorldPos.x += 0.01; vertexWorldPos.y += -2.0;"
            + " gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0); gl_Position.xy += vec2(0.01); }";
        String result = compact(DhClipTransformation.transform(TERRAIN, source));

        assertTrue(result.contains("voidwormholes_dhClipMain()"));
        assertTrue(result.contains("vertexWorldPos.x+=0.01;vertexWorldPos.y+=-2.0;"));
        assertTrue(result.contains("voidmain(){wormholes_dhClipMain();gl_ClipDistance[0]="
            + "wormholes_DhClipEnabled?dot(wormholes_DhClipInverse*gl_Position,wormholes_DhClipPlane):1.0;}"));
    }

    @Test
    public void genericInstanceAndDirectPositionsKeepTheirOwnTransforms() {
        String instanced = "#version 330 core\nuniform mat4 uProjectionMvm; in vec3 vPosition; in vec3 aScale;"
            + " void main() { vec3 trans = vec3(16.0); gl_Position = uProjectionMvm * vec4(vPosition * aScale + trans, 1.0); }";
        String direct = "#version 330 core\nuniform mat4 uTransform; in vec3 vPosition;"
            + " void main() { gl_Position = uTransform * vec4(vPosition, 1.0); if (gl_VertexID == 0) return; }";

        assertTrue(compact(DhClipTransformation.transform(INSTANCED, instanced))
            .contains("gl_Position=uProjectionMvm*vec4(vPosition*aScale+trans,1.0);"));
        String result = compact(DhClipTransformation.transform(DIRECT, direct));
        assertTrue(result.contains("gl_Position=uTransform*vec4(vPosition,1.0);if(gl_VertexID==0)return;"));
        assertTrue(result.contains("voidmain(){wormholes_dhClipMain();gl_ClipDistance[0]="));
    }

    @Test
    public void fullScreenAndFragmentProgramsStayUnchanged() {
        String source = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); }";

        assertSame(source, DhClipTransformation.transform("assets/distanthorizons/shaders/shared/gl/quad_apply.vert", source));
        assertSame(source, DhClipTransformation.transform("assets/distanthorizons/shaders/terrain/gl/frag.frag", source));
    }

    @Test
    public void malformedOrAlreadyClippedOwnedVertexIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DhClipTransformation.transform(TERRAIN, "#version 330 core\n"));
        assertThrows(IllegalArgumentException.class, () -> DhClipTransformation.transform(TERRAIN,
            "#version 330 core\nvoid main() {} void main(void) {}"));
        assertThrows(IllegalArgumentException.class, () -> DhClipTransformation.transform(TERRAIN,
            "#version 330 core\nvoid main() { gl_ClipDistance[0] = 1.0; }"));
    }

    private static String compact(String source) {
        return source.replaceAll("\\s+", "");
    }
}
