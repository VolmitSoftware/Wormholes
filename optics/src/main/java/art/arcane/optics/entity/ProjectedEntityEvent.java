package art.arcane.optics.entity;

import java.util.UUID;

public record ProjectedEntityEvent(UUID entityId, boolean hurt, int animation, float yaw) {
    public static ProjectedEntityEvent animation(UUID entityId, int animation) {
        return new ProjectedEntityEvent(entityId, false, animation, 0.0F);
    }

    public static ProjectedEntityEvent hurt(UUID entityId, float yaw) {
        return new ProjectedEntityEvent(entityId, true, 0, yaw);
    }
}
