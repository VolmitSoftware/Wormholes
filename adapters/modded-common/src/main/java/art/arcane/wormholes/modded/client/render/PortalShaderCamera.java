package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
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

    PortalShaderCamera(ClientViewEnvironment environment, CameraRenderState display) {
        setEntity(Minecraft.getInstance().getCameraEntity());
        GeometryVector eye = environment.transform().destinationPoint(display.pos.x, display.pos.y, display.pos.z);
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

        private Probe(ClientViewEnvironment environment) {
            ClientViewEnvironment.Sky sky = environment.sky();
            values.put(EnvironmentAttributes.SUN_ANGLE, (float) Math.toDegrees(sky.sunAngle()));
            values.put(EnvironmentAttributes.MOON_ANGLE, (float) Math.toDegrees(sky.moonAngle()));
            values.put(EnvironmentAttributes.STAR_ANGLE, (float) Math.toDegrees(sky.starAngle()));
            values.put(EnvironmentAttributes.STAR_BRIGHTNESS, sky.starBrightness());
            values.put(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, PortalEnvironment.vector(sky.sunrise()));
            values.put(EnvironmentAttributes.SKY_COLOR, PortalEnvironment.vector(sky.color()));
            values.put(EnvironmentAttributes.MOON_PHASE, MoonPhase.values()[sky.moonPhase()]);
            ClientViewEnvironment.Fog fog = environment.fog();
            values.put(EnvironmentAttributes.FOG_COLOR, PortalEnvironment.vector(fog.color()));
            values.put(EnvironmentAttributes.FOG_START_DISTANCE, fog.start());
            values.put(EnvironmentAttributes.FOG_END_DISTANCE, fog.end());
            values.put(EnvironmentAttributes.SKY_FOG_END_DISTANCE, fog.skyEnd());
            values.put(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE, fog.cloudEnd());
            values.put(EnvironmentAttributes.WATER_FOG_COLOR, PortalEnvironment.vector(fog.waterColor()));
            values.put(EnvironmentAttributes.WATER_FOG_START_DISTANCE, fog.waterStart());
            values.put(EnvironmentAttributes.WATER_FOG_END_DISTANCE, fog.waterEnd());
            ClientViewEnvironment.Lighting lighting = environment.lighting();
            values.put(EnvironmentAttributes.BLOCK_LIGHT_TINT, PortalEnvironment.vector(lighting.blockTint()));
            values.put(EnvironmentAttributes.SKY_LIGHT_FACTOR, lighting.skyFactor());
            values.put(EnvironmentAttributes.SKY_LIGHT_COLOR, PortalEnvironment.vector(lighting.skyColor()));
            values.put(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, PortalEnvironment.vector(lighting.ambient()));
            values.put(EnvironmentAttributes.CLOUD_COLOR, PortalEnvironment.vector(environment.clouds().color()));
            values.put(EnvironmentAttributes.CLOUD_HEIGHT, environment.clouds().height());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Value> Value getValue(EnvironmentAttribute<Value> attribute, float partialTicks) {
            Object value = values.get(attribute);
            return value == null ? attribute.defaultValue() : (Value) value;
        }
    }
}
