package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.render.EntityRenderLocalOcclusionArbiter;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.PortalSkinRenderer;
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.wormholes.util.AxisAlignedBB;

final class ProjectionInterestFrameBudgetTest {
    private final AtomicLong now = new AtomicLong();
    private final ProjectionBudgetLedger ledger = new ProjectionBudgetLedger(now::get);
    private final ProjectionInterestSet interestSet = mock(ProjectionInterestSet.class);
    private final ProjectionClaimArbiter claimArbiter = mock(ProjectionClaimArbiter.class);
    private final EntityRenderLocalOcclusionArbiter localEntityOcclusion = mock(EntityRenderLocalOcclusionArbiter.class);
    private final PortalSkinRenderer skinRenderer = mock(PortalSkinRenderer.class);
    private final PortalProjector projector = mock(PortalProjector.class);
    private final Player observer = mock(Player.class);
    private final UUID observerId = UUID.randomUUID();
    private final ILocalPortal portal = mock(ILocalPortal.class);
    private final ProjectionInterestFrame interestFrame = new ProjectionInterestFrame(interestSet, ledger,
        claimArbiter, localEntityOcclusion, skinRenderer, mock(RtpRimRenderer.class), () -> null, () -> true);
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
        when(interestSet.nextSlice(eq(observerId), anyList(), eq(1))).thenReturn(List.of(portal));
        active = PortalCandidateSnapshot.captureProjection(List.of(portal));
    }

    @Test
    void deniedBlockRefreshReturnsReservedProjectorsAndContinuesEntities() {
        ProjectionBudgetLedger.FrameBudget frame = exhaustedFrame();
        AtomicInteger remaining = new AtomicInteger(2);

        interestFrame.project(observer, active, remaining, 3, true, true, 1L, false, active, frame);

        assertEquals(5, remaining.get());
        verify(projector).project(eq(false), eq(true), anyLong());
        verify(projector, never()).project(eq(true), eq(true), anyLong());
        verify(interestSet).refreshProjectedEntities(projector);
        verify(interestSet, never()).discardObserver(observerId);
        verify(claimArbiter).flushFrame(observer);
    }

    @Test
    void deniedBlockOnlyFrameReturnsReservationsWithoutTouchingTheCommittedProjector() {
        ProjectionBudgetLedger.FrameBudget frame = exhaustedFrame();
        AtomicInteger remaining = new AtomicInteger();

        interestFrame.project(observer, active, remaining, 3, true, false, 1L, false, active, frame);

        assertEquals(3, remaining.get());
        verify(interestSet, never()).obtain(portal, observer);
        verify(claimArbiter).retryPending(observer, observer.getWorld());
        verify(claimArbiter).flushFrame(observer);
    }

    @Test
    void finalClaimAndBlockEntityFlushesContributeToTheObservedCost() {
        doAnswer(invocation -> {
            now.addAndGet(20_000_000L);
            return null;
        }).when(projector).project(eq(true), eq(true), anyLong());
        doAnswer(invocation -> {
            now.addAndGet(5_000_000L);
            return null;
        }).when(claimArbiter).flushFrame(observer);
        doAnswer(invocation -> {
            now.addAndGet(5_000_000L);
            return Integer.valueOf(0);
        }).when(projector).flushBlockEntities(anyInt());

        interestFrame.project(observer, active, new AtomicInteger(), 1, true, true, 1L,
            false, active, ledger.beginFrame(30_000));

        ProjectionBudgetLedger.FrameBudget next = ledger.beginFrame(30_000);
        try (ProjectionBudgetLedger.ObserverFrame first = next.beginObserver(UUID.randomUUID())) {
            first.recordBlockWork();
            now.incrementAndGet();
        }
        try (ProjectionBudgetLedger.ObserverFrame repeated = next.beginObserver(observerId)) {
            assertFalse(repeated.admitsBlocks());
        }
    }

    @Test
    void throwingFinalFlushStillChargesTheFrameAndFinishesBlockEntities() {
        UUID cheapId = UUID.randomUUID();
        try (ProjectionBudgetLedger.ObserverFrame seed = ledger.beginFrame(0).beginObserver(cheapId)) {
            seed.recordBlockWork();
            now.addAndGet(1_000_000L);
        }
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        doAnswer(invocation -> {
            now.addAndGet(20_000_000L);
            return null;
        }).when(projector).project(eq(true), eq(true), anyLong());
        doAnswer(invocation -> {
            now.addAndGet(12_000_000L);
            throw new IllegalStateException("claim flush failure");
        }).when(claimArbiter).flushFrame(observer);

        assertThrows(IllegalStateException.class, () -> interestFrame.project(observer, active,
            new AtomicInteger(), 1, true, true, 1L, false, active, frame));

        verify(projector).flushBlockEntities(anyInt());
        try (ProjectionBudgetLedger.ObserverFrame next = frame.beginObserver(cheapId)) {
            assertFalse(next.admitsBlocks());
        }
    }

    @Test
    void offlineObserversReturnReservationsWithoutStartingAFrame() {
        when(observer.isOnline()).thenReturn(false);
        AtomicInteger remaining = new AtomicInteger();

        interestFrame.project(observer, active, remaining, 2, true, true, 1L, false,
            active, ledger.beginFrame(30_000));

        assertEquals(2, remaining.get());
        verify(claimArbiter, never()).beginFrame(observer, observer.getWorld(), false);
    }

    @Test
    void pendingScanContinuesBetweenRefreshTicksWithTheRemainingFrameDeadline() {
        when(interestSet.hasPendingScan(portal.getId(), observerId)).thenReturn(true);
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        AtomicInteger remaining = new AtomicInteger();

        interestFrame.project(observer, active, remaining, 2, false, false, 2L, false, active, frame);

        assertEquals(1, remaining.get());
        verify(projector).project(true, false, 30_000_000L);
    }

    @Test
    void unrelatedViewsKeepTheirRefreshCadenceWhileEntitiesContinue() {
        AtomicInteger remaining = new AtomicInteger();

        interestFrame.project(observer, active, remaining, 2, false, true, 2L,
            false, active, ledger.beginFrame(30_000));

        assertEquals(2, remaining.get());
        verify(projector).project(false, true, 30_000_000L);
        verify(projector, never()).project(eq(true), eq(true), anyLong());
    }

    @Test
    void pendingScanStillNeedsTimeAndCountAdmission() {
        when(interestSet.hasPendingScan(portal.getId(), observerId)).thenReturn(true);
        AtomicInteger remaining = new AtomicInteger();
        ProjectionBudgetLedger.FrameBudget frame = exhaustedFrame();

        interestFrame.project(observer, active, remaining, 2, false, true, 2L, false, active, frame);

        assertEquals(2, remaining.get());
        verify(projector).project(eq(false), eq(true), anyLong());
        verify(projector, never()).project(eq(true), eq(true), anyLong());
    }

    @Test
    void pendingScanWaitsWhenNoProjectorCountIsAvailable() {
        when(interestSet.hasPendingScan(portal.getId(), observerId)).thenReturn(true);

        interestFrame.project(observer, active, new AtomicInteger(), 0, false, true, 2L,
            false, active, ledger.beginFrame(30_000));

        verify(projector).project(eq(false), eq(true), anyLong());
        verify(projector, never()).project(eq(true), eq(true), anyLong());
    }

    @Test
    void retiringContinuationConsumesOneCountReservation() {
        PortalProjector retired = mock(PortalProjector.class);
        ILocalPortal retiredPortal = mock(ILocalPortal.class);
        when(retiredPortal.getId()).thenReturn(UUID.randomUUID());
        when(retired.getPortal()).thenReturn(retiredPortal);
        when(retired.hasPendingScan()).thenReturn(true);
        when(interestSet.retiringProjectors(observerId)).thenReturn(List.of(retired));
        when(interestSet.nextSlice(eq(observerId), anyList(), eq(1))).thenReturn(List.of(retiredPortal));
        AtomicInteger remaining = new AtomicInteger();

        interestFrame.project(observer, active, remaining, 2, false, true, 2L,
            false, active, ledger.beginFrame(30_000));

        assertEquals(1, remaining.get());
        verify(retired).project(true, false, 30_000_000L);
        verify(projector).project(false, true, 30_000_000L);
    }

    private ProjectionBudgetLedger.FrameBudget exhaustedFrame() {
        ProjectionBudgetLedger.FrameBudget frame = ledger.beginFrame(30_000);
        try (ProjectionBudgetLedger.ObserverFrame first = frame.beginObserver(UUID.randomUUID())) {
            first.recordBlockWork();
            now.addAndGet(40_000_000L);
        }
        return frame;
    }
}
