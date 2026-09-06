package art.arcane.wormholes;

import art.arcane.wormholes.render.ProjectionChangeListener;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldUnloadListenerTest {
    @Test
    void lifecycleCleanupRunsAfterUnloadCancellationHasBeenDecided() throws ReflectiveOperationException {
        assertUnloadListener(PortalManager.class, "on");
        assertUnloadListener(ProjectionChangeListener.class, "on");
        assertUnloadListener(PocketWorldService.class, "onWorldUnload");
    }

    private static void assertUnloadListener(Class<?> listener, String method) throws ReflectiveOperationException {
        EventHandler handler = listener.getMethod(method, WorldUnloadEvent.class).getAnnotation(EventHandler.class);
        assertNotNull(handler, listener.getSimpleName());
        assertEquals(EventPriority.MONITOR, handler.priority(), listener.getSimpleName());
        assertTrue(handler.ignoreCancelled(), listener.getSimpleName());
    }
}
