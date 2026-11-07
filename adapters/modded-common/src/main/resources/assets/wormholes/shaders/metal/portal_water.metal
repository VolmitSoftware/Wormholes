// Water in a streamed portal view under Metal Shaders: its own water module (waves, refraction of what the view already
// drew, reflections of its sky and clouds, screen-space reflections inside the view, caustics, foam) run with the portal
// camera. The module projects and samples through the ShadeFrame it is handed, so the view's Portal block carries a copy
// of Metal Shaders' live frame whose camera fields are the portal camera's; the scene and depth copies it refracts are
// the view's own, captured before its translucent pass. Compiled after Metal Shaders' prelude, its weather prelude and its
// water prelude.
#ifndef WORMHOLES_PORTAL_WATER
#define WORMHOLES_PORTAL_WATER

// result: the shaded water when this returns true. uv: the atlas coordinate; pView, pDest, light, shade, frag, fog: as
// wormholesShadeBlock; rawColor: the raw vertex colour (the biome's water tint times vanilla ambient occlusion).
static bool wormholesShadeWater(thread float4 &result, float2 uv, float3 pView, float3 pDest, float2 light, float2 shade, float4 rawColor, float4 frag,
                                float4 fog, constant WormholesPortal &portal, texture2d<half> lut, sampler lutS, depth2d_array<float> shadowMap, sampler cmp,
                                constant ShadeFrame &f, constant ShadeLook &L, constant ShadeGpu &g, constant ShadeResources &R,
                                texture2d<half> sceneCopy, depth2d<float> depthCopy, constant ShadeWaterParams &wp, texture2d<half> waves,
                                texture2d<float> coarse, texture2d<half> detailTexture) {
	int sprite = int(shadeQuadMax(float(shadeWaterSprite(uv, wp))));
	int mode = int(shade.x + 0.5);
	if (sprite <= 0 || wp.state.x <= 0.5 || mode == WORMHOLES_SHADE_VANILLA || f.flags.x != SHADE_DIM_OVERWORLD || shadeDev(f, SHADE_DEV_NO_WATER)) {
		return false;
	}
	constant ShadeFrame &pf = portal.Frame;
	float3 dpx = dfdx(pView), dpy = dfdy(pView);
	float2 du = dfdx(uv), dv = dfdy(uv);
	float3 raw = cross(dpx, dpy);
	bool flipped = dot(raw, pView) > 0.0;
	float3 nf = raw * rsqrt(max(dot(raw, raw), 1e-30));
	if (flipped) nf = -nf;
	half3 n = half3(nf);
	half3 a = abs(n);
	half amax = max(max(a.x, a.y), a.z);
	if (amax >= 0.9h) n = half3(a.x == amax ? sign(n.x) : 0.0h, a.x != amax && a.y == amax ? sign(n.y) : 0.0h, a.x != amax && a.y != amax ? sign(n.z) : 0.0h);
	float3 ndf = cross(dfdx(pDest), dfdy(pDest)) * (shade.y < 0.0 ? -1.0 : 1.0);
	float3 nd = ndf * rsqrt(max(dot(ndf, ndf), 1e-30));
	if (flipped) nd = -nd;
	half blockLight = half(saturate(light.x)), skyLight = half(saturate(light.y));
	half shadow = half(1.0);
	if (mode == WORMHOLES_SHADE_LIT_SHADOWED && skyLight > 0.3h) {
		if (f.flags.z != 0) shadow = shadeShadow(shadowMap, cmp, f, pDest + f.lightDirWorld.xyz * 0.05, half3(nd), false);
		shadow *= half(shadeCloudShadow(pDest, L, R.cloudShadow));
	}
	half3 up = half3(0.0h, 1.0h, 0.0h);
	ShadeWaterIn wi;
	wi.p = pView;
	wi.nFace = float3(n);
	wi.footprint = max(length(dpx), length(dpy));
	float2 flow = shadeWaterFlowDir(dpx, dpy, du, dv);
	wi.flowDir = sprite == 2 && n.y > 0.5h ? flow : float2(0.0);
	wi.pixel = frag.xy;
	wi.depth = frag.z;
	wi.tint = half3(rawColor.rgb);
	wi.sky = skyLight;
	wi.shadow = shadow;
	{
		float b = float(blockLight), s = saturate(float(skyLight) * 2.0), c = 1.0 - s * s;
		wi.cave = half4(half(smoothstep(0.3, 0.8, b) * L.waterShadow.w), half(c * L.eye.z), half(saturate(c * L.eye.y)), half(L.bloom.w));
	}
	wi.dxz = float4(dpx.xz, dpy.xz);
	wi.detail = float4(0.0);
#ifdef SHADE_WATER_DETAIL
	if ((n.y > 0.5h || (n.y < -0.5h && SHADE_CAMERA_UNDERWATER(pf))) && wp.state.w > 0.5) wi.detail = shadeWaterDetailIn(wi, pf, detailTexture);
#endif
#if defined(SHADE_WAVE_BAKE) && !defined(SHADE_LEAN_NOFALLS)
	if (shadeWaterIsFall(float(n.y), L.bloom.w, wp)) wi.nFace = shadeWaterFallNormal(wi, pf, waves);
#endif
	wi.lit = shadeLit2(half3(1.0h), up, skyLight, blockLight, shadow, false, false, f, L);
	float ex = shadeExposure(f, g);
	wi.exposure = ex;
	wi.prevExposure = 0.0;
	wi.look = &L;
	wi.res = &R;
	wi.fog = SHADE_CAMERA_UNDERWATER(pf) ? shadeLdrToHdr(f.fogColor.rgb, L) / max(ex, 1e-6) : float3(0.0);
	float3 radiance = shadeWaterSurface(wi, pf, wp, lut, lutS, sceneCopy, depthCopy, sceneCopy, waves, coarse);
#if defined(SHADE_WAVE_BAKE) && !defined(SHADE_LEAN_NOFALLS)
	if (shadeWaterIsFall(float(n.y), L.bloom.w, wp)) radiance = mix(radiance, wi.lit * 0.55, shadeWaterFallFoam(pView, float3(n), wi.footprint, L.bloom.w, pf));
#endif
	float vanillaFog = shadeVanillaFog(pView, fog.xy, fog.zw);
	radiance = shadeFogAir2(radiance, pView, vanillaFog, 0.0, lut, lutS, f, L, &R);
	result = float4(shadeFinish2(radiance, vanillaFog, f, L, ex), 1.0);
	return true;
}

#endif
