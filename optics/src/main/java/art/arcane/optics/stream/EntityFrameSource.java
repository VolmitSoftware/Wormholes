package art.arcane.optics.stream;

import java.util.UUID;
import java.util.List;

import art.arcane.optics.entity.ProjectedEntityEvent;

@FunctionalInterface
public interface EntityFrameSource<O> {
    ViewStreamMessage.EntityFrame frame(O observer, EntityFrameTarget target, long tick);

    default UUID projectedId(UUID sourceId) {
        return sourceId;
    }

    default void event(ProjectedEntityEvent event) {
    }

    default List<ViewStreamMessage.EntityEvent> events(O observer, UUID portal, int portalKey) {
        return List.of();
    }

    static <O> EntityFrameSource<O> none() {
        return (observer, target, tick) -> null;
    }
}
