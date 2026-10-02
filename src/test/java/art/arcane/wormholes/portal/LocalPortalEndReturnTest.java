package art.arcane.wormholes.portal;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalPortalEndReturnTest {
    @Test
    void fountainProjectionStaysOpenWithoutInstallingATraversalTunnel() {
        World world = LocalPortalTestSupport.world("end-return");
        LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        portal.setDimensionalPortalKind(DimensionalPortalKind.END_EXIT);
        for (int tick = 0; tick < 100; tick++) {
            portal.update();
            assertTrue(portal.isOpen());
            assertNull(portal.getTunnel());
        }
    }

    @Test
    void managedEndReceiversAndFountainsNeverScanEntitiesForCustomCaptures() {
        for (DimensionalPortalKind kind : new DimensionalPortalKind[] {DimensionalPortalKind.END_ARRIVAL, DimensionalPortalKind.END_EXIT}) {
            LocalPortal portal = mock(LocalPortal.class);
            when(portal.getDimensionalPortalKind()).thenReturn(kind);
            new LocalPortalTraversal(portal).updateCaptures(null, true);
            verify(portal, never()).getStructure();
        }
    }
}
