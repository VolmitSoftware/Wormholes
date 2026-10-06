package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.math.Face;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftPortalEnvironmentTest extends MinecraftTestBase {
    @Test
    @SuppressWarnings("unchecked")
    public void samplesDestinationAttributesAndDimensionInsteadOfClientWorld() {
        ServerLevel world = mock(ServerLevel.class);
        DimensionType dimension = mock(DimensionType.class);
        EnvironmentAttributeSystem attributes = mock(EnvironmentAttributeSystem.class);
        when(world.environmentAttributes()).thenReturn(attributes);
        when(world.getFluidState(any(BlockPos.class))).thenReturn(Fluids.EMPTY.defaultFluidState());
        when(world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        when(world.dimensionType()).thenReturn(dimension);
        when(world.getGameTime()).thenReturn(18000L);
        when(world.getDefaultClockTime()).thenReturn(72000L);
        when(world.dimension()).thenReturn(Level.END);
        Holder<Biome> biome = mock(Holder.Reference.class);
        when(world.getBiome(any(BlockPos.class))).thenReturn(biome);
        when(biome.unwrapKey()).thenReturn(Optional.of(Biomes.PLAINS));
        when(world.getSeaLevel()).thenReturn(63);
        when(world.getBrightness(eq(LightLayer.BLOCK), any(BlockPos.class))).thenReturn(7);
        when(world.getBrightness(eq(LightLayer.SKY), any(BlockPos.class))).thenReturn(15);
        when(world.getRainLevel(1.0F)).thenReturn(0.6F);
        when(dimension.skybox()).thenReturn(DimensionType.Skybox.END);
        when(dimension.cardinalLightType()).thenReturn(CardinalLighting.Type.NETHER);
        when(dimension.minY()).thenReturn(-96);
        when(dimension.height()).thenReturn(512);
        when(dimension.logicalHeight()).thenReturn(256);
        when(dimension.hasCeiling()).thenReturn(true);
        when(dimension.ambientLight()).thenReturn(0.1F);
        when(attributes.getValue(any(EnvironmentAttribute.class), any(Vec3.class))).thenAnswer(invocation -> {
            EnvironmentAttribute<?> attribute = invocation.getArgument(0);
            return attribute.defaultValue();
        });
        Vec3 point = new Vec3(128.5D, 92.0D, -32.25D);
        when(attributes.getValue(eq(EnvironmentAttributes.SKY_COLOR), eq(point))).thenReturn(new Vector3f(1.25F, 0.4F, 0.8F));
        when(attributes.getValue(eq(EnvironmentAttributes.SUN_ANGLE), eq(point))).thenReturn(90.0F);
        ProjectionEnvironment.Transform transform = new ProjectionEnvironment.Transform(Face.E, Face.U, Face.S,
            new art.arcane.optics.math.Vec3(-128, 0, 0));
        ProjectionEnvironment result = MinecraftPortalEnvironment.capture(world, new art.arcane.optics.math.Vec3(point.x, point.y, point.z), transform, world.isFlat());
        assertEquals(ProjectionEnvironment.Skybox.END, result.sky().skybox());
        assertEquals(1.25F, result.sky().color().red(), 0.0001F);
        assertEquals((float) (Math.PI / 2), result.sky().sunAngle(), 0.0001F);
        assertEquals(0.6F, result.sky().rain(), 0.0001F);
        assertEquals(-96, result.dimension().minY());
        assertEquals(ProjectionEnvironment.CardinalLighting.NETHER, result.dimension().cardinalLighting());
        assertFalse(result.dimension().hasSkyLight());
        assertEquals(18000L, result.gameTime());
        assertEquals(new ProjectionEnvironment.World("minecraft:the_end", 72000L, "minecraft:plains", 63, 7, 15, 256, true, 0.1F, ProjectionEnvironment.EyeMedium.NONE, false), result.world());
    }

    @Test
    public void eyeMediumUsesFluidSurfaceAndFixedTimeUsesTheActualDimensionFlag() {
        ServerLevel world = mediumWorld();
        FluidState fluid = mock(FluidState.class);
        when(world.getFluidState(any(BlockPos.class))).thenReturn(fluid);
        when(fluid.is(FluidTags.WATER)).thenReturn(true);
        when(fluid.getHeightForCamera(eq(world), any(BlockPos.class))).thenReturn(0.25F);
        when(fluid.getHeight(eq(world), any(BlockPos.class))).thenReturn(0.875F);
        assertEquals(ProjectionEnvironment.EyeMedium.WATER, sample(world, 64.249).world().eyeMedium());
        assertEquals(ProjectionEnvironment.EyeMedium.NONE, sample(world, 64.25).world().eyeMedium());
        assertEquals(ProjectionEnvironment.EyeMedium.NONE, sample(world, 64.75).world().eyeMedium());
        verify(fluid, times(3)).getHeightForCamera(world, new BlockPos(3, 64, -5));
        when(fluid.is(FluidTags.WATER)).thenReturn(false);
        when(fluid.is(FluidTags.LAVA)).thenReturn(true);
        assertEquals(ProjectionEnvironment.EyeMedium.LAVA, sample(world, 64.874).world().eyeMedium());
        assertEquals(ProjectionEnvironment.EyeMedium.NONE, sample(world, 64.875).world().eyeMedium());
        when(fluid.is(FluidTags.LAVA)).thenReturn(false);
        when(world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.POWDER_SNOW.defaultBlockState());
        when(world.dimensionType().hasFixedTime()).thenReturn(true);
        ProjectionEnvironment snow = sample(world, 64.5);
        assertEquals(ProjectionEnvironment.EyeMedium.POWDER_SNOW, snow.world().eyeMedium());
        assertTrue(snow.world().hasFixedTime());
        assertEquals("minecraft:overworld", snow.world().dimensionKey());
    }

    private static ProjectionEnvironment sample(ServerLevel world, double y) {
        return MinecraftPortalEnvironment.capture(world, new art.arcane.optics.math.Vec3(3.5, y, -4.5), ProjectionEnvironment.Transform.IDENTITY, world.isFlat());
    }

    @SuppressWarnings("unchecked")
    private static ServerLevel mediumWorld() {
        ServerLevel world = mock(ServerLevel.class);
        DimensionType dimension = mock(DimensionType.class);
        EnvironmentAttributeSystem attributes = mock(EnvironmentAttributeSystem.class);
        Holder<Biome> biome = mock(Holder.Reference.class);
        when(world.dimensionType()).thenReturn(dimension);
        when(world.environmentAttributes()).thenReturn(attributes);
        when(world.dimension()).thenReturn(Level.OVERWORLD);
        when(world.getBiome(any(BlockPos.class))).thenReturn(biome);
        when(biome.unwrapKey()).thenReturn(Optional.of(Biomes.PLAINS));
        when(world.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        when(dimension.skybox()).thenReturn(DimensionType.Skybox.OVERWORLD);
        when(dimension.cardinalLightType()).thenReturn(CardinalLighting.Type.DEFAULT);
        when(dimension.height()).thenReturn(384);
        when(attributes.getValue(any(EnvironmentAttribute.class), any(Vec3.class))).thenAnswer(call -> {
            EnvironmentAttribute<?> attribute = call.getArgument(0);
            return attribute.defaultValue();
        });
        return world;
    }
}
