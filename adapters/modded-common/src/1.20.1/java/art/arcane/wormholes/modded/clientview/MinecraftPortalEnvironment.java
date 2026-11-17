package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

public final class MinecraftPortalEnvironment {
    private static final float TWO_PI = (float) (Math.PI * 2.0D);
    private static final int BLOCK_LIGHT_TINT = 0xFFFFD88C;
    private static final float NIGHT_SKY_LIGHT_FACTOR = 0.24F;

    private MinecraftPortalEnvironment() {
    }

    public static EnvironmentState capture(Level world, Vec3d destinationEye, OpticTransform transform, boolean flat) {
        Vec3 eye = new Vec3(destinationEye.x(), destinationEye.y(), destinationEye.z());
        BlockPos eyeBlock = BlockPos.containing(eye);
        DimensionType dimension = world.dimensionType();
        Biome biome = world.getBiome(eyeBlock).value();
        ResourceLocation effects = dimension.effectsLocation();
        EnvironmentState.Skybox skybox = effects.equals(BuiltinDimensionTypes.OVERWORLD_EFFECTS) ? EnvironmentState.Skybox.OVERWORLD
            : effects.equals(BuiltinDimensionTypes.END_EFFECTS) ? EnvironmentState.Skybox.END : EnvironmentState.Skybox.NONE;
        boolean nether = effects.equals(BuiltinDimensionTypes.NETHER_EFFECTS);
        float timeOfDay = world.getTimeOfDay(1.0F);
        float cycle = Mth.cos(timeOfDay * TWO_PI);
        float daylight = Mth.clamp(cycle * 2.0F + 0.5F, 0.0F, 1.0F);
        float angle = timeOfDay * 360.0F;
        EnvironmentState.Sky sky = new EnvironmentState.Sky(skybox, radians(angle), radians(angle + 180.0F), radians(angle),
            skybox == EnvironmentState.Skybox.OVERWORLD ? starBrightness(cycle) : 0.0F,
            skybox == EnvironmentState.Skybox.OVERWORLD ? sunrise(cycle) : new EnvironmentState.ColorAlpha(0.0F, 0.0F, 0.0F, 0.0F),
            scaled(biome.getSkyColor(), skybox == EnvironmentState.Skybox.OVERWORLD ? daylight : 1.0F), world.getMoonPhase(),
            world.getRainLevel(1.0F), world.getThunderLevel(1.0F));
        float fogScale = skybox == EnvironmentState.Skybox.OVERWORLD ? daylight * 0.94F + 0.06F : 1.0F;
        EnvironmentState.Fog fog = new EnvironmentState.Fog(scaled(biome.getFogColor(), fogScale),
            nether ? 10.0F : 0.0F, nether ? 96.0F : 1024.0F, 512.0F, 2048.0F,
            scaled(biome.getWaterFogColor(), 1.0F), -8.0F, 96.0F);
        float skyFactor = dimension.hasSkyLight() ? NIGHT_SKY_LIGHT_FACTOR + (1.0F - NIGHT_SKY_LIGHT_FACTOR) * daylight : 1.0F;
        EnvironmentState.Lighting lighting = new EnvironmentState.Lighting(scaled(BLOCK_LIGHT_TINT, 1.0F), skyFactor,
            new EnvironmentState.Color(1.0F, 1.0F, 1.0F), new EnvironmentState.Color(0.0F, 0.0F, 0.0F));
        EnvironmentState.Clouds clouds = new EnvironmentState.Clouds(new EnvironmentState.ColorAlpha(1.0F, 1.0F, 1.0F,
            skybox == EnvironmentState.Skybox.OVERWORLD ? 0.8F : 0.0F), 192.0F);
        return new EnvironmentState(world.getGameTime(), sky, fog, lighting, clouds, transform,
            new EnvironmentState.Dimension(dimension.minY(), dimension.height(), dimension.hasSkyLight(),
                nether ? EnvironmentState.CardinalLighting.NETHER : EnvironmentState.CardinalLighting.DEFAULT,
                flat ? dimension.minY() : 63.0D, false),
            new EnvironmentState.World(world.dimension().location().toString(), world.getDayTime(),
                world.getBiome(eyeBlock).unwrapKey().orElseThrow().location().toString(), world.getSeaLevel(),
                world.getBrightness(LightLayer.BLOCK, eyeBlock), world.getBrightness(LightLayer.SKY, eyeBlock), dimension.logicalHeight(), dimension.hasCeiling(), dimension.ambientLight(),
                eyeMedium(world, eye, eyeBlock), dimension.hasFixedTime()), 1.0F);
    }

    private static EnvironmentState.EyeMedium eyeMedium(Level world, Vec3 eye, BlockPos position) {
        FluidState fluid = world.getFluidState(position);
        if (fluid.is(FluidTags.WATER) && eye.y < position.getY() + MinecraftEnvironmentColors.fluidHeight(fluid, world, position)) {
            return EnvironmentState.EyeMedium.WATER;
        }
        if (fluid.is(FluidTags.LAVA) && eye.y < position.getY() + fluid.getHeight(world, position)) {
            return EnvironmentState.EyeMedium.LAVA;
        }
        return world.getBlockState(position).is(Blocks.POWDER_SNOW)
            ? EnvironmentState.EyeMedium.POWDER_SNOW : EnvironmentState.EyeMedium.NONE;
    }

    private static float starBrightness(float cycle) {
        float value = Mth.clamp(1.0F - (cycle * 2.0F + 0.25F), 0.0F, 1.0F);
        return value * value * 0.5F;
    }

    private static EnvironmentState.ColorAlpha sunrise(float cycle) {
        if (cycle < -0.4F || cycle > 0.4F) {
            return new EnvironmentState.ColorAlpha(0.0F, 0.0F, 0.0F, 0.0F);
        }
        float ramp = cycle / 0.4F * 0.5F + 0.5F;
        float alpha = 1.0F - (1.0F - Mth.sin(ramp * (float) Math.PI)) * 0.99F;
        return new EnvironmentState.ColorAlpha(ramp * 0.3F + 0.7F, ramp * ramp * 0.7F + 0.2F, 0.2F, alpha * alpha);
    }

    private static EnvironmentState.Color scaled(int rgb, float scale) {
        return new EnvironmentState.Color((rgb >> 16 & 0xFF) / 255.0F * scale, (rgb >> 8 & 0xFF) / 255.0F * scale, (rgb & 0xFF) / 255.0F * scale);
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
