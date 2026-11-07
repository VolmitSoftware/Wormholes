package art.arcane.wormholes.modded.client.render.metal;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** The rewrites against the MSL that Metal Shaders' SPIRV-Cross translation emits for the portal block pipelines. */
public class MetalPortalMslTest {
    private static final String PRELUDE = "// prelude\n";
    private static final String WATER = "// water\n";
    private static final String CLOUDS = "constant int CLOUD_FC_DEV [[function_constant(0)]];\nconstant int CLOUD_FC_MODE [[function_constant(1)]];\n"
        + "constant float CLOUD_FC_STYLE [[function_constant(2)]];\nconstant bool cloudFc = is_function_constant_defined(CLOUD_FC_DEV) && is_function_constant_defined(CLOUD_FC_MODE) && is_function_constant_defined(CLOUD_FC_STYLE);\n"
        + "constant int cloudFcDev = cloudFc ? CLOUD_FC_DEV : 0;\nconstant int cloudFcMode = cloudFc ? CLOUD_FC_MODE : 0;\nconstant float cloudFcStyle = cloudFc ? CLOUD_FC_STYLE : 0.0;\n"
        + "static float cloudDensity(float3 p, texture3d<half> noise) {\n\thalf4 n = noise.sample(cloudRepMip, n3 * CLOUD_NOISE_SCALE, level(lod));\n"
        + "\thalf4 m = q >= 2 ? noise.sample(cloudRepMip, cloudNoisePos(xz[0], yq[0], c) * CLOUD_DETAIL_SCALE, level(lod + 1.0)) : half4(0.0h);\n"
        + "\thalf4 o = noise.sample(cloudRep, n3 * CLOUD_NOISE_SCALE, level(lod));\n}\n";
    private final MetalPortalMsl msl = new MetalPortalMsl(PRELUDE, "", CLOUDS);
    private final MetalPortalMsl water = new MetalPortalMsl(PRELUDE, WATER, CLOUDS);

    @Test
    public void vertexStageAddsViewDestinationColourLightAndShadeOutputsBehindTheHdrDefine() {
        String patched = msl.blockVertex(resource("portal_solid.vs.metal"));
        assertTrue(patched.contains("float3 wormholesView [[user(locn12)]];"));
        assertTrue(patched.contains("float2 wormholesShade [[user(locn16)]] [[flat]];"));
        assertTrue(patched.contains("out.wormholesView = pos;"));
        assertTrue(patched.contains("out.wormholesDest = (_94.TextureMat * float4(pos, 1.0)).xyz;"));
        assertTrue(patched.contains("out.wormholesLight = float2(in.UV2) * (1.0 / 240.0);"));
        assertTrue(patched.contains("out.wormholesShade = _83.Shade.xy;"));
        assertTrue(patched.indexOf("out.wormholesShade") < patched.indexOf("out.gl_Position.y = -(out.gl_Position.y);"));
        assertEquals(2, patched.split("#ifdef MCOPT_HDR", -1).length - 1);
    }

    @Test
    public void fragmentStageLightsTheGamesColourBeforeItReturns() {
        String source = resource("portal_cutout_rgss.fs.metal");
        String patched = msl.blockFragment(source);
        assertTrue(patched.startsWith(PRELUDE));
        assertTrue(patched.contains("#define WORMHOLES_PORTAL_BLOCK"));
        assertTrue(patched.contains("constant ShadeFrame& wormholesSF [[buffer(16)]]"));
        assertTrue(patched.contains("float4 wormholesAlbedo = (sampleRGSS(Sampler0, Sampler0Smplr, param, param_1) * in.wormholesColor) * _403.ColorModulator;"));
        assertTrue(patched.contains("out.fragColor = wormholesShadeBlock(out.fragColor, wormholesAlbedo, in.wormholesView, in.wormholesDest, in.wormholesLight, in.wormholesShade,"));
        assertTrue(patched.contains("float4(_340.FogEnvironmentalStart, _340.FogEnvironmentalEnd, _340.FogRenderDistanceStart, _340.FogRenderDistanceEnd)"));
        int lit = patched.indexOf("wormholesShadeBlock(out.fragColor");
        assertTrue(lit > patched.indexOf("calculateFinalColor(param_2"));
        assertTrue(lit < patched.lastIndexOf("return out;"));
        assertTrue(patched.indexOf("discard_fragment();") < patched.indexOf("float4 wormholesAlbedo"));
        assertTrue(patched.contains("    float2 wormholesShade [[user(locn16)]] [[flat]];\n#endif\n};"));
    }

    @Test
    public void translucentStageTriesTheWaterModuleFirstWhenItIsOn() {
        String source = resource("portal_cutout_rgss.fs.metal");
        String patched = water.translucentFragment(source);
        assertTrue(patched.contains(WATER));
        assertTrue(patched.contains("#define WORMHOLES_PORTAL_WATER"));
        assertTrue(patched.contains("constant WormholesPortal& wormholesPortal [[buffer(MCOPT_SLOT_Portal)]]"));
        assertTrue(patched.contains("texture2d<half> wormholesScene [[texture(18)]]"));
        int tried = patched.indexOf("bool wormholesIsWater = wormholesShadeWater(out.fragColor, in.texCoord0");
        int lit = patched.indexOf("if (!wormholesIsWater) out.fragColor = wormholesShadeBlock(out.fragColor");
        assertTrue(tried > 0 && lit > tried && lit < patched.lastIndexOf("return out;"));
        assertTrue(water.shadesWater());
        assertTrue(!msl.shadesWater());
        assertTrue(!msl.translucentFragment(source).contains("wormholesShadeWater"));
        assertTrue(!water.blockFragment(source).contains("wormholesShadeWater"));
    }

    @Test
    public void skyStagesCarryTheUniformStructsTheyBind() {
        String vertex = msl.skyVertex();
        String fragment = msl.skyFragment();
        assertTrue(vertex.startsWith(PRELUDE));
        assertTrue(vertex.contains("struct DynamicTransforms"));
        assertTrue(vertex.contains("[[buffer(MCOPT_SLOT_DynamicTransforms)]]"));
        assertTrue(fragment.contains("struct Fog"));
        assertTrue(fragment.contains("shadeCloudsDome(dir, sl, sr.cloudDome)"));
        assertTrue(fragment.contains("[[buffer(MCOPT_SLOT_Fog)]]"));
        assertTrue(fragment.contains("cloudSky(dir, int(c.march.x), int(c.march.y), 2, jitter, sf, c, weather, noise, skyLut, skyLutSmplr, foot)"));
        assertTrue(fragment.contains("texture2d<half> noise [[texture(MCOPT_SLOT_CloudNoise)]]"));
        assertTrue(fragment.indexOf("static inline half4 wormholesNoise(") < fragment.indexOf("static float cloudDensity("));
    }

    @Test
    public void cloudMarchReadsItsNoiseVolumeAsLayers() {
        String layered = MetalPortalMsl.layeredNoise(CLOUDS);
        assertTrue(layered.contains("static float cloudDensity(float3 p, texture2d<half> noise) {"));
        assertTrue(layered.contains("half4 n = wormholesNoise(noise, n3 * CLOUD_NOISE_SCALE, lod);"));
        assertTrue(layered.contains("half4 m = q >= 2 ? wormholesNoise(noise, cloudNoisePos(xz[0], yq[0], c) * CLOUD_DETAIL_SCALE, lod + 1.0) : half4(0.0h);"));
        assertTrue(layered.contains("half4 o = wormholesNoise(noise, n3 * CLOUD_NOISE_SCALE, lod);"));
        assertTrue(!layered.contains("texture3d"));
        assertTrue(!layered.contains("function_constant") && !layered.contains("is_function_constant_defined"));
        assertTrue(layered.contains("constant bool cloudFc = false;"));
        assertTrue(layered.contains("constant int cloudFcDev = 0;") && !layered.contains("CLOUD_FC_DEV"));
        assertThrows(IllegalStateException.class, () -> MetalPortalMsl.layeredNoise("// no volume"));
    }

    @Test
    public void unexpectedStageShapesAreRefused() {
        assertThrows(IllegalStateException.class, () -> msl.blockVertex("vertex main0_out main0() {}"));
        assertThrows(IllegalStateException.class, () -> msl.blockFragment("fragment main0_out main0() { return out; }"));
    }

    private static String resource(String name) {
        try (InputStream in = MetalPortalMslTest.class.getResourceAsStream("/wormholes/metal/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
