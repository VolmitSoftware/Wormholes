// The sky of a streamed portal view under Metal Shaders: Metal Shaders' sky radiance along the portal camera's ray,
// turned into the destination's orientation, with its volumetric clouds from the reflection dome (any direction, no
// screen-space history to depend on). Compiled after Metal Shaders' prelude; DynamicTransforms.ModelViewMat is the
// portal camera's inverse view-projection and TextureMat the source-to-destination rotation.
struct WormholesSkyIn {
	float3 Position [[attribute(0)]];
};

struct WormholesSkyOut {
	float4 position [[position]];
	float3 dir [[user(locn0)]];
};

vertex WormholesSkyOut main0(WormholesSkyIn in [[stage_in]], constant DynamicTransforms& dt [[buffer(MCOPT_SLOT_DynamicTransforms)]]) {
	WormholesSkyOut out;
	float2 ndc = in.Position.xy;
	float4 w = dt.ModelViewMat * float4(ndc, 0.5, 1.0);
	float3 ray = w.xyz / w.w - shadeEye(dt.ModelViewMat);
	out.dir = (dt.TextureMat * float4(ray, 0.0)).xyz;
	out.position = float4(ndc.x, -ndc.y, 0.0, 1.0);
	return out;
}

