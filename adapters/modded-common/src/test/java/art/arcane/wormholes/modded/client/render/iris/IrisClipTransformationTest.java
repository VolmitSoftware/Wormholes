package art.arcane.wormholes.modded.client.render.iris;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IrisClipTransformationTest {
    private static final String VERTEX = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); }";
    private static final String DISTANT_VERTEX = "#version 330 core\nuniform mat4 iris_ProjectionMatrix;"
        + " uniform mat4 iris_ModelViewMatrix; vec3 _vert_position;"
        + " vec4 getVertexPosition() { return vec4(_vert_position, 1.0); }"
        + " void main() { _vert_position = vec3(1.0);"
        + " gl_Position = iris_ProjectionMatrix * iris_ModelViewMatrix * getVertexPosition(); gl_Position.z = -gl_Position.z; }";
    private static final String FRAGMENT = "#version 330 core\nout vec4 color; void main() { color = vec4(1.0); }";

    @Test
    public void vertexClipsAfterTheOriginalMainIncludingEarlyReturns() {
        String source = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); if (gl_VertexID == 0) return; gl_Position.z = 2.0; }";
        Map<PatchShaderType, String> result = IrisClipTransformation.transform(Map.of(PatchShaderType.VERTEX, source,
            PatchShaderType.FRAGMENT, FRAGMENT));
        TranslationUnit tree = parse(result.get(PatchShaderType.VERTEX));
        String main = body(tree, "main");

        assertTrue(main.indexOf("wormholes_clipMain()") < main.indexOf("gl_ClipDistance[0]"));
        assertTrue(main.contains("dot(gl_Position,wormholes_ClipPlane0)"));
        assertTrue(compact(result.get(PatchShaderType.VERTEX)).contains("uniformvec4wormholes_ClipPlane0;"));
        assertTrue(body(tree, "wormholes_clipMain").contains("return;"));
        assertSame(FRAGMENT, result.get(PatchShaderType.FRAGMENT));
    }

    @Test
    public void tessellationEvaluationIsTheFinalStage() {
        String evaluation = "#version 400 core\nlayout(triangles) in; void main() { gl_Position = gl_in[0].gl_Position; }";
        Map<PatchShaderType, String> result = IrisClipTransformation.transform(Map.of(PatchShaderType.VERTEX, VERTEX,
            PatchShaderType.TESS_EVAL, evaluation));

        assertSame(VERTEX, result.get(PatchShaderType.VERTEX));
        assertTrue(body(parse(result.get(PatchShaderType.TESS_EVAL)), "main").contains("gl_ClipDistance[0]"));
    }

    @Test
    public void everyGeometryEmissionClipsAfterThePositionRemap() {
        String geometry = "#version 400 core\nlayout(points) in; layout(points,max_vertices=2) out;"
            + " void main() { gl_Position = gl_in[0].gl_Position; (gl_Position.z = -gl_Position.z, EmitVertex());"
            + " gl_Position.x += 1.0; EmitVertex(); EndPrimitive(); }";
        Map<PatchShaderType, String> result = IrisClipTransformation.transform(Map.of(PatchShaderType.VERTEX, VERTEX,
            PatchShaderType.GEOMETRY, geometry));
        TranslationUnit tree = parse(result.get(PatchShaderType.GEOMETRY));
        String main = body(tree, "main");

        assertSame(VERTEX, result.get(PatchShaderType.VERTEX));
        assertEquals(2, calls(tree, "EmitVertex"));
        assertEquals(2, calls(tree, "dot"));
        assertTrue(main.contains("(gl_Position.z=-gl_Position.z,(gl_ClipDistance[0]=dot(gl_Position,wormholes_ClipPlane0),EmitVertex()))"));
    }

    @Test
    public void packClipDistancesKeepTheirSlotAndDeclarationsGrow() {
        String vertex = "#version 330 core\nout gl_PerVertex { vec4 gl_Position; float gl_ClipDistance[1]; };"
            + " void main() { gl_Position = vec4(1.0); gl_ClipDistance[0] = 3.0; }";
        String converted = compact(IrisClipTransformation.transform(Map.of(PatchShaderType.VERTEX, vertex)).get(PatchShaderType.VERTEX));

        assertTrue(converted.contains("floatgl_ClipDistance[2]"));
        assertTrue(converted.contains("gl_ClipDistance[0]=3.0"));
        assertTrue(converted.contains("gl_ClipDistance[1]=dot(gl_Position,wormholes_ClipPlane1)"));
    }

    @Test
    public void unsupportedProgramsAreRefused() {
        String dynamic = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); gl_ClipDistance[gl_VertexID] = 1.0; }";
        String reserved = "#version 330 core\nuniform vec4 wormholes_ClipPlane0; void main() { gl_Position = vec4(1.0); }";

        assertThrows(IllegalArgumentException.class, () -> IrisClipTransformation.transform(Map.of(PatchShaderType.VERTEX, dynamic)));
        assertThrows(IllegalArgumentException.class, () -> IrisClipTransformation.transform(Map.of(PatchShaderType.VERTEX, reserved)));
        assertThrows(IllegalArgumentException.class, () -> IrisClipTransformation.transform(Map.of(PatchShaderType.FRAGMENT, FRAGMENT)));
    }

    @Test
    public void absentStagesStayAbsent() {
        EnumMap<PatchShaderType, String> original = new EnumMap<>(PatchShaderType.class);
        original.put(PatchShaderType.VERTEX, VERTEX);
        original.put(PatchShaderType.GEOMETRY, null);
        Map<PatchShaderType, String> result = IrisClipTransformation.transform(original);

        assertTrue(result.containsKey(PatchShaderType.GEOMETRY));
        assertEquals(null, result.get(PatchShaderType.GEOMETRY));
    }

    @Test
    public void distantVertexClipsDecodedCameraRelativeTerrainWithoutProjection() {
        String vertex = "#version 330 core\nuniform mat4 iris_ProjectionMatrix; uniform mat4 iris_ModelViewMatrix;"
            + " uniform vec3 modelOffset; in uvec4 vPosition; vec3 _vert_position;"
            + " vec4 getVertexPosition() { return vec4(modelOffset + _vert_position, 1.0); }"
            + " void main() { _vert_position = vec3(vPosition.xyz) + vec3(0.01, 0.0, -0.01);"
            + " gl_Position = iris_ProjectionMatrix * iris_ModelViewMatrix * vec4(modelOffset + _vert_position, 1.0);"
            + " gl_Position.z = -gl_Position.z; }";
        Map<PatchShaderType, String> result = IrisClipTransformation.transformDistant(Map.of(PatchShaderType.VERTEX, vertex,
            PatchShaderType.FRAGMENT, FRAGMENT));
        TranslationUnit tree = parse(result.get(PatchShaderType.VERTEX));
        String main = body(tree, "main");

        assertTrue(main.contains("dot(getVertexPosition(),wormholes_ClipPlane0)"));
        assertTrue(main.indexOf("wormholes_clipMain()") < main.indexOf("gl_ClipDistance[0]"));
        assertEquals(body(parse(vertex), "main"), body(tree, "wormholes_clipMain"));
        assertFalse(compact(result.get(PatchShaderType.VERTEX)).contains("wormholes_ClipProjection"));
        assertEquals(1, calls(tree, "dot"));
        assertSame(FRAGMENT, result.get(PatchShaderType.FRAGMENT));
    }

    @Test
    public void distantGeometryMapsEveryEmissionAndKeepsPackClipSlots() {
        String geometry = "#version 400 core\nlayout(points) in; layout(points,max_vertices=2) out;"
            + " void main() { gl_Position = gl_in[0].gl_Position; gl_ClipDistance[0] = 2.0;"
            + " (gl_Position.z = -gl_Position.z, EmitVertex()); gl_Position.x += 1.0; EmitVertex(); EndPrimitive(); }";
        Map<PatchShaderType, String> result = IrisClipTransformation.transformDistant(Map.of(PatchShaderType.VERTEX, DISTANT_VERTEX,
            PatchShaderType.GEOMETRY, geometry));
        TranslationUnit tree = parse(result.get(PatchShaderType.GEOMETRY));
        String main = body(tree, "main");

        assertSame(DISTANT_VERTEX, result.get(PatchShaderType.VERTEX));
        assertEquals(2, calls(tree, "EmitVertex"));
        assertEquals(4, calls(tree, "dot"));
        assertTrue(main.contains("gl_ClipDistance[0]=2.0"));
        assertTrue(main.contains("(gl_Position.z=-gl_Position.z,(gl_ClipDistance[1]="
            + "dot(iris_ModelViewMatrixInverse*iris_ProjectionMatrixInverse*vec4(gl_Position.xy,dot(gl_Position.zw,wormholes_ClipDepth),gl_Position.w),wormholes_ClipPlane1),EmitVertex()))"));
    }

    @Test
    public void distantTessellationClipsAtTheEvaluationStage() {
        String evaluation = "#version 400 core\nlayout(triangles) in; void main() { gl_Position = gl_in[0].gl_Position; }";
        Map<PatchShaderType, String> result = IrisClipTransformation.transformDistant(Map.of(PatchShaderType.VERTEX, DISTANT_VERTEX,
            PatchShaderType.TESS_EVAL, evaluation));

        assertSame(DISTANT_VERTEX, result.get(PatchShaderType.VERTEX));
        assertTrue(body(parse(result.get(PatchShaderType.TESS_EVAL)), "main")
            .contains("dot(iris_ModelViewMatrixInverse*iris_ProjectionMatrixInverse*vec4(gl_Position.xy,"
                + "dot(gl_Position.zw,wormholes_ClipDepth),gl_Position.w),wormholes_ClipPlane0)"));
    }

    @Test
    public void distantGenericKeepsCameraRelativeInstanceCoordinates() {
        String vertex = "#version 330 core\nuniform mat4 iris_ProjectionMatrix; uniform mat4 iris_ModelViewMatrix;"
            + " uniform ivec3 uOffsetChunk; uniform ivec3 uCameraPosChunk; uniform vec3 uOffsetSubChunk;"
            + " uniform vec3 uCameraPosSubChunk; in ivec3 aTranslateChunk; in vec3 aTranslateSubChunk;"
            + " in vec3 aScale; in vec3 vPosition; vec3 _vert_position;"
            + " vec4 getVertexPosition() { return vec4(_vert_position, 1.0); } void main() {"
            + " vec3 trans = vec3(aTranslateChunk + uOffsetChunk - uCameraPosChunk) * 16.0;"
            + " trans += aTranslateSubChunk + uOffsetSubChunk - uCameraPosSubChunk;"
            + " _vert_position = vPosition * aScale + trans;"
            + " gl_Position = iris_ProjectionMatrix * iris_ModelViewMatrix * getVertexPosition();"
            + " gl_Position.z = 0.5 * (gl_Position.w - gl_Position.z); }";
        TranslationUnit tree = parse(IrisClipTransformation.transformDistant(Map.of(PatchShaderType.VERTEX, vertex))
            .get(PatchShaderType.VERTEX));

        assertTrue(body(tree, "main").contains("dot(getVertexPosition(),wormholes_ClipPlane0)"));
        assertEquals(body(parse(vertex), "main"), body(tree, "wormholes_clipMain"));
    }

    @Test
    public void distantPackProjectionAndTemporalJitterStayInsideTheOriginalMain() {
        String vertex = "#version 330 core\nuniform mat4 dhProjection; uniform mat4 gbufferModelView;"
            + " uniform mat4 gbufferModelViewInverse; uniform mat4 iris_ModelViewMatrix;"
            + " uniform vec3 modelOffset; uniform vec2 jitter; in vec3 vPosition;"
            + " vec4 getVertexPosition() { return vec4(modelOffset + vPosition, 1.0); }"
            + " void iris_depthMain() { vec4 decoded = getVertexPosition();"
            + " vec4 position = gbufferModelViewInverse * iris_ModelViewMatrix * decoded;"
            + " gl_Position = dhProjection * gbufferModelView * position;"
            + " gl_Position.xy += jitter * gl_Position.w; }"
            + " void main() { iris_depthMain(); gl_Position.z = -gl_Position.z; }";
        TranslationUnit tree = parse(IrisClipTransformation.transformDistant(Map.of(PatchShaderType.VERTEX, vertex))
            .get(PatchShaderType.VERTEX));

        assertEquals(body(parse(vertex), "iris_depthMain"), body(tree, "iris_depthMain"));
        assertEquals(body(parse(vertex), "main"), body(tree, "wormholes_clipMain"));
        assertTrue(body(tree, "main").contains("wormholes_clipMain();gl_ClipDistance[0]="
            + "dot(getVertexPosition(),wormholes_ClipPlane0)"));
    }

    @Test
    public void distantGeometryUsesThePackProjectionAndClipsEveryStreamEmission() {
        String vertex = DISTANT_VERTEX.replace("uniform mat4 iris_ProjectionMatrix;", "uniform mat4 dhProjection;")
            .replace("iris_ProjectionMatrix *", "dhProjection *");
        String geometry = "#version 400 core\nlayout(points) in; layout(points,max_vertices=2) out;"
            + " void main() { gl_Position = gl_in[0].gl_Position; EmitStreamVertex(0);"
            + " gl_Position.x += 1.0; EmitStreamVertex(0); EndStreamPrimitive(0); }";
        TranslationUnit tree = parse(IrisClipTransformation.transformDistant(Map.of(PatchShaderType.VERTEX, vertex,
            PatchShaderType.GEOMETRY, geometry)).get(PatchShaderType.GEOMETRY));
        String converted = compact(ASTPrinter.printSimple(tree));

        assertEquals(2, calls(tree, "EmitStreamVertex"));
        assertEquals(4, calls(tree, "dot"));
        assertEquals(0, calls(tree, "inverse"));
        assertTrue(converted.contains("uniformmat4dhProjectionInverse;"));
        assertTrue(converted.contains("uniformmat4iris_ModelViewMatrixInverse;"));
        assertTrue(body(tree, "main").contains("dot(iris_ModelViewMatrixInverse*dhProjectionInverse*vec4("));
        assertFalse(converted.contains("iris_ProjectionMatrixInverse"));
    }

    @Test
    public void distantEmittingStageUsesItsOwnProjectionAfterReprojecting() {
        String vertex = DISTANT_VERTEX.replace("uniform mat4 iris_ProjectionMatrix;", "uniform mat4 dhProjection;")
            .replace("iris_ProjectionMatrix *", "dhProjection *");
        String evaluation = "#version 400 core\nlayout(triangles) in; uniform mat4 iris_ProjectionMatrix;"
            + " void main() { gl_Position = iris_ProjectionMatrix * vec4(1.0); }";
        String converted = compact(IrisClipTransformation.transformDistant(Map.of(PatchShaderType.VERTEX, vertex,
            PatchShaderType.TESS_EVAL, evaluation)).get(PatchShaderType.TESS_EVAL));

        assertTrue(converted.contains("iris_ModelViewMatrixInverse*iris_ProjectionMatrixInverse*"));
        assertFalse(converted.contains("dhProjectionInverse"));
    }

    @Test
    public void distantEmittingCoordinatesPreserveBothSidesDuringCameraTurnsAndProjectionChanges() {
        Vector4f plane = new Vector4f(0.0F, 0.0F, 1.0F, 0.0F);
        for (boolean zeroToOne : new boolean[]{false, true}) {
            for (float turn : new float[]{0.0F, 0.9F, 2.7F}) {
                Matrix4f modelView = new Matrix4f().rotateX(0.2F).rotateY(turn);
                Matrix4f inverseView = new Matrix4f(modelView).invert();
                for (float fov : new float[]{0.8F, 1.2F}) {
                    Matrix4f projection = new Matrix4f().setPerspective(fov, 1.6F, 1.47F, 2176.0F);
                    Matrix4f inverseProjection = new Matrix4f(projection).invert();
                    for (float distance : new float[]{-256.0F, -1.0F, 1.0F, 256.0F}) {
                        Vector4f position = new Vector4f(7.0F, -3.0F, distance, 1.0F);
                        Vector4f emitted = projection.transform(modelView.transform(new Vector4f(position)));
                        emitted.z = zeroToOne ? 0.5F * (emitted.w - emitted.z) : -emitted.z;
                        emitted.z = zeroToOne ? emitted.w - 2.0F * emitted.z : -emitted.z;
                        Vector4f reconstructed = inverseView.transform(inverseProjection.transform(emitted));

                        assertEquals(distance, plane.dot(reconstructed), 0.01F);
                        assertEquals(distance >= 0.0F, plane.dot(reconstructed) >= 0.0F);
                    }
                }
            }
        }
    }

    private static TranslationUnit parse(String source) {
        return new ASTParser().parseTranslationUnit(RootSupplier.PREFIX_UNORDERED_ED_EXACT, source);
    }

    private static String body(TranslationUnit tree, String function) {
        return compact(ASTPrinter.printSimple(tree.getOneFunctionDefinitionBody(function)));
    }

    private static String compact(String source) {
        return source.replaceAll("\\s+", "");
    }

    private static long calls(TranslationUnit tree, String function) {
        return tree.getRoot().nodeIndex.getStream(FunctionCallExpression.class)
            .filter(call -> call.getFunctionName() != null && call.getFunctionName().getName().equals(function)).count();
    }
}
