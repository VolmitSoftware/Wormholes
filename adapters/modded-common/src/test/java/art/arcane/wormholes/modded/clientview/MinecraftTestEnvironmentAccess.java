package art.arcane.wormholes.modded.clientview;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class MinecraftTestEnvironmentAccess {
    private MinecraftTestEnvironmentAccess() {
    }

    public static void sky(EnvironmentAttributeSystem attributes, Vec3 point) {
        when(attributes.getValue(eq(EnvironmentAttributes.SKY_COLOR), eq(point))).thenReturn(new Vector3f(1.25F, 0.4F, 0.8F));
    }

    public static float skyRed() {
        return 1.25F;
    }

    public static void water(FluidState fluid, ServerLevel world) {
        when(fluid.getHeightForCamera(eq(world), any(BlockPos.class))).thenReturn(0.25F);
        when(fluid.getHeight(eq(world), any(BlockPos.class))).thenReturn(0.875F);
    }

    public static void verifyWater(FluidState fluid, ServerLevel world) {
        verify(fluid, times(3)).getHeightForCamera(world, new BlockPos(3, 64, -5));
    }
}
