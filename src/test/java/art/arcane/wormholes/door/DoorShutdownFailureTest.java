package art.arcane.wormholes.door;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.door.view.DoorProjectionRegistry;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class DoorShutdownFailureTest {
    @Test
    void recipeAndProjectionFailuresDoNotPreventRuntimeAndTransitRelease() throws ReflectiveOperationException {
        Wormholes plugin = mock(Wormholes.class);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        DimensionalDoorManager manager = mock(DimensionalDoorManager.class, CALLS_REAL_METHODS);
        DoorStateGuard guard = new DoorStateGuard();
        DoorItemService items = mock(DoorItemService.class);
        DoorProjectionRegistry projections = mock(DoorProjectionRegistry.class);
        DoorRuntimeIndex runtimes = mock(DoorRuntimeIndex.class);
        DoorTransitLedger ledger = mock(DoorTransitLedger.class);
        PocketSpaceIndex pockets = mock(PocketSpaceIndex.class);
        DoorAccessFeedback feedback = mock(DoorAccessFeedback.class);
        IllegalStateException recipeFailure = new IllegalStateException("recipe failure");
        IllegalStateException projectionFailure = new IllegalStateException("projection failure");
        doThrow(recipeFailure).when(items).unregisterRecipes();
        doThrow(projectionFailure).when(projections).close();
        set(manager, "plugin", plugin);
        set(manager, "guard", guard);
        set(manager, "items", items);
        set(manager, "projectionRegistry", projections);
        set(manager, "runtimes", runtimes);
        set(manager, "ledger", ledger);
        set(manager, "pockets", pockets);
        set(manager, "accessFeedback", feedback);
        try (MockedStatic<HandlerList> handlers = mockStatic(HandlerList.class)) {
            manager.close();
            manager.close();
        }
        assertTrue(guard.closed());
        verify(runtimes, times(1)).close();
        verify(ledger, times(1)).clear();
        verify(pockets, times(1)).clear();
        verify(feedback, times(1)).clear();
        verify(logger).log(Level.WARNING, "Could not close door recipes", recipeFailure);
        verify(logger).log(Level.WARNING, "Could not close door projections", projectionFailure);
    }

    @Test
    void failedSweepAndVisualReleaseStillClearRuntimeIndexes() throws ReflectiveOperationException {
        Plugin plugin = mock(Plugin.class);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        DoorRuntimeIndex index = mock(DoorRuntimeIndex.class, CALLS_REAL_METHODS);
        DoorEntitySweep sweep = mock(DoorEntitySweep.class);
        DoorPortalVisualService visuals = mock(DoorPortalVisualService.class);
        DoorAutoCloseBook autoClose = mock(DoorAutoCloseBook.class);
        DoorSpatialIndex<RuntimeDoor> spatial = new DoorSpatialIndex<RuntimeDoor>();
        ConcurrentHashMap<UUID, RuntimeDoor> runtimes = new ConcurrentHashMap<UUID, RuntimeDoor>();
        runtimes.put(UUID.randomUUID(), mock(RuntimeDoor.class));
        IllegalStateException sweepFailure = new IllegalStateException("sweep failure");
        IllegalStateException visualFailure = new IllegalStateException("visual failure");
        doThrow(sweepFailure).when(sweep).close();
        doThrow(visualFailure).when(visuals).close();
        set(index, "plugin", plugin);
        set(index, "sweep", sweep);
        set(index, "visuals", visuals);
        set(index, "autoClose", autoClose);
        set(index, "spatialIndex", spatial);
        set(index, "runtimes", runtimes);
        index.close();
        assertTrue(runtimes.isEmpty());
        verify(autoClose).clear();
        verify(logger).log(Level.WARNING, "Could not close door entity sweep", sweepFailure);
        verify(logger).log(Level.WARNING, "Could not close door visuals", visualFailure);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
