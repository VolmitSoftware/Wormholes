package art.arcane.wormholes.modded.client;

import art.arcane.optics.entity.EntitySnapshot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

final class ClientItemMotion {
    private static final double TELEPORT_DISTANCE_SQUARED = 64.0D;
    private static final int INTERPOLATION_TICKS = 2;

    private final ItemEntity entity;
    private Vec3 target;
    private float targetYaw;
    private float targetPitch;
    private int steps;

    ClientItemMotion(ItemEntity entity) {
        this.entity = entity;
    }

    void move(EntitySnapshot visual, EntitySnapshot previous) {
        Vec3 position = new Vec3(visual.x(), visual.y(), visual.z());
        double dx = visual.x() - previous.x();
        double dy = visual.y() - previous.y();
        double dz = visual.z() - previous.z();
        if (dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE_SQUARED) {
            steps = 0;
            entity.setPos(position);
            entity.setYRot(visual.yaw());
            entity.setXRot(visual.pitch());
            entity.setOldPosAndRot();
        } else if (dx != 0 || dy != 0 || dz != 0 || visual.yaw() != previous.yaw() || visual.pitch() != previous.pitch()) {
            target = position;
            targetYaw = visual.yaw();
            targetPitch = visual.pitch();
            steps = INTERPOLATION_TICKS;
        }
        entity.setDeltaMovement(new Vec3(visual.velocityX(), visual.velocityY(), visual.velocityZ()));
        entity.setOnGround(visual.onGround());
    }

    void tick() {
        entity.baseTick();
        if (steps <= 0 || target == null) {
            return;
        }
        double fraction = 1.0D / steps;
        Vec3 current = entity.position();
        entity.setPos(current.add(target.subtract(current).scale(fraction)));
        entity.setYRot(entity.getYRot() + (float) ((targetYaw - entity.getYRot()) * fraction));
        entity.setXRot(entity.getXRot() + (float) ((targetPitch - entity.getXRot()) * fraction));
        steps--;
    }
}
