package art.arcane.optics.entity;

import java.util.UUID;

public record EntityAnimation(UUID entityId, boolean hurt, int animation, float yaw) {
    public static EntityAnimation animation(UUID entityId, int animation) {
        return new EntityAnimation(entityId, false, animation, 0.0F);
    }

    public static EntityAnimation hurt(UUID entityId, float yaw) {
        return new EntityAnimation(entityId, true, 0, yaw);
    }
}
