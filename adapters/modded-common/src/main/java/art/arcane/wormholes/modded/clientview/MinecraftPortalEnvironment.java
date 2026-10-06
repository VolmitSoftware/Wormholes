package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.frame.OpticTransform;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.joml.Vector4fc;

public final class MinecraftPortalEnvironment {
    private MinecraftPortalEnvironment() {
    }

    public static ProjectionEnvironment capture(Level world, Vec3d destinationEye, OpticTransform transform, boolean flat) {
        Vec3 eye = new Vec3(destinationEye.x(), destinationEye.y(), destinationEye.z());
        BlockPos eyeBlock = BlockPos.containing(eye);
        EnvironmentAttributeReader attributes = world.environmentAttributes();
        DimensionType dimension = world.dimensionType();
        ProjectionEnvironment.Sky sky = new ProjectionEnvironment.Sky(ProjectionEnvironment.Skybox.valueOf(dimension.skybox().name()),
            radians(attributes.getValue(EnvironmentAttributes.SUN_ANGLE, eye)), radians(attributes.getValue(EnvironmentAttributes.MOON_ANGLE, eye)),
            radians(attributes.getValue(EnvironmentAttributes.STAR_ANGLE, eye)), attributes.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, eye),
            color(attributes.getValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, eye)), color(attributes.getValue(EnvironmentAttributes.SKY_COLOR, eye)),
            attributes.getValue(EnvironmentAttributes.MOON_PHASE, eye).ordinal(), world.getRainLevel(1.0F), world.getThunderLevel(1.0F));
        ProjectionEnvironment.Fog fog = new ProjectionEnvironment.Fog(color(attributes.getValue(EnvironmentAttributes.FOG_COLOR, eye)),
            attributes.getValue(EnvironmentAttributes.FOG_START_DISTANCE, eye), attributes.getValue(EnvironmentAttributes.FOG_END_DISTANCE, eye),
            attributes.getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, eye), attributes.getValue(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE, eye),
            color(attributes.getValue(EnvironmentAttributes.WATER_FOG_COLOR, eye)), attributes.getValue(EnvironmentAttributes.WATER_FOG_START_DISTANCE, eye),
            attributes.getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, eye));
        ProjectionEnvironment.Lighting lighting = new ProjectionEnvironment.Lighting(color(attributes.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, eye)),
            attributes.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, eye), color(attributes.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, eye)),
            color(attributes.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, eye)));
        ProjectionEnvironment.Clouds clouds = new ProjectionEnvironment.Clouds(color(attributes.getValue(EnvironmentAttributes.CLOUD_COLOR, eye)),
            attributes.getValue(EnvironmentAttributes.CLOUD_HEIGHT, eye));
        return new ProjectionEnvironment(world.getGameTime(), sky, fog, lighting, clouds, transform,
            new ProjectionEnvironment.Dimension(dimension.minY(), dimension.height(), dimension.hasSkyLight(),
                ProjectionEnvironment.CardinalLighting.valueOf(dimension.cardinalLightType().name()),
                flat ? dimension.minY() : 63.0D, dimension.hasEndFlashes()),
            new ProjectionEnvironment.World(world.dimension().identifier().toString(), world.getDefaultClockTime(),
                world.getBiome(eyeBlock).unwrapKey().orElseThrow().identifier().toString(), world.getSeaLevel(),
                world.getBrightness(LightLayer.BLOCK, eyeBlock), world.getBrightness(LightLayer.SKY, eyeBlock), dimension.logicalHeight(), dimension.hasCeiling(), dimension.ambientLight(),
                eyeMedium(world, eye, eyeBlock), dimension.hasFixedTime()));
    }

    private static ProjectionEnvironment.EyeMedium eyeMedium(Level world, Vec3 eye, BlockPos position) {
        FluidState fluid = world.getFluidState(position);
        if (fluid.is(FluidTags.WATER) && eye.y < position.getY() + fluid.getHeightForCamera(world, position)) {
            return ProjectionEnvironment.EyeMedium.WATER;
        }
        if (fluid.is(FluidTags.LAVA) && eye.y < position.getY() + fluid.getHeight(world, position)) {
            return ProjectionEnvironment.EyeMedium.LAVA;
        }
        return world.getBlockState(position).is(Blocks.POWDER_SNOW)
            ? ProjectionEnvironment.EyeMedium.POWDER_SNOW : ProjectionEnvironment.EyeMedium.NONE;
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static ProjectionEnvironment.Color color(Vector3fc value) {
        return new ProjectionEnvironment.Color(value.x(), value.y(), value.z());
    }

    private static ProjectionEnvironment.ColorAlpha color(Vector4fc value) {
        return new ProjectionEnvironment.ColorAlpha(value.x(), value.y(), value.z(), value.w());
    }
}
