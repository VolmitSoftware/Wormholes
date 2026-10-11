package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;

final class PortalShaderCamera extends Camera {
    private final FogType medium;
    private final Matrix4f viewRotation;
    private final Probe attributes;

    PortalShaderCamera(EnvironmentState environment, CameraRenderState display) {
        setEntity(Minecraft.getInstance().getCameraEntity());
        Vec3d eye = environment.transform().inverse().point(new Vec3d(display.pos.x, display.pos.y, display.pos.z));
        setPosition(eye.x(), eye.y(), eye.z());
        viewRotation = new Matrix4f(display.viewRotationMatrix).mul(PortalProjection.rotation(environment.transform()));
        medium = FogType.valueOf(environment.world().eyeMedium().name());
        attributes = new Probe(environment);
    }

    @Override
    public Matrix4f getViewRotationMatrix(Matrix4f destination) {
        return destination.set(viewRotation);
    }

    @Override
    public EnvironmentAttributeProbe attributeProbe() {
        return attributes;
    }

    @Override
    public FogType getFluidInCamera() {
        return medium;
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    private static final class Probe extends EnvironmentAttributeProbe {
        private final Map<EnvironmentAttribute<?>, Object> values = new IdentityHashMap<>();

        private Probe(EnvironmentState environment) {
            EnvironmentState.Sky sky = environment.sky();
            put(EnvironmentAttributes.SUN_ANGLE, (float) Math.toDegrees(sky.sunAngle()));
            put(EnvironmentAttributes.MOON_ANGLE, (float) Math.toDegrees(sky.moonAngle()));
            put(EnvironmentAttributes.STAR_ANGLE, (float) Math.toDegrees(sky.starAngle()));
            put(EnvironmentAttributes.STAR_BRIGHTNESS, sky.starBrightness());
            put(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, color(sky.sunrise()));
            put(EnvironmentAttributes.SKY_COLOR, color(sky.color()));
            put(EnvironmentAttributes.MOON_PHASE, MoonPhase.values()[sky.moonPhase()]);
            EnvironmentState.Fog fog = environment.fog();
            put(EnvironmentAttributes.FOG_COLOR, color(fog.color()));
            put(EnvironmentAttributes.FOG_START_DISTANCE, fog.start());
            put(EnvironmentAttributes.FOG_END_DISTANCE, fog.end());
            put(EnvironmentAttributes.SKY_FOG_END_DISTANCE, fog.skyEnd());
            put(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE, fog.cloudEnd());
            put(EnvironmentAttributes.WATER_FOG_COLOR, color(fog.waterColor()));
            put(EnvironmentAttributes.WATER_FOG_START_DISTANCE, fog.waterStart());
            put(EnvironmentAttributes.WATER_FOG_END_DISTANCE, fog.waterEnd());
            EnvironmentState.Lighting lighting = environment.lighting();
            put(EnvironmentAttributes.BLOCK_LIGHT_TINT, color(lighting.blockTint()));
            put(EnvironmentAttributes.SKY_LIGHT_FACTOR, lighting.skyFactor());
            put(EnvironmentAttributes.SKY_LIGHT_COLOR, color(lighting.skyColor()));
            put(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, color(lighting.ambient()));
            put(EnvironmentAttributes.CLOUD_COLOR, color(environment.clouds().color()));
            put(EnvironmentAttributes.CLOUD_HEIGHT, environment.clouds().height());
        }

        private <Value> void put(EnvironmentAttribute<Value> attribute, Value value) {
            values.put(attribute, value);
        }

        private static int color(EnvironmentState.Color color) {
            return ARGB.colorFromFloat(1.0F, color.red(), color.green(), color.blue());
        }

        private static int color(EnvironmentState.ColorAlpha color) {
            return ARGB.colorFromFloat(color.alpha(), color.red(), color.green(), color.blue());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Value> Value getValue(EnvironmentAttribute<Value> attribute, float partialTicks) {
            Object value = values.get(attribute);
            return value == null ? attribute.defaultValue() : (Value) value;
        }
    }
}
