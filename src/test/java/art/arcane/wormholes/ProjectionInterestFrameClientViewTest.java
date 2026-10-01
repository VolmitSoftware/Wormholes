package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.render.EntityRenderLocalOcclusionArbiter;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.PortalSkinRenderer;
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.wormholes.render.clientview.ClientViewRouting;
import art.arcane.wormholes.util.AxisAlignedBB;

final class ProjectionInterestFrameClientViewTest {
    private final AtomicLong now = new AtomicLong();
    private final ProjectionBudgetLedger ledger = new ProjectionBudgetLedger(now::get);
    private final ProjectionInterestSet interestSet = mock(ProjectionInterestSet.class);
    private final ProjectionClaimArbiter claimArbiter = mock(ProjectionClaimArbiter.class);
    private final EntityRenderLocalOcclusionArbiter<Player, Entity> localEntityOcclusion = mock(EntityRenderLocalOcclusionArbiter.class);
    private final PortalProjector projector = mock(PortalProjector.class);
    private final Player observer = mock(Player.class);
    private final UUID observerId = UUID.randomUUID();
    private final ILocalPortal portal = mock(ILocalPortal.class);
    private PortalCandidateSnapshot active;

    @BeforeEach
    void prepareObserver() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(observer.getUniqueId()).thenReturn(observerId);
        when(observer.isOnline()).thenReturn(true);
        when(observer.getWorld()).thenReturn(world);
        when(observer.getLocation()).thenReturn(new Location(world, 0.0D, 64.0D, 0.0D));
        when(observer.getEyeLocation()).thenReturn(new Location(world, 0.0D, 65.6D, 0.0D));
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getCenter()).thenReturn(new Location(world, 0.0D, 64.0D, 4.0D));
        when(portal.getView()).thenReturn(new AxisAlignedBB(-10.0D, 10.0D, 54.0D, 74.0D, -10.0D, 10.0D));
        when(portal.supportsProjections()).thenReturn(true);
        when(portal.isProjecting()).thenReturn(true);
        when(portal.isOpen()).thenReturn(true);
        when(portal.hasTunnel()).thenReturn(true);
        when(interestSet.obtain(portal, observer)).thenReturn(projector);
        when(interestSet.scheduleBlocks(eq(observerId), any(), anyList(), anyInt(), anyLong())).thenAnswer(call -> List.of());
        active = PortalCandidateSnapshot.captureProjection(List.of(portal));
    }

    @Test
    void pendingHandshakeHoldsTheVanillaProjectorAndReturnsTheBudget() {
        AtomicInteger remaining = new AtomicInteger(1);

        frame(new Routing(true, false)).project(observer, active, remaining, 2, true, true, 1L, false, active, ledger.beginFrame(30_000));

        assertEquals(3, remaining.get());
        verify(claimArbiter, never()).beginFrame(any(), any(), anyBoolean());
        verify(interestSet, never()).obtain(portal, observer);
    }

    @Test
    void ownedPortalsNeverReachTheVanillaProjector() {
        frame(new Routing(false, true)).project(observer, active, new AtomicInteger(), 1, true, true, 1L, false, active,
            ledger.beginFrame(30_000));

        verify(interestSet).closeUnplanned(eq(observerId), eq(Set.of()), eq(1L), anyInt());
        verify(interestSet, never()).obtain(portal, observer);
        verify(claimArbiter).flushFrame(observer);
    }

    @Test
    void notOwnedPortalsStayOnTheVanillaProjector() {
        UUID portalId = portal.getId();
        frame(new Routing(false, false)).project(observer, active, new AtomicInteger(), 1, true, true, 1L, false, active,
            ledger.beginFrame(30_000));

        verify(interestSet).closeUnplanned(eq(observerId), eq(Set.of(portalId)), eq(1L), anyInt());
        verify(interestSet).obtain(portal, observer);
    }

    private ProjectionInterestFrame frame(ClientViewRouting routing) {
        return new ProjectionInterestFrame(interestSet, ledger, claimArbiter, localEntityOcclusion, mock(PortalSkinRenderer.class),
            mock(RtpRimRenderer.class), () -> null, () -> true, routing);
    }

    private record Routing(boolean hold, boolean own) implements ClientViewRouting {
        @Override
        public boolean holdsVanilla(Player observer, long frameTick) {
            return hold;
        }

        @Override
        public void route(Player observer, Location eye, List<ILocalPortal> interested, List<ILocalPortal> projectable,
                          Map<UUID, PortalProjector.RtpProjectionTarget> rtpTargets, long frameTick) {
            if (own) {
                interested.clear();
            }
        }
    }
}
