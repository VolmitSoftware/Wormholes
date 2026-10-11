package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.NativeRenderFixtures;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.DHParameters;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisClipProgramsTest {
    @Test
    public void distantWorldProgramsClipAndShadowProgramsStayUnchanged() {
        for (Patch patch : new Patch[]{Patch.DH_TERRAIN, Patch.DH_GENERIC}) {
            DHParameters parameters = NativeRenderFixtures.distantParameters(patch);

            assertTrue(IrisClipPrograms.clipped("dh_terrain", parameters));
            assertTrue(IrisClipPrograms.clipped("dh_water", parameters));
            assertTrue(IrisClipPrograms.clipped("dh_generic", parameters));
            assertFalse(IrisClipPrograms.clipped("dh_shadow_terrain", parameters));
            assertFalse(IrisClipPrograms.clipped("dh_shadow_generic", parameters));
            assertFalse(IrisClipPrograms.clipped("dh_terrain_shadow", parameters));
            assertFalse(IrisClipPrograms.clipped("dh_generic_shadow", parameters));
        }
    }

    @Test
    public void distantShadowProgramsDoNotTransformSources() {
        for (Patch patch : new Patch[]{Patch.DH_TERRAIN, Patch.DH_GENERIC}) {
            DHParameters parameters = NativeRenderFixtures.distantParameters(patch);
            Map<PatchShaderType, String> source = Map.of(PatchShaderType.VERTEX,
                "#version 330 core\nvoid main() { gl_Position = vec4(1.0); }");

            assertEquals(null, IrisClipPrograms.transform("dh_shadow_terrain", parameters, source));
            assertEquals(null, IrisClipPrograms.transform("dh_generic_shadow", parameters, source));
        }
    }
}
