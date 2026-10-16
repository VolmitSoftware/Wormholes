package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SeamlessReturnStraddleTest {
    @Test
    public void anAdoptedTraversableReturnHoldsAStraddleExceptThroughEndPortals() {
        assertTrue(MinecraftSeamlessMove.straddles(back(DimensionalPortalKind.NONE, true), true));
        assertTrue(MinecraftSeamlessMove.straddles(back(DimensionalPortalKind.NETHER, true), true));
        for (DimensionalPortalKind kind : new DimensionalPortalKind[] {DimensionalPortalKind.END_SOURCE, DimensionalPortalKind.END_ARRIVAL,
            DimensionalPortalKind.END_EXIT}) {
            assertFalse(MinecraftSeamlessMove.straddles(back(kind, true), true));
        }
        assertFalse(MinecraftSeamlessMove.straddles(back(DimensionalPortalKind.NONE, false), true));
        assertFalse(MinecraftSeamlessMove.straddles(back(DimensionalPortalKind.NONE, true), false));
        assertFalse(MinecraftSeamlessMove.straddles(null, true));
    }

    private static RemoteRoutes.Return back(DimensionalPortalKind kind, boolean traversable) {
        MinecraftPortal source = mock(MinecraftPortal.class);
        when(source.getDimensionalKind()).thenReturn(kind);
        return new RemoteRoutes.Return(source, mock(MinecraftPortal.class), mock(TravelMessage.RemoteLevelOpen.class), traversable);
    }
}
