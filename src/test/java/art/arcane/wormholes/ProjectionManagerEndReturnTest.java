package art.arcane.wormholes;

import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.clientview.ClientViewRouting;
import art.arcane.wormholes.util.Direction;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectionManagerEndReturnTest {
    @Test
    void exitProjectionUsesTheObserversValidatedTargetWithoutATunnelOrRtpSemantics() {
        ProjectionManager previous = Wormholes.projectionManager;
        ProjectionManager manager = mock(ProjectionManager.class);
        Player observer = mock(Player.class);
        ILocalPortal exit = mock(ILocalPortal.class);
        PortalProjector.RtpProjectionTarget target = new PortalProjector.RtpProjectionTarget(mock(World.class),
            10.5, 64, 20.5, PortalFrame.canonical(Direction.U), 1);
        when(exit.getDimensionalPortalKind()).thenReturn(DimensionalPortalKind.END_EXIT);
        when(exit.supportsProjections()).thenReturn(true);
        when(exit.isProjecting()).thenReturn(true);
        when(exit.isOpen()).thenReturn(true);
        when(manager.endReturnTarget(observer, 20)).thenReturn(target);
        Wormholes.projectionManager = manager;
        try {
            ProjectionManager.ProjectionResolution ready = ProjectionManager.resolveProjection(null, exit, observer,
                null, 20, ClientViewRouting.none());
            assertTrue(ready.projectable());
            assertFalse(ready.rtp());
            assertSame(target, ready.target());
            verify(exit, never()).hasTunnel();
            ProjectionManager.ProjectionResolution waiting = ProjectionManager.resolveProjection(null, exit, observer,
                null, 21, ClientViewRouting.none());
            assertFalse(waiting.projectable());
        } finally {
            Wormholes.projectionManager = previous;
        }
    }
}
