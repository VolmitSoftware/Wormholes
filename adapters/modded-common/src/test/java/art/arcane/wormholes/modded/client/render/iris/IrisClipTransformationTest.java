package art.arcane.wormholes.modded.client.render.iris;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IrisClipTransformationTest {
    private static final String VERTEX = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); }";
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
