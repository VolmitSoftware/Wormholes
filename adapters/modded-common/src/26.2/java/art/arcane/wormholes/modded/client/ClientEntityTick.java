package art.arcane.wormholes.modded.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;

final class ClientEntityTick {
    private ClientEntityTick() {
    }

    static void interpolate(Entity entity) {
        begin(entity);
        InterpolationHandler interpolation = entity.getInterpolation();
        if (interpolation != null) {
            interpolation.interpolate();
        }
    }

    static void begin(Entity entity) {
        entity.setOldPosAndRot();
        entity.tickCount++;
    }
}
