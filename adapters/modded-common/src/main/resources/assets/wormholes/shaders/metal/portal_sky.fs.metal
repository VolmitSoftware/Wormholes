// The sky of a streamed portal view under Metal Shaders: Metal Shaders' sky radiance along the portal camera's ray,
// turned into the destination's orientation, with its volumetric clouds marched per pixel along that ray with the
// mod's own march (clouds.metal, cloudSky) from the view's Portal block: the mod's live CloudParams re-based on the
// view's camera, and Wormholes' copies of its weather map and noise volume. There is no history to resolve into, so the
// march jitters per pixel and frame and the renderer's temporal pass settles it. A view into the End (Shade.w 2) gets
// the mod's End sky from the view's frame instead, which carries the End's light whether the camera is in the End or
// looks into it from elsewhere. Compiled after Metal Shaders' prelude and clouds.metal with the noise volume read from
// a slice atlas.
struct WormholesSkyOut {
	float4 position [[position]];
	float3 dir [[user(locn0)]];
};

struct WormholesPortal {
	float4 ClipPlane;
	float4 TargetSize;
	float4 DepthTransform;
	float4 Shade;
	ShadeFrame Frame;
	CloudParams Clouds;
};

fragment float4 main0(WormholesSkyOut in [[stage_in]], constant ShadeFrame& sf [[buffer(16)]], constant ShadeLook& sl [[buffer(17)]],
                      constant ShadeGpu& sg [[buffer(18)]], constant ShadeResources& sr [[buffer(19)]],
                      texture2d<half> skyLut [[texture(17)]], sampler skyLutSmplr [[sampler(13)]],
                      constant Fog& fog [[buffer(MCOPT_SLOT_Fog)]], constant WormholesPortal& portal [[buffer(MCOPT_SLOT_Portal)]],
                      texture2d<half> weather [[texture(MCOPT_SLOT_CloudWeather)]], texture2d<half> noise [[texture(MCOPT_SLOT_CloudNoise)]]) {
	float3 dir = normalize(in.dir);
	float ex = shadeExposure(sf, sg);
#ifdef MCOPT_SHADE_DIMS
	if (portal.Shade.w > 1.5) {
		// a frame written for the view (the camera is not in the End) carries the End's reference exposure in time.z
		constant ShadeFrame &pf = portal.Frame;
		float endPixelAngle = 2.0 * atan(1.0 / max(abs(pf.proj[1][1]), 1e-3)) * pf.screen.w;
		float endExposure = pf.flags.x != sf.flags.x ? pf.time.z * exp2(sl.grade0.x) : ex;
		return float4(shadeOut(shadeEndSky(dir, pf, sl, sr.milkyWay, endPixelAngle), pf, endExposure), 1.0);
	}
#endif
	if (sf.flags.x != SHADE_DIM_OVERWORLD) {
		return float4(shadeVanillaOut(fog.FogColor.rgb, sl), 1.0);
	}
	float pixelAngle = 2.0 * atan(1.0 / max(abs(sf.proj[1][1]), 1e-3)) * sf.screen.w;
	float3 sky = shadeSkyRadiance2(dir, skyLut, skyLutSmplr, sf, sl, sr.milkyWay, pixelAngle);
	float3 fl = float3(0.0);
	if (sl.flash.w > 0.0) {
		fl = float3(0.78, 0.86, 1.0) * (sl.flash.w * 0.35 * (0.35 + 0.65 * pow(saturate(dot(dir, sl.flash.xyz) * 0.5 + 0.5), 3.0)));
	}
#ifdef MCOPT_HDR
	float4 clouds;
	constant CloudParams &c = portal.Clouds;
	if (sl.clouds0.w > 0.5 && portal.Shade.z > 0.5 && c.art.w < 0.5) {
		// the view's own march: one sample footprint per target pixel, a per-pixel jitter that turns with the frame
		float foot = 2.0 * atan(1.0 / max(abs(sf.proj[1][1]), 1e-3)) / max(portal.TargetSize.y, 1.0);
		float jitter = fract(shadeNoise(in.position.xy, 0) + float(sf.flags2.x & 1023) * 0.6180339887);
		CloudResult marched = cloudSky(dir, int(c.march.x), int(c.march.y), 2, jitter, sf, c, weather, noise, skyLut, skyLutSmplr, foot);
		clouds = float4(min(marched.S, 60000.0), marched.T);
	} else {
		clouds = shadeCloudsDome(dir, sl, sr.cloudDome);
	}
	sky = sky * clouds.a + clouds.rgb;
	sky += fl * (sl.clouds0.w > 0.5 ? 0.4 + 0.6 * (1.0 - clouds.a) : 1.0);
#else
	sky += fl;
#endif
	float vanillaSky = dir.y <= 0.0 ? 1.0 : saturate(min(16.0 / max(dir.y, 1e-3), 512.0) / max(sf.fogSky.x, 1.0));
	return float4(shadeFinish2(sky, vanillaSky, sf, sl, ex), 1.0);
}
