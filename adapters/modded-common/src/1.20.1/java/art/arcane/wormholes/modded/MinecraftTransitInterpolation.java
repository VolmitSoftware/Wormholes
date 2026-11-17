package art.arcane.wormholes.modded;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Server entities on 1.20.1 hold no movement interpolation; clients lerp on their own, so there is nothing to shift
 * or clear here.
 */
public final class MinecraftTransitInterpolation {
    private MinecraftTransitInterpolation() {
    }

    public static void translate(Entity entity, Vec3 delta) {
    }

    public static void clear(Entity entity) {
    }
}
