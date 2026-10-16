package art.arcane.wormholes.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DimensionalPortalKindTest {
    @Test
    void twoWayEndPortalsTravelBothWaysWithBothViewsShown() {
        assertFalse(DimensionalPortalKind.END_ARRIVAL.isReceiverOnly(true));
        assertEquals(PortalTravelMode.BOTH, DimensionalPortalKind.END_SOURCE.managedTravel(true));
        assertEquals(PortalTravelMode.BOTH, DimensionalPortalKind.END_ARRIVAL.managedTravel(true));
        assertEquals(ProjectionMode.ON, DimensionalPortalKind.END_SOURCE.managedProjection(true));
        assertEquals(ProjectionMode.ON, DimensionalPortalKind.END_ARRIVAL.managedProjection(true));
    }

    @Test
    void oneWayEndPortalsOnlySendFromTheSourceAndHideTheArrivalView() {
        assertTrue(DimensionalPortalKind.END_ARRIVAL.isReceiverOnly(false));
        assertEquals(PortalTravelMode.OUTBOUND, DimensionalPortalKind.END_SOURCE.managedTravel(false));
        assertEquals(PortalTravelMode.INBOUND, DimensionalPortalKind.END_ARRIVAL.managedTravel(false));
        assertEquals(ProjectionMode.ON, DimensionalPortalKind.END_SOURCE.managedProjection(false));
        assertEquals(ProjectionMode.OFF, DimensionalPortalKind.END_ARRIVAL.managedProjection(false));
    }

    @Test
    void theEndExitAndNetherPortalsIgnoreTheEndTravelSetting() {
        for (boolean twoWay : new boolean[] {true, false}) {
            assertEquals(PortalTravelMode.LOCKED, DimensionalPortalKind.END_EXIT.managedTravel(twoWay));
            assertEquals(ProjectionMode.ON, DimensionalPortalKind.END_EXIT.managedProjection(twoWay));
            assertEquals(PortalTravelMode.BOTH, DimensionalPortalKind.NETHER.managedTravel(twoWay));
            assertEquals(PortalTravelMode.BOTH, DimensionalPortalKind.SHAPED_NETHER.managedTravel(twoWay));
            assertNull(DimensionalPortalKind.NETHER.managedProjection(twoWay));
            assertNull(DimensionalPortalKind.NONE.managedProjection(twoWay));
            for (DimensionalPortalKind kind : DimensionalPortalKind.values()) {
                if (kind != DimensionalPortalKind.END_ARRIVAL) {
                    assertFalse(kind.isReceiverOnly(twoWay));
                }
            }
        }
    }
}
