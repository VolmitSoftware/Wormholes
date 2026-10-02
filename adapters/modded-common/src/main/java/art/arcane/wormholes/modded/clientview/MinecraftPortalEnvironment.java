package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.joml.Vector4fc;

final class MinecraftPortalEnvironment {
    private MinecraftPortalEnvironment() {
    }

    static ClientViewEnvironment capture(ServerLevel world, GeometryVector destinationEye, ClientViewEnvironment.Transform transform) {
        Vec3 eye = new Vec3(destinationEye.x(), destinationEye.y(), destinationEye.z());
        EnvironmentAttributeSystem attributes = world.environmentAttributes();
        DimensionType dimension = world.dimensionType();
        ClientViewEnvironment.Sky sky = new ClientViewEnvironment.Sky(ClientViewEnvironment.Skybox.valueOf(dimension.skybox().name()),
            radians(attributes.getValue(EnvironmentAttributes.SUN_ANGLE, eye)), radians(attributes.getValue(EnvironmentAttributes.MOON_ANGLE, eye)),
            radians(attributes.getValue(EnvironmentAttributes.STAR_ANGLE, eye)), attributes.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, eye),
            color(attributes.getValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, eye)), color(attributes.getValue(EnvironmentAttributes.SKY_COLOR, eye)),
            attributes.getValue(EnvironmentAttributes.MOON_PHASE, eye).ordinal(), world.getRainLevel(1.0F), world.getThunderLevel(1.0F));
        ClientViewEnvironment.Fog fog = new ClientViewEnvironment.Fog(color(attributes.getValue(EnvironmentAttributes.FOG_COLOR, eye)),
            attributes.getValue(EnvironmentAttributes.FOG_START_DISTANCE, eye), attributes.getValue(EnvironmentAttributes.FOG_END_DISTANCE, eye),
            attributes.getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, eye), attributes.getValue(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE, eye),
            color(attributes.getValue(EnvironmentAttributes.WATER_FOG_COLOR, eye)), attributes.getValue(EnvironmentAttributes.WATER_FOG_START_DISTANCE, eye),
            attributes.getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, eye));
        ClientViewEnvironment.Lighting lighting = new ClientViewEnvironment.Lighting(color(attributes.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, eye)),
            attributes.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, eye), color(attributes.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, eye)),
            color(attributes.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, eye)));
        ClientViewEnvironment.Clouds clouds = new ClientViewEnvironment.Clouds(color(attributes.getValue(EnvironmentAttributes.CLOUD_COLOR, eye)),
            attributes.getValue(EnvironmentAttributes.CLOUD_HEIGHT, eye));
        return new ClientViewEnvironment(world.getGameTime(), sky, fog, lighting, clouds, transform,
            new ClientViewEnvironment.Dimension(dimension.minY(), dimension.height(), dimension.hasSkyLight(),
                ClientViewEnvironment.CardinalLighting.valueOf(dimension.cardinalLightType().name()),
                world.isFlat() ? dimension.minY() : 63.0D, dimension.hasEndFlashes()));
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static ClientViewEnvironment.Color color(Vector3fc value) {
        return new ClientViewEnvironment.Color(value.x(), value.y(), value.z());
    }

    private static ClientViewEnvironment.ColorAlpha color(Vector4fc value) {
        return new ClientViewEnvironment.ColorAlpha(value.x(), value.y(), value.z(), value.w());
    }
}
