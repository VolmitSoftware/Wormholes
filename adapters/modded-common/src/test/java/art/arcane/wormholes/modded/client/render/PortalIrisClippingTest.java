package art.arcane.wormholes.modded.client.render;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalIrisClippingTest {
    private static final String VERTEX = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); }";
    private static final String FRAGMENT = "#version 330 core\nout vec4 color; void main() { color = vec4(1.0); }";

    @Test
    public void destinationTerrainReportsCompletedChunkFadeAcrossStages() {
        String vertex = "#version 330 core\nconst float mc_chunkFade = -1.0; out float chunkFade;"
            + " void main() { chunkFade = mc_chunkFade; gl_Position = vec4(1.0); }";
        String fragment = "#version 330 core\nconst float mc_chunkFade = -1.0; in float chunkFade; out vec4 color;"
            + " void main() { color = vec4(chunkFade, mc_chunkFade, -1.0, 1.0); }";
        Map<PatchShaderType, String> original = Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, fragment);
        PortalIrisClipping.Result result = PortalIrisClipping.transform(original, 8, true);

        assertTrue(compact(result.sources().get(PatchShaderType.VERTEX)).contains("constfloatmc_chunkFade=1.0f;"));
        assertTrue(compact(result.sources().get(PatchShaderType.FRAGMENT)).contains("constfloatmc_chunkFade=1.0f;"));
        assertTrue(body(parse(result.sources().get(PatchShaderType.VERTEX)), "wormholes_clipMain")
            .contains("chunkFade=mc_chunkFade;"));
        assertTrue(body(parse(result.sources().get(PatchShaderType.FRAGMENT)), "main")
            .contains("vec4(chunkFade,mc_chunkFade,-1.0f,1.0f)"));
        assertSame(vertex, original.get(PatchShaderType.VERTEX));
        assertSame(fragment, original.get(PatchShaderType.FRAGMENT));
        assertTrue(vertex.contains("mc_chunkFade = -1.0"));
    }

    @Test
    public void runtimeChunkFadeAndUnrelatedSentinelsArePreserved() {
        String vertex = "#version 330 core\nfloat mc_chunkFade; const float other_chunkFade = -1.0;"
            + " void main() { mc_chunkFade = clamp(float(gl_VertexID), 0.0, 1.0); gl_Position = vec4(other_chunkFade); }";
        String fragment = "#version 330 core\nconst float mc_chunkFade = 0.25; out vec4 color;"
            + " void main() { color = vec4(mc_chunkFade); }";
        PortalIrisClipping.Result result = PortalIrisClipping.transform(
            Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, fragment), 8, true);

        assertTrue(compact(result.sources().get(PatchShaderType.VERTEX)).contains("floatmc_chunkFade;"));
        assertTrue(compact(result.sources().get(PatchShaderType.VERTEX)).contains("constfloatother_chunkFade=-1.0f;"));
        assertTrue(body(parse(result.sources().get(PatchShaderType.VERTEX)), "wormholes_clipMain")
            .contains("mc_chunkFade=clamp(float(gl_VertexID),0.0f,1.0f)"));
        assertSame(fragment, result.sources().get(PatchShaderType.FRAGMENT));
    }

    @Test
    public void cachedEntityAndTerrainProgramsDoNotShareChunkFadeNormalization() {
        PortalIrisClipping.clear();
        String vertex = "#version 330 core\nconst float mc_chunkFade = -1.0; void main() { gl_Position = vec4(mc_chunkFade); }";
        Map<PatchShaderType, String> sources = Map.of(PatchShaderType.VERTEX, vertex);
        PortalIrisClipping.Result entity = PortalIrisClipping.transform(sources, 8, false);
        PortalIrisClipping.Result terrain = PortalIrisClipping.transform(sources, 8, true);

        assertNotSame(entity, terrain);
        assertTrue(compact(entity.sources().get(PatchShaderType.VERTEX)).contains("constfloatmc_chunkFade=-1.0f;"));
        assertTrue(compact(terrain.sources().get(PatchShaderType.VERTEX)).contains("constfloatmc_chunkFade=1.0f;"));
        assertSame(entity, PortalIrisClipping.transform(sources, 8, false));
        assertSame(terrain, PortalIrisClipping.transform(sources, 8, true));
        PortalIrisClipping.clear();
    }

    @Test
    public void mainSceneSkyAndShadowCompilationKeepTheirChunkFadeSources() {
        String source = "#version 330 core\nconst float mc_chunkFade = -1.0; void main() { gl_Position = vec4(mc_chunkFade); }";
        Map<PatchShaderType, String> sources = Map.of(PatchShaderType.VERTEX, source);
        assertFalse(PortalIrisClipCompilation.active());
        assertNull(PortalIrisClipCompilation.transform(sources));
        for (ShaderKey key : new ShaderKey[] {ShaderKey.SKY_BASIC, ShaderKey.CLOUDS, ShaderKey.SHADOW_TERRAIN_CUTOUT}) {
            try (PortalIrisClipCompilation compilation = PortalIrisClipCompilation.open(key)) {
                assertFalse(PortalIrisClipCompilation.active());
                assertNull(PortalIrisClipCompilation.transform(sources));
                assertSame(source, sources.get(PatchShaderType.VERTEX));
            }
        }
        try (PortalIrisClipCompilation compilation = PortalIrisClipCompilation.open(ShaderKey.TERRAIN_SOLID)) {
            assertTrue(PortalIrisClipCompilation.active());
        }
        assertFalse(PortalIrisClipCompilation.active());
    }

    @Test
    public void identicalProgramsReuseClippingWithoutReparsingAndSnapshotTheirInputs() {
        PortalIrisClipping.clear();
        EnumMap<PatchShaderType, String> sources = new EnumMap<>(PatchShaderType.class);
        sources.put(PatchShaderType.VERTEX, VERTEX);
        sources.put(PatchShaderType.FRAGMENT, FRAGMENT);
        sources.put(PatchShaderType.GEOMETRY, null);
        PortalIrisClipping.Result first = PortalIrisClipping.transform(sources, 8, false);
        sources.put(PatchShaderType.VERTEX, VERTEX.replace("1.0", "2.0"));
        assertNotSame(first, PortalIrisClipping.transform(sources, 8, false));
        sources.put(PatchShaderType.VERTEX, VERTEX);
        assertSame(first, PortalIrisClipping.transform(sources, 8, false));
        assertNotSame(first, PortalIrisClipping.transform(sources, 4, false));
        PortalIrisClipping.clear();
        assertNotSame(first, PortalIrisClipping.transform(sources, 8, false));
    }

    @Test
    public void clippingCacheEvictsLeastRecentlyUsedPrograms() {
        PortalIrisClipping.clear();
        Map<PatchShaderType, String> firstSources = Map.of(PatchShaderType.VERTEX, VERTEX);
        PortalIrisClipping.Result first = PortalIrisClipping.transform(firstSources, 8, false);
        Map<PatchShaderType, String> secondSources = Map.of(PatchShaderType.VERTEX, VERTEX.replace("1.0", "2.0"));
        PortalIrisClipping.Result second = PortalIrisClipping.transform(secondSources, 8, false);
        for (int index = 3; index <= 128; index++) {
            PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, VERTEX.replace("1.0", index + ".0")), 8, false);
        }
        assertSame(first, PortalIrisClipping.transform(firstSources, 8, false));
        PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, VERTEX.replace("1.0", "129.0")), 8, false);
        assertSame(first, PortalIrisClipping.transform(firstSources, 8, false));
        assertNotSame(second, PortalIrisClipping.transform(secondSources, 8, false));
        PortalIrisClipping.clear();
    }

    @Test
    public void cachedSourcesAndFragmentRemainUnchanged() {
        Map<PatchShaderType, String> original = Map.of(PatchShaderType.VERTEX, VERTEX,
            PatchShaderType.FRAGMENT, FRAGMENT);
        PortalIrisClipping.Result result = PortalIrisClipping.transform(original, 8, false);

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
        String converted = PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, source), 8, false)
            .sources().get(PatchShaderType.VERTEX);

        assertTrue(converted.contains("#version 330 core"));
        assertTrue(compact(converted).contains("floatsample=1.0"));
    }

    @Test
    public void vertexEarlyReturnsStillComputeDistanceAfterOriginalMain() {
        String source = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); if (gl_VertexID == 0) return;"
            + " gl_Position.z = 2.0; }";
        TranslationUnit tree = parse(PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, source), 8, false)
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
            PatchShaderType.TESS_EVAL, evaluation), 8, false);

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
            PatchShaderType.VERTEX, VERTEX, PatchShaderType.GEOMETRY, geometry), 8, false);
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
        TranslationUnit tree = parse(PortalIrisClipping.transform(Map.of(PatchShaderType.GEOMETRY, geometry), 8, false)
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
            PatchShaderType.TESS_EVAL, evaluation, PatchShaderType.GEOMETRY, geometry), 8, false);

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
            PatchShaderType.VERTEX, vertex, PatchShaderType.GEOMETRY, geometry), 8, false);
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
            () -> PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, source), 8, false));
    }

    @Test
    public void deviceLimitAndReservedIdentifiersAreValidated() {
        String occupied = "#version 330 core\nvoid main() { gl_Position = vec4(1.0); gl_ClipDistance[0] = 1.0; }";
        String collision = "#version 330 core\nuniform vec4 wormholes_ClipPlane; void main() { gl_Position = vec4(1.0); }";

        assertThrows(IllegalArgumentException.class,
            () -> PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, occupied), 1, false));
        assertThrows(IllegalArgumentException.class,
            () -> PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, VERTEX), 0, false));
        assertThrows(IllegalArgumentException.class,
            () -> PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, collision), 8, false));
    }

    @Test
    public void nullAbsentStagesAreRetainedWithoutInventingSource() {
        EnumMap<PatchShaderType, String> original = new EnumMap<>(PatchShaderType.class);
        original.put(PatchShaderType.VERTEX, VERTEX);
        original.put(PatchShaderType.GEOMETRY, null);
        PortalIrisClipping.Result result = PortalIrisClipping.transform(original, 8, false);

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
