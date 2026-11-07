// Lighting for the terrain of a streamed portal view under Metal Shaders. Appended after Metal Shaders' own prelude
// (ShadeFrame, ShadeLook, shadeLit2, shadeShadow, shadeCloudShadow, shadeFogAir2, shadeFinish2), so the view takes
// the same light as the terrain around it. Two positions arrive from the vertex stage: pView is the mesh position
// relative to the view's camera in source space (fog distance, facing), pDest is the block's real position in the
// destination relative to the main camera (shadow map, cloud shadow, dynamic lights, haze height).
#ifndef WORMHOLES_PORTAL_BLOCK
#define WORMHOLES_PORTAL_BLOCK

#ifdef SHADE_DYNLIGHT
#define WORMHOLES_DL_PARAMS , constant ShadeDynLights &dl, texture3d<half> dlIrr, texture3d<half> dlDir
#define WORMHOLES_DL_ARGS , wormholesDL, wormholesDLIrr, wormholesDLDir
#else
#define WORMHOLES_DL_PARAMS
#define WORMHOLES_DL_ARGS
#endif

// The view's Portal block: its clip plane, target, depth transform and shade fields, then the renderer's frame as the
// view carries it (the camera's own, re-based on the view's camera; another dimension's light in the foreign mode).
struct WormholesPortal {
	float4 ClipPlane;
	float4 TargetSize;
	float4 DepthTransform;
	float4 Shade;
	ShadeFrame Frame;
};

#define WORMHOLES_SHADE_VANILLA 0
#define WORMHOLES_SHADE_LIT_SHADOWED 1
#define WORMHOLES_SHADE_LIT 2
#define WORMHOLES_SHADE_LIT_FOREIGN 3

// vanilla: the game's lightmapped, fogged colour (its alpha is kept); albedoSrgb: atlas texel x raw vertex colour x
// ColorModulator; light: lightmap coordinates / 240 (x block, y sky); shade: x the shade mode, y the sign of the
// source-to-destination rotation's determinant; fog: the Fog uniform's environmental and render distance ranges; f: the
// renderer's live frame (the camera's; pDest is relative to its camera); view: the frame the view carries, which in the
// foreign mode holds another dimension's light than the camera's and in time.z its reference exposure under the player's
// EV settings, standing in for the camera's eye-adapted exposure (the look's EV bias still applies).
static float4 wormholesShadeBlock(float4 vanilla, float4 albedoSrgb, float3 pView, float3 pDest, float2 light, float2 shade, float4 frag,
                                  float4 fog, texture2d<half> lut, sampler lutS, depth2d_array<float> shadowMap, sampler cmp,
                                  constant ShadeFrame &f, constant ShadeFrame &view, constant ShadeLook &L, constant ShadeGpu &g,
                                  constant ShadeResources &R WORMHOLES_DL_PARAMS) {
	float3 dpx = dfdx(pView), dpy = dfdy(pView);
	float3 ddx = dfdx(pDest), ddy = dfdy(pDest);
	int mode = int(shade.x + 0.5);
	float3 nf = cross(dpx, dpy);
	float3 n = nf * rsqrt(max(dot(nf, nf), 1e-30));
	bool flipped = dot(n, pView) > 0.0;
	// outside the overworld the dims module lights the view as it lights the terrain around it, when it is on and the view
	// shows the same kind of sky the camera is under (the frame's light is that dimension's) or carries that dimension's
	// light itself (the foreign mode); the End's key light is shadowed in the camera's own dimension
	bool foreign = mode == WORMHOLES_SHADE_LIT_FOREIGN;
	constant ShadeFrame *lightFrame = foreign ? &view : &f;
	constant ShadeFrame &lf = *lightFrame;
	bool dimsLit = lf.flags.x != SHADE_DIM_OVERWORLD && mode != WORMHOLES_SHADE_VANILLA && shadeDimsLit(lf) && !shadeDev(f, SHADE_DEV_FLAT_LIGHT);
	if (dimsLit) {
		float3 ndf = cross(ddx, ddy) * (shade.y < 0.0 ? -1.0 : 1.0);
		float3 nd = ndf * rsqrt(max(dot(ndf, ndf), 1e-30));
		if (flipped) nd = -nd;
		half3 albedo = shadeToLinear(half3(saturate(albedoSrgb.rgb)));
		half block = half(saturate(light.x));
		half shadow = half(1.0);
		if (mode == WORMHOLES_SHADE_LIT_SHADOWED && f.flags.x == SHADE_DIM_END && f.flags.z != 0) shadow = shadeShadow(shadowMap, cmp, f, pDest, half3(nd), false);
		float3 radiance = shadeLitDims(albedo, half3(nd), block, shadow, false, false, pDest, lf, L);
		float vanillaFog = shadeVanillaFog(pView, fog.xy, fog.zw);
		float ex = foreign ? lf.time.z * exp2(L.grade0.x) : shadeExposure(f, g);
		return float4(shadeFinish2(radiance, vanillaFog, f, L, ex), vanilla.a);
	}
	if (mode == WORMHOLES_SHADE_VANILLA || f.flags.x != SHADE_DIM_OVERWORLD || shadeDev(f, SHADE_DEV_FLAT_LIGHT)) {
		// the game's lightmapped colour, converted as Metal Shaders converts its own terrain outside the overworld; the
		// view's meshes carry no directional face shade (PortalTerrainMaterials.PER_PIXEL), so it is applied here per pixel
		// from the face's axis, as Metal Shaders does for its flattened meshes
		half3 axis = half3(flipped ? -n : n);
		half3 a = abs(axis);
		half amax = max(max(a.x, a.y), a.z);
		if (amax >= 0.9h) axis = half3(a.x == amax ? sign(axis.x) : 0.0h, a.x != amax && a.y == amax ? sign(axis.y) : 0.0h, a.x != amax && a.y != amax ? sign(axis.z) : 0.0h);
		return float4(shadeVanillaOut(vanilla.rgb * float(shadeVanillaFace(axis, f.flags.x)), L), vanilla.a);
	}
	float3 ndf = cross(ddx, ddy) * (shade.y < 0.0 ? -1.0 : 1.0);
	float3 nd = ndf * rsqrt(max(dot(ndf, ndf), 1e-30));
	if (flipped) {
		nd = -nd;
	}
	half3 albedo = shadeToLinear(half3(saturate(albedoSrgb.rgb)));
	int debug = f.flags.w;
	if (debug == 3) albedo = half3(0.45h);
	half block = half(saturate(light.x)), sky = half(saturate(light.y));
	float ex = shadeExposure(f, g);
	half3 hn = half3(nd);
	half shadow = half(1.0);
	if (mode == WORMHOLES_SHADE_LIT_SHADOWED && sky > 0.3h) {
		if (f.flags.z != 0) shadow = shadeShadow(shadowMap, cmp, f, pDest + f.lightDirWorld.xyz * 0.05, hn, false);
		shadow *= half(shadeCloudShadow(pDest, L, R.cloudShadow));
	}
	float3 radiance = shadeLit2(albedo, hn, sky, block, shadow, false, false, f, L);
	if (L.flash.w > 0.0) {
		half gk = shadeSkyCurve(sky);
		float ndl = saturate(dot(nd, L.flash.xyz)) * 0.65 + 0.35;
		radiance += float3(albedo) * float3(0.78, 0.86, 1.0) * (L.flash.w * ndl * float(gk * gk));
	}
	if (mode == WORMHOLES_SHADE_LIT_SHADOWED) {
		radiance += shadeDynamicLights(shadeDynIn(pDest, nd, float3(albedo), frag.xy, sky, block, SHADE_DYN_ENTITY), f, L, &R);
#ifdef SHADE_DYNLIGHT
		radiance += float3(albedo) * f.ambientBlock.xyz * shadeDynamicLightsVol(dl, dlIrr, dlDir, pDest, nd, false, block);
#endif
	}
	float vanillaFog = shadeVanillaFog(pView, fog.xy, fog.zw);
	radiance = shadeFogAir2(radiance, pView, vanillaFog, 0.0, lut, lutS, f, L, &R);
	float3 rgb = shadeFinish2(radiance, vanillaFog, f, L, ex);
	if (debug == 2) rgb = shadeVanillaOut(nd * 0.5 + 0.5, L);
	if (debug == 1) rgb = shadeVanillaOut(float3(0.25 + 0.75 * float(shadow)), L);
	return float4(rgb, vanilla.a);
}

#endif
