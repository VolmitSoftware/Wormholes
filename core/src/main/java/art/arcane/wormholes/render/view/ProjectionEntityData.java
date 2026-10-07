package art.arcane.wormholes.render.view;

import art.arcane.optics.entity.EntityProfile;
import art.arcane.optics.entity.EntitySnapshot;

import java.util.List;
import java.util.UUID;

public interface ProjectionEntityData<M, E> {
    List<EntitySnapshot> getEntities(double centerX, double centerY, double centerZ, double range);
    EntityProfile getProfile(UUID entityId);
    List<M> getMetadata(UUID entityId);
    List<E> getEquipment(UUID entityId);
    int getStateVersion(UUID entityId);
}
