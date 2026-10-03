package art.arcane.wormholes.modded.client.render;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalIrisClippingTest {
    private static final String VERTEX = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); }";
    private static final String FRAGMENT = "#version 330 core\nout vec4 color; void main() { color = vec4(1.0); }";

    @Test
    public void cachedSourcesAndFragmentRemainUnchanged() {
        Map<PatchShaderType, String> original = Map.of(PatchShaderType.VERTEX, VERTEX,
            PatchShaderType.FRAGMENT, FRAGMENT);
        PortalIrisClipping.Result result = PortalIrisClipping.transform(original, 8);

        assertEquals(0, result.clipDistance());
        assertEquals(Set.of(), result.existingDistances());
        assertSame(VERTEX, original.get(PatchShaderType.VERTEX));
        assertSame(FRAGMENT, result.sources().get(PatchShaderType.FRAGMENT));
        assertNotEquals(VERTEX, result.sources().get(PatchShaderType.VERTEX));
        assertThrows(UnsupportedOperationException.class,
            () -> result.sources().put(PatchShaderType.FRAGMENT, "changed"));
    }

    @Test
    public void olderVersionIdentifiersAreParsedWithTheirDeclaredLanguageVersion() {
        String source = "#version 330 core\nvoid main() { float sample = 1.0; gl_Position = vec4(sample); }";
        String converted = PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, source), 8)
            .sources().get(PatchShaderType.VERTEX);

        assertTrue(converted.contains("#version 330 core"));
        assertTrue(compact(converted).contains("floatsample=1.0"));
    }

    @Test
    public void vertexEarlyReturnsStillComputeDistanceAfterOriginalMain() {
        String source = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); if (gl_VertexID == 0) return;"
            + " gl_Position.z = 2.0; }";
        TranslationUnit tree = parse(PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, source), 8)
            .sources().get(PatchShaderType.VERTEX));
        String main = body(tree, "main");

        assertTrue(main.indexOf("wormholes_clipMain()") < main.indexOf("gl_ClipDistance[0]"));
        assertTrue(main.contains("dot(gl_Position,wormholes_ClipPlane)"));
        assertTrue(body(tree, "wormholes_clipMain").contains("return;"));
    }

    @Test
    public void tessellationEvaluationIsTheFinalStage() {
        String evaluation = "#version 400 core\nlayout(triangles) in; void main() { gl_Position = gl_in[0].gl_Position; }";
        String control = "#version 400 core\nlayout(vertices=3) out; void main() {"
            + " gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position; }";
        PortalIrisClipping.Result result = PortalIrisClipping.transform(Map.of(
            PatchShaderType.VERTEX, VERTEX, PatchShaderType.TESS_CONTROL, control,
            PatchShaderType.TESS_EVAL, evaluation), 8);

        assertSame(VERTEX, result.sources().get(PatchShaderType.VERTEX));
        assertSame(control, result.sources().get(PatchShaderType.TESS_CONTROL));
        assertTrue(body(parse(result.sources().get(PatchShaderType.TESS_EVAL)), "main")
            .contains("gl_ClipDistance[0]"));
    }

    @Test
    public void everyGeometryEmissionClipsAfterPositionAndDepthChanges() {
        String geometry = "#version 400 core\nlayout(points) in; layout(points,max_vertices=2) out;"
            + " void main() { gl_Position = gl_in[0].gl_Position; gl_Position.z = -gl_Position.z; EmitVertex();"
            + " gl_Position.x += 1.0; EmitVertex(); EndPrimitive(); }";
        PortalIrisClipping.Result result = PortalIrisClipping.transform(Map.of(
            PatchShaderType.VERTEX, VERTEX, PatchShaderType.GEOMETRY, geometry), 8);
        TranslationUnit tree = parse(result.sources().get(PatchShaderType.GEOMETRY));
        String main = body(tree, "main");

        assertSame(VERTEX, result.sources().get(PatchShaderType.VERTEX));
        assertEquals(2, calls(tree, "EmitVertex"));
        assertEquals(2, calls(tree, "dot"));
        assertTrue(main.contains("(gl_ClipDistance[0]=dot(gl_Position,wormholes_ClipPlane),EmitVertex())"));
        assertTrue(main.indexOf("gl_Position.z=-gl_Position.z") < main.indexOf("gl_ClipDistance[0]"));
    }

    @Test
    public void geometryStreamArgumentRemainsAConstantExpression() {
        String geometry = "#version 400 core\nlayout(points) in; layout(points,max_vertices=1) out;"
            + " const int stream = 1; void main() { gl_Position = gl_in[0].gl_Position; EmitStreamVertex(stream); }";
        TranslationUnit tree = parse(PortalIrisClipping.transform(Map.of(PatchShaderType.GEOMETRY, geometry), 8)
            .sources().get(PatchShaderType.GEOMETRY));
        assertEquals(1, calls(tree, "EmitStreamVertex"));
        assertTrue(body(tree, "main").contains(
            "(gl_ClipDistance[0]=dot(gl_Position,wormholes_ClipPlane),EmitStreamVertex(stream))"));
    }

    @Test
    public void geometryTakesPrecedenceOverTessellationEvaluation() {
        String evaluation = "#version 400 core\nlayout(triangles) in; void main() { gl_Position = gl_in[0].gl_Position; }";
        String geometry = "#version 400 core\nlayout(triangles) in; layout(points,max_vertices=1) out;"
            + " void main() { gl_Position = gl_in[0].gl_Position; EmitVertex(); }";
        PortalIrisClipping.Result result = PortalIrisClipping.transform(Map.of(
            PatchShaderType.TESS_EVAL, evaluation, PatchShaderType.GEOMETRY, geometry), 8);

        assertSame(evaluation, result.sources().get(PatchShaderType.TESS_EVAL));
        assertTrue(body(parse(result.sources().get(PatchShaderType.GEOMETRY)), "main")
            .contains("gl_ClipDistance[0]"));
    }

    @Test
    public void existingPackDistancesArePreservedAcrossStagesAndDeclarationsGrow() {
        String vertex = "#version 330 core\nout gl_PerVertex { vec4 gl_Position; float gl_ClipDistance[1]; };"
            + " void main() { gl_Position = vec4(1.0); gl_ClipDistance[0] = 3.0; }";
        String geometry = "#version 400 core\nlayout(points) in; layout(points,max_vertices=1) out;"
            + " in gl_PerVertex { vec4 gl_Position; float gl_ClipDistance[1]; } gl_in[];"
            + " out gl_PerVertex { vec4 gl_Position; float gl_ClipDistance[1]; };"
            + " void main() { gl_Position = gl_in[0].gl_Position; gl_ClipDistance[0] = gl_in[0].gl_ClipDistance[0];"
            + " EmitVertex(); }";
        PortalIrisClipping.Result result = PortalIrisClipping.transform(Map.of(
            PatchShaderType.VERTEX, vertex, PatchShaderType.GEOMETRY, geometry), 8);
        String convertedVertex = compact(result.sources().get(PatchShaderType.VERTEX));
        String convertedGeometry = compact(result.sources().get(PatchShaderType.GEOMETRY));

        assertEquals(1, result.clipDistance());
        assertEquals(Set.of(0), result.existingDistances());
        assertThrows(UnsupportedOperationException.class, () -> result.existingDistances().add(1));
        assertTrue(convertedVertex.contains("floatgl_ClipDistance[2]"));
        assertTrue(convertedVertex.contains("gl_ClipDistance[0]=3.0"));
        assertTrue(convertedGeometry.contains("gl_ClipDistance[0]=gl_in[0].gl_ClipDistance[0]"));
        assertTrue(convertedGeometry.contains("gl_ClipDistance[1]=dot(gl_Position,wormholes_ClipPlane)"));
    }

    @Test
    public void dynamicPackDistanceAccessCannotSilentlyOverwriteOutput() {
        String source = "#version 330 core\nvoid main() { gl_Position = vec4(1.0);"
            + " gl_ClipDistance[gl_VertexID] = 1.0; }";

        assertThrows(IllegalArgumentException.class,
            () -> PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, source), 8));
    }

    @Test
    public void deviceLimitAndReservedIdentifiersAreValidated() {
        String occupied = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); gl_ClipDistance[0] = 1.0; }";
        String collision = "#version 330 core\nuniform vec4 wormholes_ClipPlane; void main() { gl_Position = vec4(1.0); }";

        assertThrows(IllegalArgumentException.class,
            () -> PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, occupied), 1));
        assertThrows(IllegalArgumentException.class,
            () -> PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, VERTEX), 0));
        assertThrows(IllegalArgumentException.class,
            () -> PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, collision), 8));
    }

    @Test
    public void nullAbsentStagesAreRetainedWithoutInventingSource() {
        EnumMap<PatchShaderType, String> original = new EnumMap<>(PatchShaderType.class);
        original.put(PatchShaderType.VERTEX, VERTEX);
        original.put(PatchShaderType.GEOMETRY, null);
        PortalIrisClipping.Result result = PortalIrisClipping.transform(original, 8);

        assertTrue(result.sources().containsKey(PatchShaderType.GEOMETRY));
        assertEquals(null, result.sources().get(PatchShaderType.GEOMETRY));
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
