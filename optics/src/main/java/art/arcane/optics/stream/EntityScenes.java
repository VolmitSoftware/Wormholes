package art.arcane.optics.stream;

import java.util.List;
import java.util.UUID;

import art.arcane.optics.entity.EntitySnapshot;

public interface EntityScenes<O> {
    Object sceneKey(O observer, UUID portal);

    List<EntitySnapshot> capture(O observer, UUID portal, long tick);

    default UUID projectedId(UUID sourceId) {
        return sourceId;
    }

    default boolean visible(O observer, EntitySnapshot visual) {
        return true;
    }

    default boolean isObserver(O observer, EntitySnapshot visual) {
        return false;
    }
}
