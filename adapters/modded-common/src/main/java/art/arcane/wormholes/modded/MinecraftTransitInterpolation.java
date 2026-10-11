package art.arcane.wormholes.modded;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class MinecraftTransitInterpolation {
    private MinecraftTransitInterpolation() {
    }

    public static void translate(Entity entity, Vec3 delta) {
        entity.getInterpolation().applyPredictedMovement(delta);
    }

    public static void clear(Entity entity) {
        entity.getInterpolation().interpolationTracker().clear();
    }
}
