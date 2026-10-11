package art.arcane.wormholes;

import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.integration.ReloadPreparation;
import art.arcane.wormholes.integration.fawe.FaweProjectionIntegration;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class WormholesFaweReloadTest {
    @Test
    void reloadWithoutFaweIsImmediatelyReady() throws ReflectiveOperationException {
        Wormholes plugin = plugin(null);
        assertTrue(plugin.prepareReload(ReloadAware.PreUnloadReason.HOT_RELOAD).toCompletableFuture().join().ready());
    }

    @Test
    void reloadWaitsForAdmittedEditsBeforeAllowingUnload() throws ReflectiveOperationException {
        FaweProjectionIntegration integration = mock(FaweProjectionIntegration.class);
        CompletableFuture<Void> edits = new CompletableFuture<>();
        when(integration.prepareReload(Duration.ofSeconds(90))).thenReturn(edits);
        CompletionStage<ReloadPreparation> prepared = plugin(integration).prepareReload(ReloadAware.PreUnloadReason.HOT_RELOAD);
        assertFalse(prepared.toCompletableFuture().isDone());
        edits.complete(null);
        assertTrue(prepared.toCompletableFuture().join().ready());
    }

    @Test
    void editTimeoutRefusesUnload() throws ReflectiveOperationException {
        FaweProjectionIntegration integration = mock(FaweProjectionIntegration.class);
        when(integration.prepareReload(Duration.ofSeconds(90)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("edit still active")));
        ReloadPreparation prepared = plugin(integration).prepareReload(ReloadAware.PreUnloadReason.HOT_UNLOAD)
                .toCompletableFuture().join();
        assertFalse(prepared.ready());
        assertFalse(prepared.reason().isBlank());
    }

    @Test
    void cancelledReloadReopensAdmission() throws ReflectiveOperationException {
        FaweProjectionIntegration integration = mock(FaweProjectionIntegration.class);
        plugin(integration).cancelReload().toCompletableFuture().join();
        verify(integration).cancelReload();
    }

    private static Wormholes plugin(FaweProjectionIntegration integration) throws ReflectiveOperationException {
        Wormholes plugin = mock(Wormholes.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        doCallRealMethod().when(plugin).prepareReload(ReloadAware.PreUnloadReason.HOT_RELOAD);
        doCallRealMethod().when(plugin).prepareReload(ReloadAware.PreUnloadReason.HOT_UNLOAD);
        doCallRealMethod().when(plugin).cancelReload();
        Field field = Wormholes.class.getDeclaredField("faweProjectionIntegration");
        field.setAccessible(true);
        field.set(plugin, integration);
        return plugin;
    }
}
