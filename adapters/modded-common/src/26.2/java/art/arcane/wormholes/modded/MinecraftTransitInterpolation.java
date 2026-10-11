package art.arcane.wormholes.modded;

import art.arcane.wormholes.modded.seamless.CrossingInterpolation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.phys.Vec3;

public final class MinecraftTransitInterpolation {
    private MinecraftTransitInterpolation() {
    }

    public static void translate(Entity entity, Vec3 delta) {
        InterpolationHandler interpolation = entity.getInterpolation();
        if (interpolation != null) {
            ((CrossingInterpolation) interpolation).wormholesTranslate(delta);
        }
    }

    public static void clear(Entity entity) {
        InterpolationHandler interpolation = entity.getInterpolation();
        if (interpolation != null) {
            interpolation.cancel();
        }
    }
}
