package art.arcane.optics.stream;

import java.util.UUID;
import java.util.List;

import art.arcane.optics.entity.ProjectedEntityEvent;

@FunctionalInterface
public interface EntityFrameSource<P> {
    ViewStreamMessage.EntityFrame frame(P observer, UUID portal, int portalKey, long tick, boolean full, boolean hideObserver);

    default UUID projectedId(UUID sourceId) {
        return sourceId;
    }

    default void event(ProjectedEntityEvent event) {
    }

    default List<ViewStreamMessage.EntityEvent> events(P observer, UUID portal, int portalKey) {
        return List.of();
    }

    static <P> EntityFrameSource<P> none() {
        return (observer, portal, portalKey, tick, full, hideObserver) -> null;
    }
}
