package art.arcane.wormholes.modded.client.render.metal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Metal Shaders' cloud textures, baked again by Wormholes: the mod bakes a 512x512 weather map and a 64^3 noise volume
 * once with two parameterless, deterministic kernels ({@code cloud_bake_weather}, {@code cloud_bake_noise}) and keeps
 * both in native state no Java code can reach, so the same integer-hash noise is evaluated here on the CPU, texel for
 * texel, and uploaded through Blaze3D. Blaze3D has neither 3D nor array textures, so the volume is a 512x512 atlas of
 * its 64 slices (eight by eight, every mip level laid out the same way) that the view's cloud march reads with its own
 * wrapping, trilinear lookup. Every value goes through the same uniform curve and the same 8-bit quantisation as the
 * mod's, so the shapes match its clouds up to the last bit of fast-math rounding.
 */
final class MetalCloudTextures implements AutoCloseable {
    static final int WEATHER_SIZE = 512;
    static final int NOISE_SIZE = 64;
    static final int NOISE_MIPS = 7;
    static final int ATLAS_SLICES = 8;
    static final int ATLAS_SIZE = NOISE_SIZE * ATLAS_SLICES;
    private static final int CHANNELS = 4;

    private GpuTexture weather;
    private GpuTexture noise;
    private GpuTextureView weatherView;
    private GpuTextureView noiseView;

    /** The weather map; {@link #upload()} must have run. */
    GpuTextureView weather() {
        return weatherView;
    }

    /** The noise volume as a slice atlas; {@link #upload()} must have run. */
    GpuTextureView noise() {
        return noiseView;
    }

    /** Bakes and uploads both textures once. Render thread, outside any render pass (the upload is an encoder command). */
    void upload() {
        if (noise != null) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
        weather = device.createTexture("Wormholes cloud weather", usage, GpuFormat.RGBA8_UNORM, WEATHER_SIZE, WEATHER_SIZE, 1, 1);
        noise = device.createTexture("Wormholes cloud noise", usage, GpuFormat.RGBA8_UNORM, ATLAS_SIZE, ATLAS_SIZE, 1, NOISE_MIPS);
        ByteBuffer texels = MemoryUtil.memAlloc(ATLAS_SIZE * ATLAS_SIZE * CHANNELS);
        try {
            for (int y = 0; y < WEATHER_SIZE; y++) {
                for (int x = 0; x < WEATHER_SIZE; x++) {
                    weatherTexel(x, y, texels);
                }
            }
            texels.flip();
            encoder.writeToTexture(weather, texels, 0, 0, 0, 0, WEATHER_SIZE, WEATHER_SIZE);
            byte[][][] levels = new byte[NOISE_SIZE][NOISE_MIPS][];
            for (int z = 0; z < NOISE_SIZE; z++) {
                levels[z][0] = new byte[NOISE_SIZE * NOISE_SIZE * CHANNELS];
                for (int y = 0; y < NOISE_SIZE; y++) {
                    for (int x = 0; x < NOISE_SIZE; x++) {
                        noiseTexel(x, y, z, levels[z][0], (y * NOISE_SIZE + x) * CHANNELS);
                    }
                }
                for (int level = 1; level < NOISE_MIPS; level++) {
                    levels[z][level] = downsample(levels[z][level - 1], NOISE_SIZE >> (level - 1));
                }
            }
            for (int level = 0; level < NOISE_MIPS; level++) {
                int slice = NOISE_SIZE >> level;
                int atlas = slice * ATLAS_SLICES;
                texels.clear();
                for (int row = 0; row < atlas; row++) {
                    for (int column = 0; column < atlas; column++) {
                        int z = (row / slice) * ATLAS_SLICES + column / slice;
                        texels.put(levels[z][level], ((row % slice) * slice + column % slice) * CHANNELS, CHANNELS);
                    }
                }
                texels.flip();
                encoder.writeToTexture(noise, texels, level, 0, 0, 0, atlas, atlas);
            }
        } finally {
            MemoryUtil.memFree(texels);
        }
        weatherView = device.createTextureView(weather);
        noiseView = device.createTextureView(noise);
    }

    @Override
    public void close() {
        if (noiseView != null) {
            noiseView.close();
            noiseView = null;
        }
        if (weatherView != null) {
            weatherView.close();
            weatherView = null;
        }
        if (noise != null) {
            noise.close();
            noise = null;
        }
        if (weather != null) {
            weather.close();
            weather = null;
        }
    }

    /** A 2x2 box average of one square level, the way a mip chain is generated. */
    private static byte[] downsample(byte[] source, int size) {
        int half = size / 2;
        byte[] result = new byte[half * half * CHANNELS];
        for (int y = 0; y < half; y++) {
            for (int x = 0; x < half; x++) {
                for (int channel = 0; channel < CHANNELS; channel++) {
                    int sum = unsigned(source, ((2 * y) * size + 2 * x) * CHANNELS + channel)
                        + unsigned(source, ((2 * y) * size + 2 * x + 1) * CHANNELS + channel)
                        + unsigned(source, ((2 * y + 1) * size + 2 * x) * CHANNELS + channel)
                        + unsigned(source, ((2 * y + 1) * size + 2 * x + 1) * CHANNELS + channel);
                    result[(y * half + x) * CHANNELS + channel] = (byte) ((sum + 2) / 4);
                }
            }
        }
        return result;
    }

    private static int unsigned(byte[] data, int index) {
        return data[index] & 0xFF;
    }

    // ---- cloud_bake_noise ----

    private static void noiseTexel(int x, int y, int z, byte[] out, int offset) {
        float n = NOISE_SIZE;
        float px = (x + 0.5f) / n;
        float py = (y + 0.5f) / n;
        float pz = (z + 0.5f) / n;
        float pf = perlin3(px * 4.0f, py * 4.0f, pz * 4.0f, 4, 11) * 0.5f + perlin3(px * 8.0f, py * 8.0f, pz * 8.0f, 8, 12) * 0.3f
            + perlin3(px * 16.0f, py * 16.0f, pz * 16.0f, 16, 13) * 0.2f;
        pf = saturate(pf * 0.6f + 0.5f);
        float w4 = 1.0f - saturate(worley3(px * 4.0f, py * 4.0f, pz * 4.0f, 4, 21));
        float w8 = 1.0f - saturate(worley3(px * 8.0f, py * 8.0f, pz * 8.0f, 8, 22));
        float w16 = 1.0f - saturate(worley3(px * 16.0f, py * 16.0f, pz * 16.0f, 16, 23));
        float w32 = 1.0f - saturate(worley3(px * 32.0f, py * 32.0f, pz * 32.0f, 32, 24));
        float wf0 = w4 * 0.625f + w8 * 0.25f + w16 * 0.125f;
        float pw = remap(pf, wf0 - 1.0f, 1.0f, 0.0f, 1.0f);
        float g = w4 * 0.625f + w8 * 0.25f + w16 * 0.125f;
        float b = w8 * 0.625f + w16 * 0.25f + w32 * 0.125f;
        float a = w16 * 0.75f + w32 * 0.25f;
        out[offset] = unorm(uniform(pw, 0.67f, 0.059f));
        out[offset + 1] = unorm(uniform(g, 0.48f, 0.118f));
        out[offset + 2] = unorm(uniform(b, 0.48f, 0.118f));
        out[offset + 3] = unorm(uniform(a, 0.48f, 0.14f));
    }

    private static float perlin3(float px, float py, float pz, int period, int salt) {
        int ix = floor(px);
        int iy = floor(py);
        int iz = floor(pz);
        float fx = px - ix;
        float fy = py - iy;
        float fz = pz - iz;
        float ux = fade(fx);
        float uy = fade(fy);
        float uz = fade(fz);
        float n000 = gradDot3(ix, iy, iz, period, salt, fx, fy, fz);
        float n100 = gradDot3(ix + 1, iy, iz, period, salt, fx - 1.0f, fy, fz);
        float n010 = gradDot3(ix, iy + 1, iz, period, salt, fx, fy - 1.0f, fz);
        float n110 = gradDot3(ix + 1, iy + 1, iz, period, salt, fx - 1.0f, fy - 1.0f, fz);
        float n001 = gradDot3(ix, iy, iz + 1, period, salt, fx, fy, fz - 1.0f);
        float n101 = gradDot3(ix + 1, iy, iz + 1, period, salt, fx - 1.0f, fy, fz - 1.0f);
        float n011 = gradDot3(ix, iy + 1, iz + 1, period, salt, fx, fy - 1.0f, fz - 1.0f);
        float n111 = gradDot3(ix + 1, iy + 1, iz + 1, period, salt, fx - 1.0f, fy - 1.0f, fz - 1.0f);
        float x0 = mix(mix(n000, n100, ux), mix(n010, n110, ux), uy);
        float x1 = mix(mix(n001, n101, ux), mix(n011, n111, ux), uy);
        return mix(x0, x1, uz) * 1.15f;
    }

    private static float gradDot3(int cx, int cy, int cz, int period, int salt, float dx, float dy, float dz) {
        int h = hash3(wrap(cx, period), wrap(cy, period), wrap(cz, period), salt);
        float gx = hash01(h) * 2.0f - 1.0f;
        float gy = hash01(h ^ 0x68e31da4) * 2.0f - 1.0f;
        float gz = hash01(h ^ 0xb5297a4d) * 2.0f - 1.0f;
        float inverse = (float) (1.0 / Math.sqrt(Math.max(gx * gx + gy * gy + gz * gz, 1e-6f)));
        return (gx * dx + gy * dy + gz * dz) * inverse;
    }

    private static float worley3(float px, float py, float pz, int period, int salt) {
        int ix = floor(px);
        int iy = floor(py);
        int iz = floor(pz);
        float fx = px - ix;
        float fy = py - iy;
        float fz = pz - iz;
        float md = 9.0f;
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    int h = hash3(wrap(ix + x, period), wrap(iy + y, period), wrap(iz + z, period), salt);
                    float rx = x + hash01(h) - fx;
                    float ry = y + hash01(h ^ 0x1b56c4e9) - fy;
                    float rz = z + hash01(h ^ 0x7f4a7c15) - fz;
                    md = Math.min(md, rx * rx + ry * ry + rz * rz);
                }
            }
        }
        return (float) Math.sqrt(md);
    }

    // ---- cloud_bake_weather ----

    private static void weatherTexel(int x, int y, ByteBuffer out) {
        float n = WEATHER_SIZE;
        float px = (x + 0.5f) / n;
        float py = (y + 0.5f) / n;
        float r = fbm2(px * 3.0f, py * 3.0f, 3, 4, 101);
        float pf = fbm2(px * 12.0f, py * 12.0f, 12, 4, 201);
        float w = 1.0f - saturate(worley2(px * 16.0f, py * 16.0f, 16, 301));
        float w2 = 1.0f - saturate(worley2(px * 32.0f, py * 32.0f, 32, 302));
        float cells = pf + (w * 0.75f + w2 * 0.25f) * 0.45f;
        float b = fbm2(px * 6.0f, py * 6.0f, 6, 5, 401);
        float a = fbm2(px * 24.0f, py * 24.0f, 24, 3, 501);
        out.put(unorm(uniform(r * 0.5f + 0.5f, 0.49f, 0.104f)));
        out.put(unorm(uniform(cells * 0.5f + 0.25f, 0.38f, 0.1035f)));
        out.put(unorm(uniform(b * 0.5f + 0.5f, 0.50f, 0.089f)));
        out.put(unorm(uniform(a * 0.5f + 0.5f, 0.50f, 0.104f)));
    }

    private static float fbm2(float px, float py, int period, int octaves, int salt) {
        float sum = 0.0f;
        float amplitude = 0.5f;
        float norm = 0.0f;
        for (int octave = 0; octave < octaves; octave++) {
            sum += perlin2(px, py, period, salt + octave) * amplitude;
            norm += amplitude;
            px *= 2.0f;
            py *= 2.0f;
            period *= 2;
            amplitude *= 0.5f;
        }
        return sum / norm;
    }

    private static float perlin2(float px, float py, int period, int salt) {
        int ix = floor(px);
        int iy = floor(py);
        float fx = px - ix;
        float fy = py - iy;
        float ux = fade(fx);
        float uy = fade(fy);
        float n00 = gradDot2(ix, iy, period, salt, fx, fy);
        float n10 = gradDot2(ix + 1, iy, period, salt, fx - 1.0f, fy);
        float n01 = gradDot2(ix, iy + 1, period, salt, fx, fy - 1.0f);
        float n11 = gradDot2(ix + 1, iy + 1, period, salt, fx - 1.0f, fy - 1.0f);
        return mix(mix(n00, n10, ux), mix(n01, n11, ux), uy) * 1.4f;
    }

    private static float gradDot2(int cx, int cy, int period, int salt, float dx, float dy) {
        int h = hash3(wrap(cx, period), wrap(cy, period), 0, salt);
        float angle = hash01(h) * 6.2831853f;
        return (float) Math.cos(angle) * dx + (float) Math.sin(angle) * dy;
    }

    private static float worley2(float px, float py, int period, int salt) {
        int ix = floor(px);
        int iy = floor(py);
        float fx = px - ix;
        float fy = py - iy;
        float md = 9.0f;
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                int h = hash3(wrap(ix + x, period), wrap(iy + y, period), 1, salt);
                float rx = x + hash01(h) - fx;
                float ry = y + hash01(h ^ 0x1b56c4e9) - fy;
                md = Math.min(md, rx * rx + ry * ry);
            }
        }
        return (float) Math.sqrt(md);
    }

    // ---- shared helpers, the mod's shadeHash family ----

    private static int hash(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    private static float hash01(int x) {
        return (hash(x) >>> 8) * (1.0f / 16777216.0f);
    }

    private static int hash3(int x, int y, int z, int salt) {
        return hash(x * 0x8da6b343 ^ hash(y * 0xd8163841 ^ hash(z * 0xcb1ab31f ^ salt)));
    }

    private static int wrap(int c, int period) {
        return ((c % period) + period) % period;
    }

    private static int floor(float v) {
        return (int) Math.floor(v);
    }

    private static float fade(float f) {
        return f * f * f * (f * (f * 6.0f - 15.0f) + 10.0f);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float saturate(float v) {
        return Math.min(Math.max(v, 0.0f), 1.0f);
    }

    private static float remap(float v, float low, float high, float a, float b) {
        return a + (b - a) * saturate((v - low) / Math.max(high - low, 1e-5f));
    }

    private static float uniform(float v, float mu, float sigma) {
        return (float) (1.0 / (1.0 + Math.exp(-1.702f * (v - mu) / sigma)));
    }

    /** The mod writes half precision into RGBA8Unorm storage: round to the nearest of 256 levels. */
    private static byte unorm(float v) {
        return (byte) Math.round(saturate(v) * 255.0f);
    }
}
