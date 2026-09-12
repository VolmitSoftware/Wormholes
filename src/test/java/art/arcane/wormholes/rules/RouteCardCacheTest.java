package art.arcane.wormholes.rules;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouteCardCacheTest {
    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID PORTAL = UUID.randomUUID();

    @AfterEach
    void clear() {
        RouteCardCache.clear();
    }

    @Test
    void aCardIsReadBackOnlyForItsOwnPortalAndOnlyWhileItIsFresh() {
        RouteCardCache.publish(VIEWER, new RouteCardCache.Entry(PORTAL, "12.50", "", 1_000L));

        assertEquals("12.50", RouteCardCache.get(VIEWER, PORTAL, 1_000L).price());
        assertEquals("12.50", RouteCardCache.get(VIEWER, PORTAL, 1_000L + RouteCardCache.TTL_MILLIS).price());
        assertNull(RouteCardCache.get(VIEWER, PORTAL, 1_001L + RouteCardCache.TTL_MILLIS));
        assertNull(RouteCardCache.get(VIEWER, UUID.randomUUID(), 1_000L));
        assertNull(RouteCardCache.get(UUID.randomUUID(), PORTAL, 1_000L));
    }

    @Test
    void forgettingAViewerDropsTheirCard() {
        RouteCardCache.publish(VIEWER, new RouteCardCache.Entry(PORTAL, "12.50", "refused", 1_000L));
        assertEquals("refused", RouteCardCache.get(VIEWER, PORTAL, 1_000L).refusal());

        RouteCardCache.forget(VIEWER);

        assertNull(RouteCardCache.get(VIEWER, PORTAL, 1_000L));
    }
}
