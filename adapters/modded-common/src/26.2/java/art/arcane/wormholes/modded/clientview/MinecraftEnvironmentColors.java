package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.stream.EnvironmentState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.util.ARGB;

public final class MinecraftEnvironmentColors {
    private MinecraftEnvironmentColors() {
    }

    public static EnvironmentState.Color color(int value) {
        return new EnvironmentState.Color(ARGB.redFloat(value), ARGB.greenFloat(value), ARGB.blueFloat(value));
    }

    public static EnvironmentState.ColorAlpha alpha(int value) {
        return new EnvironmentState.ColorAlpha(ARGB.redFloat(value), ARGB.greenFloat(value), ARGB.blueFloat(value), ARGB.alphaFloat(value));
    }

    public static float fluidHeight(FluidState fluid, Level world, BlockPos position) {
        return fluid.getHeight(world, position);
    }
}
