package art.arcane.wormholes.modded.client.render.metal;

import art.arcane.wormholes.modded.client.render.StreamedShading;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Metal Shaders shading streamed portal views. Metal Shaders is forward-shaded around one camera and has no second
 * view, so Wormholes keeps its own cameras, targets and pipelines and feeds them through the two extension points the
 * mod offers: a pass redirector, which compiles the pipelines of a pass into Wormholes' RGBA16F portal targets for
 * Metal Shaders' HDR format, and the per-pipeline MSL registry, which lights the portal block pipelines with Metal
 * Shaders' own functions and traces the portal sky along the portal camera. The composite pipelines get identity
 * variants so linear radiance reaches the main HDR target untouched.
 */
public final class MetalStreamedShading implements StreamedShading {
    public static final MetalStreamedShading INSTANCE = new MetalStreamedShading();
    private static final Logger LOGGER = LoggerFactory.getLogger(MetalStreamedShading.class);
    private static final String PASS_PREFIX = "Wormholes";
    private static final int REDIRECTOR_ORDER = 10;
    private static final String[] LAYERS = {"solid", "cutout", "translucent"};
    private static final String[] SHAPES = {"", "_reflected"};
    private static final String[] SAMPLINGS = {"", "_rgss"};
    private static final List<String> BLOCK_UNIFORMS = List.of("Portal", "DynamicTransforms", "Fog", "Sampler0");
    private static final List<String> SKY_UNIFORMS = List.of("DynamicTransforms", "Fog");
    private static final int VIEW = 0;
    private static final int VIEW_PROJECTION = 128;
    private static final int INVERSE_VIEW_PROJECTION = 192;
    private static final int PREVIOUS_VIEW_PROJECTION = 256;
    private static final int CAMERA_BLOCK = 448;
    private static final int CAMERA_FRACTION = 464;
    private static final int SUN_DIRECTION_WORLD = 480;
    private static final int SUN_DIRECTION_VIEW = 496;
    private static final int SCREEN = 848;
    private static final int JITTER = 896;
    private static final int LIGHT_DIRECTION = 528;
    private static final int LIGHT_COLOR = 544;
    private static final int AMBIENT_SKY = 560;
    private static final int AMBIENT_GROUND = 576;
    private static final int HORIZON = 688;
    private static final int EXPOSURE = 872;
    private static final int AMBIENT_TOWARD = 928;
    private static final int AMBIENT_AWAY = 944;
    private static final int FLAGS = 960;
    private static final int SUN_PATH_BITS = 1000;
    private static final int DIMENSION_END = 2;
    private static final int DIMS_LIT_BIT = 1 << 23;
    // the End as Metal Shaders' dims module sets it (ShadeDims.end): its exposure, the reference exposure its lights are
    // written against, the key light, ambient and fog colours (sRGB) with their luminances, the sky palette (sRGB, converted
    // through the renderer's LDR-to-HDR curve) with each entry's share of the player's sky gain, and the key light's direction
    private static final float END_EXPOSURE = 0.6536f;
    private static final float END_REFERENCE_EXPOSURE = 0.86f;
    private static final int END_KEY_HEX = 13089023;
    private static final float END_KEY_LUMA = 0.72f;
    private static final int END_AMBIENT_HEX = 5917338;
    private static final float END_AMBIENT_LUMA = 0.17f;
    private static final int END_FOG_HEX = 1708080;
    private static final float END_FOG_LUMA = 0.058139537f;
    private static final int[] END_SKY_HEX = {460047, 1839664, 10119935, 7004415, 6963104, 3112847};
    private static final float[] END_SKY_GAIN = {1.0f, 1.0f, 1.0f, 1.0f, 0.7f, 0.6f};
    private static final double END_LIGHT_ELEVATION = Math.toRadians(55.0D);
    private static final double END_LIGHT_AZIMUTH = Math.toRadians(30.0D);
    private static final Vector3f END_LIGHT = new Vector3f((float) (Math.cos(END_LIGHT_ELEVATION) * Math.sin(END_LIGHT_AZIMUTH)),
        (float) Math.sin(END_LIGHT_ELEVATION), (float) (Math.cos(END_LIGHT_ELEVATION) * Math.cos(END_LIGHT_AZIMUTH)));
    private static final float[] BLACK = {0.0f, 0.0f, 0.0f};
    private static final double SEA_LEVEL = 63.0D;
    private static final int LAYER_CAMERA_Y = 168;
    private static final int ORIGIN = 192;
    private static final int CIRRUS_CAMERA = 312;
    private static final double WEATHER_PERIOD = 16384.0D;
    private static final double NOISE_PERIOD = 7680.0D;
    private static final double CIRRUS_COS = Math.cos(0.52f);
    private static final double CIRRUS_SIN = Math.sin(0.52f);

    private MetalShadersApi api;
    private boolean prepared;
    private boolean failed;
    private boolean shadesWater;
    private final ByteBuffer frame = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final Vector3f sunDirection = new Vector3f();
    private final MetalCloudTextures cloudTextures = new MetalCloudTextures();

    private MetalStreamedShading() {
    }

    @Override
    public String name() {
        return "metal_shaders";
    }

    @Override
    public void prepare() {
        RenderSystem.assertOnRenderThread();
        if (prepared || failed) {
            return;
        }
        try {
            api = MetalShadersApi.bind();
            MetalPortalMsl msl = new MetalPortalMsl(api.prelude(), api.waterOn() ? api.weatherPrelude() + "\n" + api.waterPrelude() : "", api.source("clouds.metal"));
            for (String layer : LAYERS) {
                UnaryOperator<String> fragment = layer.equals("translucent") ? msl::translucentFragment : msl::blockFragment;
                for (String shape : SHAPES) {
                    for (String sampling : SAMPLINGS) {
                        api.register("wormholes:portal_" + layer + shape + sampling, msl::blockVertex, fragment, BLOCK_UNIFORMS);
                    }
                }
            }
            shadesWater = msl.shadesWater();
            api.register("wormholes:portal_sky", source -> msl.skyVertex(), source -> msl.skyFragment(), SKY_UNIFORMS);
            for (String passthrough : List.of("wormholes:portal_composite", "wormholes:portal_composite_shape", "wormholes:portal_layer")) {
                api.register(passthrough, UnaryOperator.identity(), UnaryOperator.identity(), List.of());
            }
            api.addRedirector(REDIRECTOR_ORDER, this::redirect);
            prepared = true;
            LOGGER.info("Streamed portal views shade through Metal Shaders: HDR shading {}", api.hdrShadingActive() ? "active" : "inactive");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failed = true;
            api = null;
            LOGGER.error("Metal Shaders is installed but Wormholes could not drive its shading; streamed views keep the game's lighting", failure);
        }
    }

    @Override
    public boolean active() {
        return prepared && api.hdrShadingActive();
    }

    @Override
    public GpuFormat colorFormat() {
        return active() ? GpuFormat.RGBA16_FLOAT : GpuFormat.RGBA8_UNORM;
    }

    /**
     * Metal Shaders' sky, sun, haze and clouds are the overworld's, drawn for an overworld camera; its End sky needs
     * only the End's fixed light, which a view into the End carries whatever the camera is under; any other sky stays
     * the game's.
     */
    @Override
    public int skyKind(DimensionType.Skybox camera, DimensionType.Skybox destination) {
        if (destination == DimensionType.Skybox.END) {
            return api.dimsOn() ? SKY_END : SKY_NONE;
        }
        return camera == DimensionType.Skybox.OVERWORLD && destination == DimensionType.Skybox.OVERWORLD ? SKY_OVERWORLD : SKY_NONE;
    }

    /**
     * Its per-pixel light is the camera's dimension's; a view into the End from elsewhere takes the End's fixed light
     * ({@link #writeFrame}); a view into any other kind of sky keeps the game's light, converted.
     */
    @Override
    public int shadeMode(boolean sameLevel, DimensionType.Skybox camera, DimensionType.Skybox destination, int depth) {
        if (destination != camera) {
            return destination == DimensionType.Skybox.END && api.dimsOn() ? SHADE_LIT_FOREIGN : SHADE_VANILLA;
        }
        return sameLevel && depth == 0 ? SHADE_LIT_SHADOWED : SHADE_LIT;
    }

    /**
     * Metal Shaders' live cloud parameters with the four camera-position fields recomputed for the view's camera in the
     * destination: the camera height, the camera in the weather map's and the noise volume's wrapped spaces (wind
     * included) and in the cirrus map's rotated space. The march reads nothing else about the camera.
     */
    @Override
    public void writeClouds(ByteBuffer out, double cameraX, double cameraY, double cameraZ) {
        cloudTextures.upload();
        ByteBuffer packed = api.cloudsPacked();
        int start = out.position();
        out.put(packed.duplicate().clear().limit(CLOUD_BYTES));
        if (api.cloudVanillaCells()) {
            return;
        }
        double[] wind = api.cloudWind();
        out.putFloat(start + LAYER_CAMERA_Y, (float) cameraY);
        out.putFloat(start + ORIGIN, (float) wrap(cameraX + wind[0], WEATHER_PERIOD));
        out.putFloat(start + ORIGIN + 4, (float) wrap(cameraZ + wind[1], WEATHER_PERIOD));
        out.putFloat(start + ORIGIN + 8, (float) wrap(cameraX + wind[2], NOISE_PERIOD));
        out.putFloat(start + ORIGIN + 12, (float) wrap(cameraZ + wind[3], NOISE_PERIOD));
        double cirrusX = cameraX + wind[4];
        double cirrusZ = cameraZ + wind[5];
        out.putFloat(start + CIRRUS_CAMERA, (float) wrap(cirrusX * CIRRUS_COS + cirrusZ * CIRRUS_SIN, WEATHER_PERIOD));
        out.putFloat(start + CIRRUS_CAMERA + 4, (float) wrap(-cirrusX * CIRRUS_SIN + cirrusZ * CIRRUS_COS, WEATHER_PERIOD));
    }

    private static double wrap(double value, double period) {
        double remainder = value % period;
        return remainder < 0.0D ? remainder + period : remainder;
    }

    @Override
    public void bindSky(RenderPass pass) {
        GpuSampler sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
        pass.setUniform("CloudWeather", cloudTextures.weather(), sampler);
        pass.setUniform("CloudNoise", cloudTextures.noise(), sampler);
    }

    @Override
    public boolean shadesWater() {
        return prepared && shadesWater;
    }

    /**
     * Metal Shaders' live frame with its camera replaced by the view's: the rotation and projection the view draws with
     * (so its water projects and reflects inside the view's own target), the camera's position in the destination (so
     * waves, caustics and haze heights follow the destination), the target's size, and no temporal jitter or history.
     * A view into the End from another dimension gets the End's light written over the camera's.
     */
    @Override
    public void writeFrame(ByteBuffer out, Matrix4fc view, Matrix4fc projection, double cameraX, double cameraY, double cameraZ, int width, int height,
                           DimensionType.Skybox destination) {
        frame.clear();
        api.packFrame(frame);
        viewProjection.set(projection).mul(view);
        inverseViewProjection.set(viewProjection).invert();
        view.get(VIEW, frame);
        viewProjection.get(VIEW_PROJECTION, frame);
        inverseViewProjection.get(INVERSE_VIEW_PROJECTION, frame);
        viewProjection.get(PREVIOUS_VIEW_PROJECTION, frame);
        double blockX = Math.floor(cameraX);
        double blockY = Math.floor(cameraY);
        double blockZ = Math.floor(cameraZ);
        frame.putFloat(CAMERA_BLOCK, (float) blockX).putFloat(CAMERA_BLOCK + 4, (float) blockY).putFloat(CAMERA_BLOCK + 8, (float) blockZ);
        frame.putFloat(CAMERA_FRACTION, (float) (cameraX - blockX)).putFloat(CAMERA_FRACTION + 4, (float) (cameraY - blockY))
            .putFloat(CAMERA_FRACTION + 8, (float) (cameraZ - blockZ)).putFloat(CAMERA_FRACTION + 12, (float) (cameraY - SEA_LEVEL));
        sunDirection.set(frame.getFloat(SUN_DIRECTION_WORLD), frame.getFloat(SUN_DIRECTION_WORLD + 4), frame.getFloat(SUN_DIRECTION_WORLD + 8));
        view.transformDirection(sunDirection);
        frame.putFloat(SUN_DIRECTION_VIEW, sunDirection.x).putFloat(SUN_DIRECTION_VIEW + 4, sunDirection.y).putFloat(SUN_DIRECTION_VIEW + 8, sunDirection.z)
            .putFloat(SUN_DIRECTION_VIEW + 12, 0.0f);
        frame.putFloat(SCREEN, width).putFloat(SCREEN + 4, height).putFloat(SCREEN + 8, 1.0f / width).putFloat(SCREEN + 12, 1.0f / height);
        frame.putFloat(JITTER, 0.0f).putFloat(JITTER + 4, 0.0f).putFloat(JITTER + 8, 0.0f).putFloat(JITTER + 12, 0.0f);
        if (destination == DimensionType.Skybox.END && frame.getInt(FLAGS) != DIMENSION_END && api.dimsOn()) {
            writeEndLight();
        }
        frame.position(0).limit(FRAME_BYTES);
        out.put(frame);
    }

    /**
     * The End's light as Metal Shaders' dims module sets it for a camera in the End: its fixed violet key light, ambient,
     * fog and sky palette with the player's End light and sky gains, written over the live frame's for a view into the End
     * from another dimension, with the End's exposure under the player's EV settings in the frame's exposure slot, which
     * the foreign shade mode exposes by instead of the camera's eye-adapted exposure, so the view keeps the End's
     * brightness. The dims module's lit flag goes with it, so the view's terrain takes that light.
     */
    private void writeEndLight() {
        float liveExposure = frame.getFloat(EXPOSURE);
        frame.putFloat(EXPOSURE, liveExposure > 0.0f ? END_EXPOSURE * api.exposureReference() / liveExposure : END_EXPOSURE);
        float light = api.userGain("mcopt.shade.user.endLight") / END_REFERENCE_EXPOSURE;
        float skyGain = api.userGain("mcopt.shade.user.endSky") / END_REFERENCE_EXPOSURE;
        float[] key = linear(END_KEY_HEX);
        float keyLuma = Math.max(luma(key), 1.0e-6f);
        for (int channel = 0; channel < 3; channel++) {
            key[channel] = 0.6f * key[channel] / keyLuma + 0.4f;
        }
        withLuma(key, END_KEY_LUMA * light);
        float[] ambient = linear(END_AMBIENT_HEX);
        withLuma(ambient, END_AMBIENT_LUMA * light);
        float[] fog = linear(END_FOG_HEX);
        withLuma(fog, END_FOG_LUMA);
        frame.putInt(FLAGS, DIMENSION_END);
        frame.putFloat(SUN_PATH_BITS, (float) ((int) frame.getFloat(SUN_PATH_BITS) | DIMS_LIT_BIT));
        frame.putFloat(LIGHT_DIRECTION, END_LIGHT.x).putFloat(LIGHT_DIRECTION + 4, END_LIGHT.y).putFloat(LIGHT_DIRECTION + 8, END_LIGHT.z)
            .putFloat(LIGHT_DIRECTION + 12, 1.0f);
        putColor(LIGHT_COLOR, key, 1.0f, luma(key));
        putColor(AMBIENT_SKY, ambient, 1.0f, 0.0f);
        putColor(AMBIENT_GROUND, ambient, 0.55f, 0.0f);
        putColor(AMBIENT_TOWARD, ambient, 0.8f, 0.0f);
        putColor(AMBIENT_AWAY, ambient, 0.8f, 0.0f);
        putColor(HORIZON, fog, 1.0f, 0.0f);
        putColor(HORIZON + 16, BLACK, 0.0f, 0.0f);
        for (int index = 0; index < END_SKY_HEX.length; index++) {
            int hex = END_SKY_HEX[index];
            float[] hdr = api.ldrToHdr(channel(hex, 16), channel(hex, 8), channel(hex, 0));
            putColor(HORIZON + 16 * (2 + index), hdr, END_SKY_GAIN[index] * skyGain, 0.0f);
        }
    }

    private void putColor(int offset, float[] color, float scale, float w) {
        frame.putFloat(offset, color[0] * scale).putFloat(offset + 4, color[1] * scale).putFloat(offset + 8, color[2] * scale).putFloat(offset + 12, w);
    }

    private static float channel(int hex, int shift) {
        return ((hex >> shift) & 0xFF) / 255.0f;
    }

    private static float[] linear(int hex) {
        float[] color = {channel(hex, 16), channel(hex, 8), channel(hex, 0)};
        for (int index = 0; index < 3; index++) {
            color[index] = color[index] <= 0.04045f ? color[index] / 12.92f : (float) Math.pow((color[index] + 0.055f) / 1.055f, 2.4D);
        }
        return color;
    }

    private static float luma(float[] color) {
        return 0.2126f * color[0] + 0.7152f * color[1] + 0.0722f * color[2];
    }

    private static void withLuma(float[] color, float target) {
        float current = luma(color);
        if (current > 0.0f) {
            for (int index = 0; index < 3; index++) {
                color[index] *= target / current;
            }
        }
    }

    @Override
    public void captureScene() {
        if (!active()) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (encoder instanceof FrontendCommandEncoder frontend) {
            api.captureWater(frontend.backend());
        }
    }

    /**
     * A Wormholes pass into one of its RGBA16F portal targets keeps its attachments, area and depth, but its pipelines
     * compile for Metal Shaders' HDR format, which is what makes the mod rewrite them; an explicit clear colour is
     * converted the way Metal Shaders converts the game's own clears.
     */
    private Object redirect(RenderPassDescriptor descriptor) {
        if (!active()) {
            return null;
        }
        String label = descriptor.label().get();
        if (label == null || !label.startsWith(PASS_PREFIX)) {
            return null;
        }
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        if (colors.size() != 1 || colors.getFirst() == null) {
            return null;
        }
        RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.getFirst();
        GpuTextureView view = attachment.textureView();
        GpuTexture texture = view.texture();
        if (view.baseMipLevel() != 0 || texture.getFormat() != GpuFormat.RGBA16_FLOAT) {
            return null;
        }
        float[][] clears = null;
        Optional<Vector4fc> clear = attachment.clearValue();
        if (clear.isPresent()) {
            Vector4fc color = clear.get();
            float[] scene = api.ldrToHdr(color.x(), color.y(), color.z());
            clears = new float[][] {{scene[0], scene[1], scene[2], color.w()}};
        }
        return api.redirect(new long[] {api.textureHandle(texture)}, clears, texture.getWidth(0), texture.getHeight(0),
            new int[] {MetalShadersApi.RGBA16_FLOAT});
    }
}
