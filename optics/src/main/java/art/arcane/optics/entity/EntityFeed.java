package art.arcane.optics.entity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import art.arcane.optics.math.Vec3d;

public interface EntityFeed<O, W, V, E> {
    List<EntitySnapshot> entities(V view, SnapshotProjector.EntityRange range);

    Collection<E> localEntities(W world, Vec3d center, int range);

    boolean visible(O observer, V view, UUID entityId);

    EntityProfile profile(V view, UUID entityId);

    int stateVersion(V view, UUID entityId);

    boolean hasMap(V view, UUID entityId);

    boolean valid(E entity);
}
