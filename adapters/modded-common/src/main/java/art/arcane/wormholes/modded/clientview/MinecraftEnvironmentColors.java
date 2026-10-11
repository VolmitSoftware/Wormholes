package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.stream.EnvironmentState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3fc;
import org.joml.Vector4fc;

public final class MinecraftEnvironmentColors {
    private MinecraftEnvironmentColors() {
    }

    public static EnvironmentState.Color color(Vector3fc value) {
        return new EnvironmentState.Color(value.x(), value.y(), value.z());
    }

    public static EnvironmentState.ColorAlpha alpha(Vector4fc value) {
        return new EnvironmentState.ColorAlpha(value.x(), value.y(), value.z(), value.w());
    }

    public static float fluidHeight(FluidState fluid, Level world, BlockPos position) {
        return fluid.getHeightForCamera(world, position);
    }
}
