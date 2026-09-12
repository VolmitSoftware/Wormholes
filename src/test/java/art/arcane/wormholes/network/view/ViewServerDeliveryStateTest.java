package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewServerDeliveryStateTest {
    @Test
    void timeDeliveryRequiresAnAcceptedInitialValueAndCoalescesToTheLatestValue() {
        ViewServer.TimeDeliveryState state = new ViewServer.TimeDeliveryState(3);

        assertFalse(state.hasAcceptedInitial());
        assertTrue(state.needsDelivery());
        assertTrue(state.tryStartDelivery());
        assertFalse(state.tryStartDelivery());

        state.markAccepted(3);
        state.finishDelivery();
        assertTrue(state.hasAcceptedInitial());
        assertFalse(state.needsDelivery());

        state.updateDesired(5);
        state.updateDesired(7);
        assertEquals(7, state.desiredSkyDarken());
        assertTrue(state.needsDelivery());

        state.markAccepted(7);
        assertFalse(state.needsDelivery());
    }

    @Test
    void weatherRidesBesideTimeAndOnlyResendsWhenItChanges() {
        ViewServer.TimeDeliveryState state = new ViewServer.TimeDeliveryState(3);
        state.markAccepted(3);
        assertFalse(state.needsWeatherDelivery(), "clear weather is the implied initial state");

        state.updateDesired(3, true, false);
        assertTrue(state.needsWeatherDelivery());
        assertFalse(state.needsDelivery(), "an unchanged sky darken needs no time frame");
        state.markWeatherAccepted(true, false);
        assertFalse(state.needsWeatherDelivery());

        state.updateDesired(3, true, true);
        assertTrue(state.needsWeatherDelivery());
        state.updateDesired(3, false, false);
        state.markWeatherAccepted(false, false);
        assertFalse(state.needsWeatherDelivery());
        assertFalse(state.desiredStorm());
    }

    @Test
    void entityCaptureTokenCanOnlyCompleteItsGenerationOnce() {
        ViewServer.EntityCaptureToken token = new ViewServer.EntityCaptureToken(12L, System.nanoTime() + 1_000_000_000L);

        assertEquals(12L, token.generation());
        assertTrue(token.isActive());
        assertFalse(token.isExpired());
        assertTrue(token.tryCompleteBeforeDeadline());
        assertFalse(token.isActive());
        assertFalse(token.tryComplete());
    }

    @Test
    void expiredEntityCaptureCannotWinTheSuccessCommit() {
        ViewServer.EntityCaptureToken token = new ViewServer.EntityCaptureToken(13L, System.nanoTime() - 1L);

        assertTrue(token.isExpired());
        assertFalse(token.tryCompleteBeforeDeadline());
        assertTrue(token.isActive());
        assertTrue(token.tryComplete());
    }
}
