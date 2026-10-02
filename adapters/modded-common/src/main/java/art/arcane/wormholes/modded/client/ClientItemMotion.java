package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.view.EntityVisual;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

final class ClientItemMotion {
    private static final double TELEPORT_DISTANCE_SQUARED = 64.0D;
    private static final int INTERPOLATION_TICKS = 2;

    private final ItemEntity entity;
    private final InterpolationHandler interpolation;

    ClientItemMotion(ItemEntity entity) {
        this.entity = entity;
        this.interpolation = LinearInterpolationHandler.create(entity, INTERPOLATION_TICKS);
    }

    void move(EntityVisual visual, EntityVisual previous) {
        Vec3 position = new Vec3(visual.x(), visual.y(), visual.z());
        double dx = visual.x() - previous.x();
        double dy = visual.y() - previous.y();
        double dz = visual.z() - previous.z();
        if (dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE_SQUARED) {
            interpolation.cancel();
            entity.setPos(position);
            entity.setYRot(visual.yaw());
            entity.setXRot(visual.pitch());
            entity.setOldPosAndRot();
        } else if (dx != 0 || dy != 0 || dz != 0 || visual.yaw() != previous.yaw() || visual.pitch() != previous.pitch()) {
            interpolation.interpolateTo(PositionPath.of(position), visual.yaw(), visual.pitch(), true);
        }
        entity.setDeltaMovement(new Vec3(visual.velocityX(), visual.velocityY(), visual.velocityZ()));
        entity.setOnGround(visual.onGround());
    }

    void tick() {
        entity.commonTick();
        interpolation.interpolate();
    }
}
