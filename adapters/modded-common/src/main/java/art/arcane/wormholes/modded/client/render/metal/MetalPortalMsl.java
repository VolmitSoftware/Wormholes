package art.arcane.wormholes.modded.client.render.metal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metal Shaders compiles Wormholes' GLSL through SPIRV-Cross and lets a mod rewrite the MSL per pipeline. These rewrites
 * light the portal block pipelines with Metal Shaders' own functions and replace the portal sky stage outright. Every
 * added line sits behind {@code MCOPT_HDR}, which Metal Shaders defines only for the variant it compiles for an HDR
 * target, so the plain variant stays the game's shader.
 */
final class MetalPortalMsl {
    private static final String VERTEX_FLIP = "    out.gl_Position.y = -(out.gl_Position.y);";
    private static final String POSITION_MEMBER = "    float4 gl_Position [[position]];";
    private static final String FRAGMENT_ENTRY = "fragment main0_out main0(";
    private static final String RETURN = "return out;";
    private static final Pattern PORTAL_PARAM = Pattern.compile("constant Portal& (\\w+) \\[\\[buffer\\(");
    private static final Pattern TRANSFORMS_PARAM = Pattern.compile("constant DynamicTransforms& (\\w+) \\[\\[buffer\\(");
    private static final Pattern FOG_PARAM = Pattern.compile("constant Fog& (\\w+) \\[\\[buffer\\(");
    private static final Pattern VERTEX_POSITION = Pattern.compile("float3 pos = in\\.Position \\+ \\w+\\.ModelOffset;");
    private static final Pattern FRAGMENT_COLOR = Pattern.compile("float4 color = \\((.+) \\* in\\.vertexColor\\) \\* (\\w+)\\.ColorModulator;");
    private static final String VARYINGS = """
        #ifdef MCOPT_HDR
            float3 wormholesView [[user(locn12)]];
            float3 wormholesDest [[user(locn13)]];
            float4 wormholesColor [[user(locn14)]];
            float2 wormholesLight [[user(locn15)]];
            float2 wormholesShade [[user(locn16)]] [[flat]];
        #endif
        """;
    private static final String FRAGMENT_PARAMS = """

        #ifdef MCOPT_HDR
            , constant ShadeFrame& wormholesSF [[buffer(16)]], constant ShadeLook& wormholesSL [[buffer(17)]], constant ShadeGpu& wormholesSG [[buffer(18)]]
            , constant ShadeResources& wormholesSR [[buffer(19)]], depth2d_array<float> wormholesShadow [[texture(16)]], sampler wormholesShadowCmp [[sampler(12)]]
            , texture2d<half> wormholesLut [[texture(17)]], sampler wormholesLutSmplr [[sampler(13)]], float4 wormholesFrag [[position]]
            , constant WormholesPortal& wormholesPortal [[buffer(MCOPT_SLOT_Portal)]]
        #ifdef SHADE_DYNLIGHT
            , constant ShadeDynLights& wormholesDL [[buffer(24)]], texture3d<half> wormholesDLIrr [[texture(24)]], texture3d<half> wormholesDLDir [[texture(25)]]
        #endif
        #endif
        """;

    private static final String SKY_UNIFORMS = """
        struct DynamicTransforms
        {
            float4x4 ModelViewMat;
            float4x4 TextureMat;
            float4 ColorModulator;
            float3 ModelOffset;
        };

        struct Fog
        {
            float4 FogColor;
            float FogEnvironmentalStart;
            float FogEnvironmentalEnd;
            float FogRenderDistanceStart;
            float FogRenderDistanceEnd;
            float FogSkyEnd;
            float FogCloudsEnd;
        };

        """;

    private static final String WATER_PARAMS = """
        #ifdef MCOPT_HDR
            , texture2d<half> wormholesScene [[texture(18)]], depth2d<float> wormholesDepth [[texture(19)]], constant ShadeWaterParams& wormholesWP [[buffer(23)]]
            , texture2d<half> wormholesWaves [[texture(20)]], texture2d<float> wormholesCoarse [[texture(21)]], texture2d<half> wormholesDetail [[texture(22)]]
        #endif
        """;

    private static final Pattern NOISE_SAMPLE = Pattern.compile("noise\\.sample\\(cloudRep(?:Mip)?, (.*?), level\\(([^()]*)\\)\\)");
    private static final String NOISE_VOLUME = "texture3d<half> noise";
    private static final String[] FUNCTION_CONSTANTS = {"constant int CLOUD_FC_DEV [[function_constant(0)]];\n",
        "constant int CLOUD_FC_MODE [[function_constant(1)]];\n", "constant float CLOUD_FC_STYLE [[function_constant(2)]];\n"};
    private static final String FUNCTION_CONSTANTS_DEFINED = "constant bool cloudFc = is_function_constant_defined(CLOUD_FC_DEV) && is_function_constant_defined(CLOUD_FC_MODE) && is_function_constant_defined(CLOUD_FC_STYLE);";
    private static final String NOISE_LAYERS = "texture2d<half> noise";
    private static final String NOISE_LOOKUP = """
        // Metal Shaders' noise volume held as an atlas of its 64 slices, eight by eight at every mip level (Blaze3D has neither 3D nor
        // array textures): one texel of it, wrapping in all three axes, and the trilinear lookup the volume's repeat sampler did.
        static inline half4 wormholesNoiseTexel(texture2d<half> atlas, int3 c, int size, uint level) {
            int3 period = int3(size, size, 64);
            c = ((c % period) + period) % period;
            return atlas.read(uint2((c.z & 7) * size + c.x, (c.z >> 3) * size + c.y), level);
        }
        static inline half4 wormholesNoise(texture2d<half> atlas, float3 p, float lod) {
            uint level = uint(clamp(lod + 0.5, 0.0, 6.0));
            int size = 64 >> level;
            float2 txy = p.xy * float(size) - 0.5;
            float tz = p.z * 64.0 - 0.5;
            float2 bxy = floor(txy);
            float bz = floor(tz);
            int3 c = int3(int(bxy.x), int(bxy.y), int(bz));
            half3 w = half3(half(txy.x - bxy.x), half(txy.y - bxy.y), half(tz - bz));
            half4 x00 = mix(wormholesNoiseTexel(atlas, c, size, level), wormholesNoiseTexel(atlas, c + int3(1, 0, 0), size, level), w.x);
            half4 x10 = mix(wormholesNoiseTexel(atlas, c + int3(0, 1, 0), size, level), wormholesNoiseTexel(atlas, c + int3(1, 1, 0), size, level), w.x);
            half4 x01 = mix(wormholesNoiseTexel(atlas, c + int3(0, 0, 1), size, level), wormholesNoiseTexel(atlas, c + int3(1, 0, 1), size, level), w.x);
            half4 x11 = mix(wormholesNoiseTexel(atlas, c + int3(0, 1, 1), size, level), wormholesNoiseTexel(atlas, c + int3(1, 1, 1), size, level), w.x);
            return mix(mix(x00, x10, w.y), mix(x01, x11, w.y), w.z);
        }

        """;

    private final String prelude;
    private final String waterPrelude;
    private final String block;
    private final String water;
    private final String skyVertex;
    private final String skyFragment;
    private final String clouds;

    /**
     * @param prelude      Metal Shaders' shading prelude
     * @param waterPrelude its weather and water module sources for a translucent stage, or empty when the water module is off
     * @param clouds       its cloud march source ({@code clouds.metal}), whose noise volume the sky stage reads from a slice atlas
     */
    MetalPortalMsl(String prelude, String waterPrelude, String clouds) {
        this.prelude = Objects.requireNonNull(prelude, "prelude");
        this.waterPrelude = Objects.requireNonNull(waterPrelude, "waterPrelude");
        block = resource("portal_block.metal");
        water = waterPrelude.isEmpty() ? "" : resource("portal_water.metal");
        skyVertex = resource("portal_sky.vs.metal");
        skyFragment = resource("portal_sky.fs.metal");
        this.clouds = layeredNoise(Objects.requireNonNull(clouds, "clouds"));
    }

    /**
     * The cloud march with its noise volume parameter and every sample of it rewritten for a layer array, and its optional
     * function constants (the mod specialises its own passes with them) replaced by their unset values: a fragment that
     * refers to function constants cannot build a pipeline state without specialisation, which Blaze3D does not do.
     */
    static String layeredNoise(String clouds) {
        if (!clouds.contains(NOISE_VOLUME)) {
            throw new IllegalStateException("The cloud march does not read a noise volume where expected");
        }
        String source = clouds;
        for (String constant : FUNCTION_CONSTANTS) {
            if (!source.contains(constant)) {
                throw new IllegalStateException("The cloud march does not declare the function constant " + constant);
            }
            source = source.replace(constant, "");
        }
        source = source.replace(FUNCTION_CONSTANTS_DEFINED, "constant bool cloudFc = false;")
            .replace("constant int cloudFcDev = cloudFc ? CLOUD_FC_DEV : 0;", "constant int cloudFcDev = 0;")
            .replace("constant int cloudFcMode = cloudFc ? CLOUD_FC_MODE : 0;", "constant int cloudFcMode = 0;")
            .replace("constant float cloudFcStyle = cloudFc ? CLOUD_FC_STYLE : 0.0;", "constant float cloudFcStyle = 0.0;");
        return NOISE_LOOKUP + NOISE_SAMPLE.matcher(source.replace(NOISE_VOLUME, NOISE_LAYERS)).replaceAll("wormholesNoise(noise, $1, $2)");
    }

    /** Whether translucent stages shade water with Metal Shaders' water module. */
    boolean shadesWater() {
        return !water.isEmpty();
    }

    /** The portal block vertex stage with the raw colour, lightmap, view and destination positions and shade mode as extra outputs. */
    String blockVertex(String msl) {
        String portal = param(PORTAL_PARAM, msl, "Portal");
        String transforms = param(TRANSFORMS_PARAM, msl, "DynamicTransforms");
        if (!VERTEX_POSITION.matcher(msl).find() || !msl.contains(VERTEX_FLIP) || !msl.contains(POSITION_MEMBER)) {
            throw new IllegalStateException("The portal block vertex stage does not have the expected shape");
        }
        String outputs = """
            #ifdef MCOPT_HDR
                out.wormholesView = pos;
                out.wormholesDest = (%2$s.TextureMat * float4(pos, 1.0)).xyz;
                out.wormholesColor = in.Color;
                out.wormholesLight = float2(in.UV2) * (1.0 / 240.0);
                out.wormholesShade = %1$s.Shade.xy;
            #endif
            """.formatted(portal, transforms);
        return msl.replace(POSITION_MEMBER, VARYINGS + POSITION_MEMBER).replace(VERTEX_FLIP, outputs + VERTEX_FLIP);
    }

    /** The portal block fragment stage lit by Metal Shaders after the game's own colour, cutout and fog have run. */
    String blockFragment(String msl) {
        return blockFragment(msl, false);
    }

    /** The translucent portal block fragment stage: water through Metal Shaders' water module, everything else lit as a block. */
    String translucentFragment(String msl) {
        return blockFragment(msl, shadesWater());
    }

    private String blockFragment(String msl, boolean shadeWater) {
        int entry = msl.indexOf(FRAGMENT_ENTRY);
        int inStart = msl.indexOf("struct main0_in\n{");
        int inEnd = inStart < 0 ? -1 : msl.indexOf("};", inStart);
        Matcher color = FRAGMENT_COLOR.matcher(msl);
        if (entry < 0 || inEnd < 0 || !color.find()) {
            throw new IllegalStateException("The portal block fragment stage does not have the expected shape");
        }
        String signature = msl.substring(entry, msl.indexOf('{', entry));
        String fog = param(FOG_PARAM, signature, "Fog");
        int close = closingParenthesis(msl, entry + FRAGMENT_ENTRY.length() - 1);
        int returned = msl.lastIndexOf(RETURN);
        if (close < 0 || returned < close) {
            throw new IllegalStateException("The portal block fragment stage has no return to light");
        }
        String albedo = "\n#ifdef MCOPT_HDR\n    float4 wormholesAlbedo = (" + color.group(1) + " * in.wormholesColor) * " + color.group(2) + ".ColorModulator;\n#endif\n";
        String fogRange = "float4(%1$s.FogEnvironmentalStart, %1$s.FogEnvironmentalEnd, %1$s.FogRenderDistanceStart, %1$s.FogRenderDistanceEnd)".formatted(fog);
        String lit = """
            #ifdef MCOPT_HDR
                %1$sout.fragColor = wormholesShadeBlock(out.fragColor, wormholesAlbedo, in.wormholesView, in.wormholesDest, in.wormholesLight, in.wormholesShade,
                    wormholesFrag, %2$s,
                    wormholesLut, wormholesLutSmplr, wormholesShadow, wormholesShadowCmp, wormholesSF, wormholesPortal.Frame, wormholesSL, wormholesSG, wormholesSR WORMHOLES_DL_ARGS);
            #endif
                """.formatted(shadeWater ? waterGate(fogRange) : "", fogRange);
        StringBuilder patched = new StringBuilder(msl.length() + prelude.length() + waterPrelude.length() + block.length() + water.length() + 2048);
        patched.append(prelude).append('\n');
        if (shadeWater) {
            patched.append(waterPrelude).append('\n');
        }
        patched.append(block).append('\n');
        if (shadeWater) {
            patched.append(water).append('\n');
        }
        patched.append(msl, 0, inEnd).append(VARYINGS);
        int afterColor = color.end();
        patched.append(msl, inEnd, close).append(FRAGMENT_PARAMS);
        if (shadeWater) {
            patched.append(WATER_PARAMS);
        }
        patched.append(msl, close, afterColor).append(albedo);
        patched.append(msl, afterColor, returned).append(lit);
        patched.append(msl, returned, msl.length());
        return patched.toString();
    }

    /** The portal sky vertex stage: a full-screen quad whose ray follows the portal camera into the destination's orientation. */
    String skyVertex() {
        return prelude + "\n" + SKY_UNIFORMS + skyVertex;
    }

    /** The portal sky fragment stage: Metal Shaders' sky along that ray and its clouds marched along it. */
    String skyFragment() {
        return prelude + "\n" + clouds + "\n" + SKY_UNIFORMS + skyFragment;
    }

    /** The water module's try at the fragment, which leaves the block lighting to run when the fragment is not water it shades. */
    private static String waterGate(String fogRange) {
        return "bool wormholesIsWater = wormholesShadeWater(out.fragColor, in.texCoord0, in.wormholesView, in.wormholesDest, in.wormholesLight, in.wormholesShade,\n"
            + "        in.wormholesColor, wormholesFrag, " + fogRange + ", wormholesPortal, wormholesLut, wormholesLutSmplr, wormholesShadow, wormholesShadowCmp,\n"
            + "        wormholesSF, wormholesSL, wormholesSG, wormholesSR, wormholesScene, wormholesDepth, wormholesWP, wormholesWaves, wormholesCoarse, wormholesDetail);\n"
            + "    if (!wormholesIsWater) ";
    }

    private static String param(Pattern pattern, String msl, String name) {
        Matcher matcher = pattern.matcher(msl);
        if (!matcher.find()) {
            throw new IllegalStateException("The portal block stage binds no " + name + " uniform");
        }
        return matcher.group(1);
    }

    private static int closingParenthesis(String msl, int open) {
        int depth = 0;
        for (int index = open; index < msl.length(); index++) {
            char character = msl.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String resource(String name) {
        try (InputStream in = MetalPortalMsl.class.getResourceAsStream("/assets/wormholes/shaders/metal/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read the Metal portal shader " + name, failure);
        }
    }
}
