package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

final class PortalEnvironment {
    private PortalEnvironment() {
    }

    static float angle(float from, float to, float blend) {
        double difference = to - from;
        return from + (float) Math.atan2(Math.sin(difference), Math.cos(difference)) * blend;
    }

    static SkyRenderState sky(EnvironmentState environment, Vec3d eye) {
        EnvironmentState.Sky source = environment.sky();
        SkyRenderState state = new SkyRenderState();
        state.skybox = switch (source.skybox()) {
            case NONE -> DimensionType.Skybox.NONE;
            case OVERWORLD -> DimensionType.Skybox.OVERWORLD;
            case END -> DimensionType.Skybox.END;
        };
        state.sunAngle = source.sunAngle();
        state.moonAngle = source.moonAngle();
        state.starAngle = source.starAngle();
        state.rainBrightness = 1.0f - source.rain();
        state.starBrightness = source.starBrightness();
        state.sunriseAndSunsetColor = vector(source.sunrise());
        state.skyColor = vector(source.color());
        state.moonPhase = MoonPhase.values()[source.moonPhase()];
        state.shouldRenderDarkDisc = eye.y() < environment.dimension().horizonHeight();
        return state;
    }

    static LightmapRenderState light(EnvironmentState environment, LightmapRenderState local) {
        LightmapRenderState state = new LightmapRenderState();
        state.needsUpdate = true;
        state.blockFactor = local.blockFactor;
        state.blockLightTint = vector(environment.lighting().blockTint());
        state.skyFactor = environment.lighting().skyFactor();
        state.skyLightColor = vector(environment.lighting().skyColor());
        state.ambientColor = vector(environment.lighting().ambient());
        state.brightness = local.brightness;
        state.darknessEffectScale = local.darknessEffectScale;
        state.nightVisionEffectIntensity = local.nightVisionEffectIntensity;
        state.nightVisionColor = local.nightVisionColor;
        state.bossOverlayWorldDarkening = local.bossOverlayWorldDarkening;
        return state;
    }

    static FogData fog(EnvironmentState environment, CameraRenderState camera, int distance, int cloudDistance) {
        EnvironmentState.Fog source = environment.fog();
        EnvironmentState.Sky sky = environment.sky();
        Vector3fc color = vector(source.color());
        Vector3f forwards = new Vector3f(0.0f, 0.0f, -1.0f);
        new Matrix4f(camera.viewRotationMatrix).mul(PortalProjection.rotation(environment.transform())).invert().transformDirection(forwards);
        float sunriseDirection = Math.sin(sky.sunAngle()) > 0.0 ? -1.0f : 1.0f;
        float sunrise = Math.max(0.0f, forwards.x * sunriseDirection) * sky.sunrise().alpha();
        if (distance >= 4 && sunrise > 0.0f) {
            color = ARGB.srgbLerp(sunrise, color, new Vector3f(sky.sunrise().red(), sky.sunrise().green(), sky.sunrise().blue()));
        }
        Vector3fc skyColor = vector(sky.color());
        skyColor = ARGB.scaleRGB(skyColor, 1.0f - sky.rain() * 0.5f, 1.0f - sky.rain() * 0.5f, 1.0f - sky.rain() * 0.4f);
        skyColor = ARGB.scaleRGB(skyColor, 1.0f - sky.thunder() * 0.5f);
        float blend = 1.0f - (float) Math.pow(0.25f + 0.75f * Math.clamp(Math.min(source.skyEnd() / 16.0f, distance) / 32.0f, 0.0f, 1.0f), 0.25);
        color = ARGB.srgbLerp(blend, color, skyColor);
        FogData result = new FogData();
        result.color.set(color, 1.0f);
        result.environmentalStart = source.start();
        result.environmentalEnd = source.end();
        result.renderDistanceEnd = distance * 16.0f;
        result.renderDistanceStart = result.renderDistanceEnd - Math.clamp(result.renderDistanceEnd / 10.0f, 4.0f, 64.0f);
        result.skyEnd = Math.min(result.renderDistanceEnd, source.skyEnd());
        result.cloudEnd = Math.min(cloudDistance * 16.0f, source.cloudEnd());
        return result;
    }

    static Vector3f vector(EnvironmentState.Color color) {
        return new Vector3f(color.red(), color.green(), color.blue());
    }

    static Vector4f vector(EnvironmentState.ColorAlpha color) {
        return new Vector4f(color.red(), color.green(), color.blue(), color.alpha());
    }
}
