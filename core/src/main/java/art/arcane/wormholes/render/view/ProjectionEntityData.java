package art.arcane.wormholes.render.view;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.RemoteViewCache.RemoteProfile;

import java.util.List;
import java.util.UUID;

public interface ProjectionEntityData<M, E> {
    List<EntityVisual> getEntities(double centerX, double centerY, double centerZ, double range);
    RemoteProfile getProfile(UUID entityId);
    List<M> getMetadata(UUID entityId);
    List<E> getEquipment(UUID entityId);
    int getStateVersion(UUID entityId);
}
