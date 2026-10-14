package art.arcane.wormholes.modded.seamless;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RemoteRoutesHandOverTest {
    @Test
    public void aColumnTheRouteNeverDeliveredGoesThroughTheVanillaSender() {
        assertEquals(RemoteRoutes.HandOverColumn.SEND, RemoteRoutes.handOverColumn(false, false, true));
        assertEquals(RemoteRoutes.HandOverColumn.NONE, RemoteRoutes.handOverColumn(false, false, false));
    }

    @Test
    public void aDeliveredCurrentColumnStaysOnTheClient() {
        assertEquals(RemoteRoutes.HandOverColumn.NONE, RemoteRoutes.handOverColumn(true, true, true));
        assertEquals(RemoteRoutes.HandOverColumn.HOLD, RemoteRoutes.handOverColumn(true, true, false));
    }

    @Test
    public void aDeliveredColumnWaitingForAResendIsRefreshedInPlace() {
        assertEquals(RemoteRoutes.HandOverColumn.REFRESH, RemoteRoutes.handOverColumn(true, false, true));
        assertEquals(RemoteRoutes.HandOverColumn.HOLD_STALE, RemoteRoutes.handOverColumn(true, false, false));
    }
}
