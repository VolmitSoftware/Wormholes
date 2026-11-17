package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.EnvironmentState;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.FogShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Pure conversions from a streamed {@link EnvironmentState} to 1.20.1 lightmap pixels and linear fog. */
final class PortalEnvironment {
    private PortalEnvironment() {
    }

    static float angle(float from, float to, float blend) {
        double difference = to - from;
        return from + (float) Math.atan2(Math.sin(difference), Math.cos(difference)) * blend;
    }

    /**
     * Fills the 16x16 lightmap (x block level, y sky level) the way the game's lightmap shader combines its inputs,
     * using the destination's light colours and the local player's brightness and night vision.
     */
    static void lightmap(NativeImage image, EnvironmentState environment, float brightness, float nightVision) {
        EnvironmentState.Lighting lighting = environment.lighting();
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                float blockLevel = block / 15.0F;
                float skyLevel = sky / 15.0F;
                float blockBrightness = level(blockLevel);
                float skyBrightness = level(skyLevel) * lighting.skyFactor();
                float red = Math.max(lighting.ambient().red(), nightVision);
                float green = Math.max(lighting.ambient().green(), nightVision);
                float blue = Math.max(lighting.ambient().blue(), nightVision);
                red += lighting.skyColor().red() * skyBrightness;
                green += lighting.skyColor().green() * skyBrightness;
                blue += lighting.skyColor().blue() * skyBrightness;
                float mix = 0.9F * (2.0F * blockLevel - 1.0F) * (2.0F * blockLevel - 1.0F);
                red += (lighting.blockTint().red() + (1.0F - lighting.blockTint().red()) * mix) * blockBrightness;
                green += (lighting.blockTint().green() + (1.0F - lighting.blockTint().green()) * mix) * blockBrightness;
                blue += (lighting.blockTint().blue() + (1.0F - lighting.blockTint().blue()) * mix) * blockBrightness;
                Vector3f color = new Vector3f(clamp(red), clamp(green), clamp(blue));
                Vector3f lifted = notGamma(color);
                color.lerp(lifted, brightness);
                image.setPixelRGBA(block, sky, 0xFF000000 | (int) (color.z * 255.0F) << 16 | (int) (color.y * 255.0F) << 8
                    | (int) (color.x * 255.0F));
            }
        }
    }

    private static float level(float value) {
        return value / (4.0F - 3.0F * value);
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static Vector3f notGamma(Vector3f color) {
        float max = Math.max(Math.max(color.x, color.y), color.z);
        if (max <= 0.0F) {
            return new Vector3f(color);
        }
        float inverted = 1.0F - max;
        float scaled = 1.0F - inverted * inverted * inverted * inverted;
        return new Vector3f(color).mul(scaled / max);
    }

    /** The destination's fog for a camera looking along {@code viewRotation}, blended toward sunrise and sky colour like the game's. */
    static Fog fog(EnvironmentState environment, Matrix4f viewRotation, int renderDistanceChunks) {
        EnvironmentState.Fog source = environment.fog();
        EnvironmentState.Sky sky = environment.sky();
        Vector3f color = new Vector3f(source.color().red(), source.color().green(), source.color().blue());
        Vector3f forwards = new Vector3f(0.0F, 0.0F, -1.0F);
        new Matrix4f(viewRotation).mul(PortalProjection.rotation(environment.transform())).invert().transformDirection(forwards).normalize();
        float sunriseDirection = Math.sin(sky.sunAngle()) > 0.0 ? -1.0F : 1.0F;
        float sunrise = Math.max(0.0F, forwards.x * sunriseDirection) * sky.sunrise().alpha();
        if (renderDistanceChunks >= 4 && sunrise > 0.0F) {
            color.lerp(new Vector3f(sky.sunrise().red(), sky.sunrise().green(), sky.sunrise().blue()), sunrise);
        }
        Vector3f skyColor = new Vector3f(sky.color().red(), sky.color().green(), sky.color().blue());
        skyColor.mul(1.0F - sky.rain() * 0.5F, 1.0F - sky.rain() * 0.5F, 1.0F - sky.rain() * 0.4F);
        skyColor.mul(1.0F - sky.thunder() * 0.5F);
        float blend = 1.0F - (float) Math.pow(0.25F + 0.75F * Math.clamp(Math.min(source.skyEnd() / 16.0F, renderDistanceChunks) / 32.0F,
            0.0F, 1.0F), 0.25);
        color.lerp(skyColor, blend);
        float renderEnd = renderDistanceChunks * 16.0F;
        float renderStart = renderEnd - Math.clamp(renderEnd / 10.0F, 4.0F, 64.0F);
        if (source.end() < renderEnd) {
            return new Fog(color, source.start(), source.end(), FogShape.SPHERE);
        }
        return new Fog(color, renderStart, renderEnd, FogShape.CYLINDER);
    }

    record Fog(Vector3f color, float start, float end, FogShape shape) {
    }
}
