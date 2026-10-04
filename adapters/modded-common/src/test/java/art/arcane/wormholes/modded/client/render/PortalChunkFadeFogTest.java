package art.arcane.wormholes.modded.client.render;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PortalChunkFadeFogTest {
    private static final String VERTEX = "#version 330 core\nconst float mc_chunkFade = -1.0;"
        + " out float chunkFade; void main() { chunkFade = mc_chunkFade; gl_Position = vec4(1.0); }";

    @Test
    public void destinationTerrainPreservesBorderFogThroughSkylightEncodingAndDeferredDecoding() {
        float fade = transformedFade(true);
        for (double skylight : new double[] {0.0, 0.5, 1.0}) {
            double encoded = skylight * 0.5;
            if (fade < 1.0) {
                encoded = 1.0 - fade * 0.5;
            }
            double decoded = encoded > 0.50001 ? (1.0 - encoded) * 2.0 : 1.0;
            assertEquals(1.0, decoded, 0.0);
            for (double distance : new double[] {8.0, 24.0, 48.0, 80.0}) {
                assertEquals(baseFog(distance), borderFog(distance, decoded), 0.000000000001);
            }
        }
    }

    @Test
    public void destinationWaterPreservesBorderFogWithoutDeferredDecoding() {
        float fade = transformedFade(true);
        for (double distance : new double[] {8.0, 24.0, 48.0, 80.0}) {
            assertEquals(baseFog(distance), borderFog(distance, fade), 0.000000000001);
        }
    }

    @Test
    public void nonTerrainSentinelWouldMakeNearbyTerrainOpaqueWithFog() {
        float sentinel = transformedFade(false);
        assertEquals(-1.0f, sentinel, 0.0f);
        double encoded = 1.0 - sentinel * 0.5;
        double decoded = (1.0 - encoded) * 2.0;
        double clampedDecoded = (1.0 - Math.clamp(encoded, 0.0, 1.0)) * 2.0;
        assertTrue(baseFog(48.0) < 0.000000001);
        assertEquals(1.0, borderFog(48.0, decoded), 0.0);
        assertTrue(borderFog(48.0, clampedDecoded) > 0.5);
    }

    private static float transformedFade(boolean terrain) {
        String source = PortalIrisClipping.transform(Map.of(PatchShaderType.VERTEX, VERTEX), 8, terrain)
            .sources().get(PatchShaderType.VERTEX);
        TranslationUnit tree = new ASTParser().parseTranslationUnit(RootSupplier.PREFIX_UNORDERED_ED_EXACT, source);
        for (DeclarationMember member : tree.getRoot().nodeIndex.getStream(DeclarationMember.class).toList()) {
            if (member.getName().getName().equals("mc_chunkFade")) {
                return Float.parseFloat(ASTPrinter.printSimple(member.getInitializer()).replaceAll("\\s+", ""));
            }
        }
        throw new AssertionError("Terrain shader has no chunk fade declaration");
    }

    private static double baseFog(double distance) {
        return -Math.expm1(-3.0 * Math.pow(distance / 192.0, 16.0));
    }

    private static double borderFog(double distance, double fade) {
        double fadeWeight = Math.pow(Math.clamp(distance * 0.015, 0.0, 1.0), 2.0);
        double visible = 1.0 + (fade - 1.0) * fadeWeight;
        return Math.clamp(1.0 + (baseFog(distance) - 1.0) * visible, 0.0, 1.0);
    }
}
