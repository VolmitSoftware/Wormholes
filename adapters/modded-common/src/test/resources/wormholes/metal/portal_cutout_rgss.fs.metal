#pragma clang diagnostic ignored "-Wmissing-prototypes"
#pragma clang diagnostic ignored "-Wmissing-braces"

#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

template<typename T, size_t Num>
struct spvUnsafeArray
{
    T elements[Num ? Num : 1];
    
    thread T& operator [] (size_t pos) thread
    {
        return elements[pos];
    }
    constexpr const thread T& operator [] (size_t pos) const thread
    {
        return elements[pos];
    }
    
    device T& operator [] (size_t pos) device
    {
        return elements[pos];
    }
    constexpr const device T& operator [] (size_t pos) const device
    {
        return elements[pos];
    }
    
    constexpr const constant T& operator [] (size_t pos) const constant
    {
        return elements[pos];
    }
    
    threadgroup T& operator [] (size_t pos) threadgroup
    {
        return elements[pos];
    }
    constexpr const threadgroup T& operator [] (size_t pos) const threadgroup
    {
        return elements[pos];
    }
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

struct DynamicTransforms
{
    float4x4 ModelViewMat;
    float4x4 TextureMat;
    float4 ColorModulator;
    float3 ModelOffset;
};

constant spvUnsafeArray<float2, 4> _283 = spvUnsafeArray<float2, 4>({ float2(0.125, 0.375), float2(-0.125, -0.375), float2(0.375, -0.125), float2(-0.375, 0.125) });

struct main0_out
{
    float4 fragColor [[color(0)]];
};

struct main0_in
{
    float sphericalVertexDistance [[user(locn0)]];
    float cylindricalVertexDistance [[user(locn1)]];
    float4 vertexColor [[user(locn2)]];
    float2 texCoord0 [[user(locn3)]];
    float portalClipDistance [[user(locn4)]];
};

static inline __attribute__((always_inline))
float4 sampleNearest(texture2d<float> source, sampler sourceSmplr, thread float2& uv, thread const float2& pixelSize, thread const float2& du, thread const float2& dv, thread const float2& texelScreenSize)
{
    float2 uvTexelCoords = uv / pixelSize;
    float2 texelCenter = round(uvTexelCoords) - float2(0.5);
    float2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = (((texelOffset - float2(0.5)) * pixelSize) / texelScreenSize) + float2(0.5);
    texelOffset = fast::clamp(texelOffset, float2(0.0), float2(1.0));
    uv = (texelCenter + texelOffset) * pixelSize;
    return source.sample(sourceSmplr, uv, gradient2d(du, dv));
}

static inline __attribute__((always_inline))
float4 sampleRGSS(texture2d<float> source, sampler sourceSmplr, thread const float2& uv, thread const float2& pixelSize)
{
    float2 du = dfdx(uv);
    float2 dv = dfdy(uv);
    float2 texelScreenSize = sqrt((du * du) + (dv * dv));
    float maxTexelSize = fast::max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = fast::min(pixelSize.x, pixelSize.y);
    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);
    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = fast::min(duLength, dvLength);
    float maxDerivative = fast::max(duLength, dvLength);
    float effectiveDerivative = sqrt(minDerivative * maxDerivative);
    float mipLevelExact = fast::max(0.0, log2(effectiveDerivative / minPixelSize));
    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);
    float4 rgssColorLow = float4(0.0);
    float4 rgssColorHigh = float4(0.0);
    for (int i = 0; i < 4; i++)
    {
        float2 sampleUV = uv + (_283[i] * pixelSize);
        rgssColorLow += source.sample(sourceSmplr, sampleUV, level(mipLevelLow));
        rgssColorHigh += source.sample(sourceSmplr, sampleUV, level(mipLevelHigh));
    }
    rgssColorLow *= 0.25;
    rgssColorHigh *= 0.25;
    float4 rgssColor = mix(rgssColorLow, rgssColorHigh, float4(mipBlend));
    float2 param = uv;
    float2 param_1 = pixelSize;
    float2 param_2 = du;
    float2 param_3 = dv;
    float2 param_4 = texelScreenSize;
    float4 _329 = sampleNearest(source, sourceSmplr, param, param_1, param_2, param_3, param_4);
    float4 nearestColor = _329;
    return mix(nearestColor, rgssColor, float4(blendFactor));
}

static inline __attribute__((always_inline))
float linear_fog_value(thread const float& vertexDistance, thread const float& fogStart, thread const float& fogEnd)
{
    if (vertexDistance <= fogStart)
    {
        return 0.0;
    }
    else
    {
        if (vertexDistance >= fogEnd)
        {
            return 1.0;
        }
    }
    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

static inline __attribute__((always_inline))
float total_fog_value(thread const float& sphericalVertexDistance, thread const float& cylindricalVertexDistance, thread const float& environmentalStart, thread const float& environmantalEnd, thread const float& renderDistanceStart, thread const float& renderDistanceEnd)
{
    float param = sphericalVertexDistance;
    float param_1 = environmentalStart;
    float param_2 = environmantalEnd;
    float param_3 = cylindricalVertexDistance;
    float param_4 = renderDistanceStart;
    float param_5 = renderDistanceEnd;
    return fast::max(linear_fog_value(param, param_1, param_2), linear_fog_value(param_3, param_4, param_5));
}

static inline __attribute__((always_inline))
float4 apply_fog(thread const float4& inColor, thread const float& sphericalVertexDistance, thread const float& cylindricalVertexDistance, thread const float& environmentalStart, thread const float& environmantalEnd, thread const float& renderDistanceStart, thread const float& renderDistanceEnd, thread const float4& fogColor)
{
    float param = sphericalVertexDistance;
    float param_1 = cylindricalVertexDistance;
    float param_2 = environmentalStart;
    float param_3 = environmantalEnd;
    float param_4 = renderDistanceStart;
    float param_5 = renderDistanceEnd;
    float fogValue = total_fog_value(param, param_1, param_2, param_3, param_4, param_5);
    return float4(mix(inColor.xyz, fogColor.xyz, float3(fogValue * fogColor.w)), inColor.w);
}

static inline __attribute__((always_inline))
float4 calculateFinalColor(thread const float4& color, constant Fog& _340, thread float& sphericalVertexDistance, thread float& cylindricalVertexDistance)
{
    float4 fogColor = _340.FogColor;
    float4 param = color;
    float param_1 = sphericalVertexDistance;
    float param_2 = cylindricalVertexDistance;
    float param_3 = _340.FogEnvironmentalStart;
    float param_4 = _340.FogEnvironmentalEnd;
    float param_5 = _340.FogRenderDistanceStart;
    float param_6 = _340.FogRenderDistanceEnd;
    float4 param_7 = fogColor;
    return apply_fog(param, param_1, param_2, param_3, param_4, param_5, param_6, param_7);
}

fragment main0_out main0(main0_in in [[stage_in]], constant DynamicTransforms& _403 [[buffer(1)]], constant Fog& _340 [[buffer(3)]], texture2d<float> Sampler0 [[texture(5)]], sampler Sampler0Smplr [[sampler(5)]])
{
    main0_out out = {};
    if (in.portalClipDistance > 0.0)
    {
        discard_fragment();
    }
    float2 pixelSize = float2(1.0) / float2(int2(Sampler0.get_width(), Sampler0.get_height()));
    float2 param = in.texCoord0;
    float2 param_1 = pixelSize;
    float4 color = (sampleRGSS(Sampler0, Sampler0Smplr, param, param_1) * in.vertexColor) * _403.ColorModulator;
    if (color.w < 0.100000001490116119384765625)
    {
        discard_fragment();
    }
    float4 param_2 = color;
    out.fragColor = calculateFinalColor(param_2, _340, in.sphericalVertexDistance, in.cylindricalVertexDistance);
    return out;
}

