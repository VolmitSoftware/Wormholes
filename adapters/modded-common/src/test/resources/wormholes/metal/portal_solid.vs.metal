#pragma clang diagnostic ignored "-Wmissing-prototypes"

#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

struct Portal
{
    float4 ClipPlane;
    float4 TargetSize;
};

struct DynamicTransforms
{
    float4x4 ModelViewMat;
    float4x4 TextureMat;
    float4 ColorModulator;
    float3 ModelOffset;
};

struct Projection
{
    float4x4 ProjMat;
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

struct main0_out
{
    float sphericalVertexDistance [[user(locn0)]];
    float cylindricalVertexDistance [[user(locn1)]];
    float4 vertexColor [[user(locn2)]];
    float2 texCoord0 [[user(locn3)]];
    float portalClipDistance [[user(locn4)]];
    float4 gl_Position [[position]];
};

struct main0_in
{
    float3 Position [[attribute(0)]];
    float4 Color [[attribute(1)]];
    float2 UV0 [[attribute(2)]];
    int2 UV2 [[attribute(3)]];
};

static inline __attribute__((always_inline))
float fog_spherical_distance(thread const float3& pos)
{
    return length(pos);
}

static inline __attribute__((always_inline))
float fog_cylindrical_distance(thread const float3& pos)
{
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return fast::max(distXZ, distY);
}

static inline __attribute__((always_inline))
float4 sample_lightmap(texture2d<float> lightMap, sampler lightMapSmplr, thread const int2& uv)
{
    return lightMap.sample(lightMapSmplr, fast::clamp((float2(uv) / float2(256.0)) + float2(0.03125), float2(0.03125), float2(0.96875)), level(0.0));
}

vertex main0_out main0(main0_in in [[stage_in]], constant Portal& _83 [[buffer(0)]], constant DynamicTransforms& _94 [[buffer(1)]], constant Projection& _106 [[buffer(2)]], texture2d<float> Sampler2 [[texture(4)]], sampler Sampler2Smplr [[sampler(4)]])
{
    main0_out out = {};
    out.portalClipDistance = dot(float4(in.Position, 1.0), _83.ClipPlane);
    float3 pos = in.Position + _94.ModelOffset;
    out.gl_Position = (_106.ProjMat * _94.ModelViewMat) * float4(pos, 1.0);
    float3 param = pos;
    out.sphericalVertexDistance = fog_spherical_distance(param);
    float3 param_1 = pos;
    out.cylindricalVertexDistance = fog_cylindrical_distance(param_1);
    int2 param_2 = in.UV2;
    out.vertexColor = in.Color * sample_lightmap(Sampler2, Sampler2Smplr, param_2);
    out.texCoord0 = in.UV0;
    out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
    return out;
}

