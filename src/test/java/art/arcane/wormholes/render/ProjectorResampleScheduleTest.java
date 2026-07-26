package art.arcane.wormholes.render;

import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.view.RemoteWorldView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectorResampleScheduleTest {
    @Test
    void unchangedRemoteRevisionDoesNotTriggerPeriodicResamples() {
        ILocalPortal portal = proxy(ILocalPortal.class);
        ProjectorResampleSchedule schedule = new ProjectorResampleSchedule(portal);
        RemoteViewCache cache = new RemoteViewCache();
        RemoteWorldView view = new RemoteWorldView(cache.getOrCreate("peer", UUID.randomUUID()), null);

        assertTrue(schedule.stableResample(false, view, null, 0.0D, 0.0D));
        schedule.noteSourceViewRevision(view.getRevision());

        for (int pass = 0; pass < 2_000; pass++) {
            schedule.beginBlockPass();
            assertFalse(schedule.stableResample(true, view, null, 0.0D, 0.0D));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (instance, method, arguments) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> type.getSimpleName() + "Proxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
