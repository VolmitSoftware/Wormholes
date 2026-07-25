package art.arcane.wormholes.chunk.presend;

import art.arcane.wormholes.service.WormholesTelemetry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPreSendServiceTest {
    private static final ChunkPreSendOptions ENABLED = ChunkPreSendOptions.of(true, 1, 64, 5000);

    @Test
    void aDisabledServiceTouchesNothingAndLeavesTodaysBlinkExactlyAsItIs() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        ChunkPreSendService<String, String> service = service(platform, ChunkPreSendOptions.disabled());

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_DISABLED, ticket.outcome());
        assertTrue(platform.announced().isEmpty());
        assertTrue(platform.sent().isEmpty());
        assertFalse(ticket.rollbackRequired());
    }

    @Test
    void anUnsupportedPlatformNeverAnnouncesAViewCentre() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().supported(false);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_UNSUPPORTED_PLATFORM, ticket.outcome());
        assertTrue(platform.announced().isEmpty());
    }

    @Test
    void aPlayerWhoLoggedOutInsideTheCommitmentWindowNeverReceivesAnything() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().online(false);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_PLAYER_OFFLINE, ticket.outcome());
        assertTrue(platform.announced().isEmpty());
        assertTrue(platform.sent().isEmpty());
    }

    @Test
    void aCrossServerDestinationHasNothingToSendAndIsCountedOnce() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        ChunkPreSendService<String, String> service = service(platform, ENABLED);
        long before = failureCount("PRESEND_CROSS_SERVER");

        ChunkPreSendTicket<String, String> ticket = service.preSend(RecordingPreSendPlatform.PLAYER, null, 0, 0);

        assertEquals(ChunkPreSendOutcome.SKIPPED_CROSS_SERVER, ticket.outcome());
        assertTrue(platform.announced().isEmpty());
        assertEquals(before + 1L, failureCount("PRESEND_CROSS_SERVER"));
    }

    @Test
    void anUnloadedDestinationCentreIsSkippedWithoutMovingTheClientWindow() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().markUnloaded(0, 0);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_DESTINATION_UNLOADED, ticket.outcome());
        assertTrue(platform.announced().isEmpty());
    }

    @Test
    void aDestinationOwnedByAnotherRegionThreadIsSkipped() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().regionOwned(false);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_REGION_NOT_OWNED, ticket.outcome());
        assertTrue(platform.sent().isEmpty());
    }

    @Test
    void theOwnershipGuardCoversTheWholeBurstSquareAndNotJustTheDestinationCentre() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().markForeignRegion(1, 1);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_REGION_NOT_OWNED, ticket.outcome());
        assertTrue(platform.sent().isEmpty());
        assertTrue(platform.announced().isEmpty());
        assertEquals(
            List.of(new RecordingPreSendPlatform.Box(-1, -1, 1, 1)),
            platform.ownershipQueries(),
            "one corner of the burst square belonging to another region must veto the whole burst"
        );
    }

    @Test
    void theOwnershipGuardIsAskedBeforeAnyChunkMapReadOnTheDestination() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform()
            .regionOwned(false)
            .markUnloaded(0, 0);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_REGION_NOT_OWNED, ticket.outcome());
    }

    @Test
    void theViewCentreIsAnnouncedBeforeAnyChunkOrTheClientWouldDropThemAll() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().playerChunk(40, 40);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 16, -32
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT, ticket.outcome());
        assertEquals(List.of(new ChunkCoordinate(1, -2)), platform.announced());
        assertEquals(9, platform.sent().size());
        for (RecordingPreSendPlatform.Sent sent : platform.sent()) {
            assertEquals(1, sent.announcementsBefore(), "every chunk must follow the view centre announcement");
            assertEquals(RecordingPreSendPlatform.DESTINATION_WORLD, sent.world());
        }
        assertEquals(9, ticket.sentChunks());
        assertEquals(9, ticket.rollback().size());
        assertTrue(ticket.rollbackRequired());
    }

    @Test
    void aSameWorldHopThatTheClientAlreadyHasSendsNothingAndCriticallyDoesNotMoveTheWindow() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        for (ChunkCoordinate coordinate : ChunkPreSendPlanner.ring(0, 0, 1)) {
            platform.markAlreadySent(coordinate.x(), coordinate.z());
        }
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.SOURCE_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_ALREADY_PRESENT, ticket.outcome());
        assertTrue(platform.announced().isEmpty(),
            "announcing a centre with nothing behind it would blank the world the player is standing in");
        assertFalse(ticket.rollbackRequired());
    }

    @Test
    void aClientThatAlreadyHoldsPartOfTheDestinationIsNotReportedAsATruncatedBurst() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform()
            .markAlreadySent(1, 1)
            .markAlreadySent(-1, -1);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.SOURCE_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT, ticket.outcome());
        assertEquals(7, platform.sent().size());
    }

    @Test
    void aSameWorldHopWhollyInsideTheClientWindowStillRestoresTheSourceCentreWhenTheTraversalAborts() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().playerChunk(0, 0);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.SOURCE_WORLD, 192, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT, ticket.outcome());
        assertEquals(List.of(new ChunkCoordinate(12, 0)), platform.announced());
        assertEquals(9, ticket.sentChunks());
        assertEquals(0, ticket.rollback().size(),
            "every destination chunk sits in the source window and evicts only itself");
        assertTrue(ticket.rollbackRequired(),
            "the client was still moved off the source centre and nothing else will move it back");

        platform.announced().clear();
        assertEquals(ChunkPreSendRollbackOutcome.RESTORED, service.rollback(ticket));
        assertEquals(List.of(new ChunkCoordinate(0, 0)), platform.announced(),
            "an abort must re-centre the client on the chunk the player is standing in");
    }

    @Test
    void aFullyUnloadedDestinationRingIsCountedBecauseTheWarmerFailedToHoldIt() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        for (ChunkCoordinate coordinate : ChunkPreSendPlanner.ring(0, 0, 1)) {
            if (coordinate.x() != 0 || coordinate.z() != 0) {
                platform.markUnloaded(coordinate.x(), coordinate.z());
            }
        }
        platform.markAlreadySent(0, 0);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);
        long before = failureCount("PRESEND_NO_CHUNKS");

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.SOURCE_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_NO_CHUNKS, ticket.outcome());
        assertTrue(platform.announced().isEmpty());
        assertEquals(before + 1L, failureCount("PRESEND_NO_CHUNKS"));
    }

    @Test
    void aRejectedViewCentreAbortsTheBurstInsteadOfSendingChunksTheClientWillDrop() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().announceAccepted(false);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.FAILED_VIEW_CENTER, ticket.outcome());
        assertTrue(platform.sent().isEmpty());
        assertFalse(ticket.rollbackRequired());
    }

    @Test
    void theTimeBudgetTruncatesTheBurstRatherThanOverrunningTheTick() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().clockStep(5_000_000L);
        ChunkPreSendService<String, String> service = service(platform, ChunkPreSendOptions.of(true, 2, 64, 1000));

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT_PARTIAL, ticket.outcome());
        assertEquals(1, platform.sent().size(), "an exhausted budget still delivers one chunk, never zero");
        assertEquals(1, ticket.rollback().size());
    }

    @Test
    void theTimeBudgetAlsoBoundsThePreFilterSoAWideRingCannotOverrunBeforeTheFirstPacket() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().clockStep(5_000_000L);
        ChunkPreSendService<String, String> service = service(platform, ChunkPreSendOptions.of(true, 9, 1000, 1000));

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT_PARTIAL, ticket.outcome());
        assertEquals(1, platform.sent().size());
        assertTrue(
            platform.chunkLoadedCalls() <= 4,
            "the 361 chunk pre-filter ran outside the wall clock cap: " + platform.chunkLoadedCalls() + " probes"
        );
    }

    @Test
    void theChunkBudgetCapsHowManyChunksReachTheWire() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        ChunkPreSendService<String, String> service = service(platform, ChunkPreSendOptions.of(true, 3, 12, 5000));

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT_PARTIAL, ticket.outcome());
        assertEquals(12, platform.sent().size());
    }

    @Test
    void theShippedDefaultsDeliverTheWholeDefaultRingWithoutTouchingTheGlobalFailureCounter() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        ChunkPreSendService<String, String> service = service(platform, ChunkPreSendOptions.of(
            true,
            ChunkPreSendOptions.DEFAULT_RADIUS_CHUNKS,
            ChunkPreSendOptions.DEFAULT_MAX_CHUNKS,
            ChunkPreSendOptions.DEFAULT_BUDGET_MICROS
        ));
        long before = WormholesTelemetry.failures();

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT, ticket.outcome());
        assertEquals(49, platform.sent().size());
        assertEquals(before, WormholesTelemetry.failures(),
            "a normal traversal at shipped defaults must not move failuresPerMinute");
    }

    @Test
    void aTruncatedBurstIsStillASuccessAndNeverReachesTheFailureCounter() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        ChunkPreSendService<String, String> service = service(platform, ChunkPreSendOptions.of(true, 3, 12, 5000));
        long before = WormholesTelemetry.failures();

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT_PARTIAL, ticket.outcome());
        assertEquals(before, WormholesTelemetry.failures());
    }

    @Test
    void aNarrowClientWindowClampsTheBurstSoNoChunkIsSentThatTheClientWouldDrop() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().clientViewDistance(2);
        ChunkPreSendService<String, String> service = service(platform, ChunkPreSendOptions.of(true, 9, 500, 5000));

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT, ticket.outcome());
        assertEquals(121, platform.sent().size());
        for (RecordingPreSendPlatform.Sent sent : platform.sent()) {
            assertTrue(sent.coordinate().chebyshevDistance(0, 0) <= ClientChunkWindow.MINIMUM_CHUNK_RADIUS,
                sent.coordinate() + " sits outside the client acceptance square and would be dropped after decode");
        }
        assertEquals(
            List.of(new RecordingPreSendPlatform.Box(-5, -5, 5, 5)),
            platform.ownershipQueries(),
            "the ownership box must match the clamped burst, not the requested radius"
        );
    }

    @Test
    void anUnloadedChunkInsideTheRingIsSkippedAndTheBurstIsReportedAsPartial() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().markUnloaded(1, 1);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT_PARTIAL, ticket.outcome());
        assertEquals(8, platform.sent().size());
        assertEquals(8, ticket.rollback().size());
    }

    @Test
    void aDeliveryFailureMidBurstLeavesARollbackCoveringOnlyWhatActuallyLanded() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().sendFailsAfter(3);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT_PARTIAL, ticket.outcome());
        assertEquals(3, ticket.sentChunks());
        assertEquals(3, ticket.rollback().size());
    }

    @Test
    void rollbackReAnnouncesTheSourceCentreAndReSendsEverySlotTheBurstStole() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().playerChunk(7, -3);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);
        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 8000, 8000
        );
        platform.sent().clear();
        platform.announced().clear();

        ChunkPreSendRollbackOutcome outcome = service.rollback(ticket);

        assertEquals(ChunkPreSendRollbackOutcome.RESTORED, outcome);
        assertEquals(List.of(new ChunkCoordinate(7, -3)), platform.announced());
        assertEquals(9, platform.sent().size());
        List<ChunkCoordinate> restored = new ArrayList<>();
        for (RecordingPreSendPlatform.Sent sent : platform.sent()) {
            assertEquals(RecordingPreSendPlatform.SOURCE_WORLD, sent.world(),
                "rollback must repaint the world the player is still standing in");
            assertEquals(1, sent.announcementsBefore());
            restored.add(sent.coordinate());
        }
        assertEquals(ticket.rollback().resend(), restored);
    }

    @Test
    void theRollbackNeverReadsASourceChunkOwnedByAnotherRegionAndResumesOnThatRegionInstead() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().playerChunk(0, 0);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);
        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 8000, 8000
        );
        List<ChunkCoordinate> resend = ticket.rollback().resend();
        assertEquals(9, resend.size());
        ChunkCoordinate blocked = resend.get(2);
        platform.markForeignRegion(blocked.x(), blocked.z());
        platform.sent().clear();
        platform.announced().clear();

        assertEquals(ChunkPreSendRollbackOutcome.CONTINUED, service.rollback(ticket));
        assertEquals(2, platform.sent().size(), "the drain must stop at the first chunk it does not own");
        for (RecordingPreSendPlatform.Sent sent : platform.sent()) {
            assertFalse(blocked.equals(sent.coordinate()),
                "a chunk owned by another region must never be read from this thread");
        }
        assertEquals(1, platform.scheduled().size());
        assertEquals(blocked, platform.scheduled().get(0).coordinate(),
            "the continuation must be handed to the region that owns the blocked chunk");
        assertEquals(RecordingPreSendPlatform.SOURCE_WORLD, platform.scheduled().get(0).world(),
            "the continuation belongs to the source world, not the world the player was moved to");

        platform.releaseForeignRegion(blocked.x(), blocked.z());
        platform.runScheduled();

        assertEquals(9, platform.sent().size());
        assertTrue(platform.scheduled().isEmpty());
    }

    @Test
    void aRollbackRegionThatNeverBecomesReachableIsGivenUpOnRatherThanReschedulingForever() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().playerChunk(0, 0);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);
        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 8000, 8000
        );
        ChunkCoordinate blocked = ticket.rollback().resend().get(0);
        platform.markForeignRegion(blocked.x(), blocked.z());
        platform.sent().clear();
        long before = failureCount("PRESEND_ROLLBACK_REGION_UNREACHABLE");

        assertEquals(ChunkPreSendRollbackOutcome.CONTINUED, service.rollback(ticket));
        for (int attempt = 0; attempt < 8; attempt++) {
            platform.runScheduled();
        }

        assertTrue(platform.sent().isEmpty());
        assertTrue(platform.scheduled().isEmpty(), "the drain must stop rescheduling once it has given up");
        assertEquals(before + 1L, failureCount("PRESEND_ROLLBACK_REGION_UNREACHABLE"));
    }

    @Test
    void aBurstThatSentNothingNeedsNoRollbackAndSendsNoPackets() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().announceAccepted(false);
        ChunkPreSendService<String, String> service = service(platform, ENABLED);
        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendRollbackOutcome.NOT_NEEDED, service.rollback(ticket));
        assertTrue(platform.sent().isEmpty());
    }

    @Test
    void rollingBackTheSameTicketTwiceDoesNotRepaintTheWorldTwice() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        ChunkPreSendService<String, String> service = service(platform, ENABLED);
        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 8000, 8000
        );
        service.rollback(ticket);
        int afterFirst = platform.sent().size();

        assertEquals(ChunkPreSendRollbackOutcome.ALREADY_CONSUMED, service.rollback(ticket));
        assertEquals(afterFirst, platform.sent().size());
    }

    @Test
    void rollbackForAPlayerWhoLeftIsAbandonedRatherThanSendingToADeadConnection() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        ChunkPreSendService<String, String> service = service(platform, ENABLED);
        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 8000, 8000
        );
        platform.sent().clear();
        platform.online(false);

        assertEquals(ChunkPreSendRollbackOutcome.PLAYER_OFFLINE, service.rollback(ticket));
        assertTrue(platform.sent().isEmpty());
    }

    @Test
    void rollbackStopsIfTheSourceViewCentreCannotBeRestored() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        ChunkPreSendService<String, String> service = service(platform, ENABLED);
        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 8000, 8000
        );
        platform.sent().clear();
        platform.announceAccepted(false);

        assertEquals(ChunkPreSendRollbackOutcome.VIEW_CENTER_FAILED, service.rollback(ticket));
        assertTrue(platform.sent().isEmpty(), "chunks outside the client window would only be dropped");
    }

    @Test
    void aRollbackLargerThanOneSliceSpillsOntoTheNextTickInsteadOfOverrunning() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        AtomicReference<ChunkPreSendOptions> options =
            new AtomicReference<>(ChunkPreSendOptions.of(true, 2, 25, 5000));
        ChunkPreSendService<String, String> service = new ChunkPreSendService<>(platform, options::get);
        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 8000, 8000
        );
        assertEquals(25, ticket.rollback().size());
        platform.sent().clear();
        options.set(ChunkPreSendOptions.of(true, 2, 10, 5000));

        assertEquals(ChunkPreSendRollbackOutcome.CONTINUED, service.rollback(ticket));
        assertEquals(10, platform.sent().size());
        assertEquals(1, platform.scheduled().size());

        platform.runScheduled();
        assertEquals(20, platform.sent().size());
        platform.runScheduled();
        assertEquals(25, platform.sent().size());
        assertTrue(platform.scheduled().isEmpty());
    }

    @Test
    void aRollbackSliceAbandonsTheRestWhenThePlayerLeavesBetweenTicks() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        AtomicReference<ChunkPreSendOptions> options =
            new AtomicReference<>(ChunkPreSendOptions.of(true, 2, 25, 5000));
        ChunkPreSendService<String, String> service = new ChunkPreSendService<>(platform, options::get);
        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 8000, 8000
        );
        platform.sent().clear();
        options.set(ChunkPreSendOptions.of(true, 2, 10, 5000));
        assertEquals(ChunkPreSendRollbackOutcome.CONTINUED, service.rollback(ticket));
        long before = failureCount("PRESEND_ROLLBACK_PLAYER_OFFLINE");

        platform.online(false);
        platform.runScheduled();

        assertEquals(10, platform.sent().size(), "a departed player must not be handed another slice of chunks");
        assertEquals(before + 1L, failureCount("PRESEND_ROLLBACK_PLAYER_OFFLINE"));
    }

    @Test
    void aRefusedContinuationIsReportedRatherThanSilentlyLeavingHoles() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().scheduleAccepted(false);
        AtomicReference<ChunkPreSendOptions> options =
            new AtomicReference<>(ChunkPreSendOptions.of(true, 2, 25, 5000));
        ChunkPreSendService<String, String> service = new ChunkPreSendService<>(platform, options::get);
        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 8000, 8000
        );
        platform.sent().clear();
        options.set(ChunkPreSendOptions.of(true, 2, 10, 5000));
        long before = failureCount("PRESEND_ROLLBACK_SCHEDULE_REJECTED");

        assertEquals(ChunkPreSendRollbackOutcome.SCHEDULE_REJECTED, service.rollback(ticket));
        assertEquals(before + 1L, failureCount("PRESEND_ROLLBACK_SCHEDULE_REJECTED"));
    }

    @Test
    void aNullOptionsSupplierDegradesToDisabledRatherThanThrowingInsideTheCommitmentWindow() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        ChunkPreSendService<String, String> service = new ChunkPreSendService<>(platform, () -> null);

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_DISABLED, ticket.outcome());
    }

    private static ChunkPreSendService<String, String> service(
        RecordingPreSendPlatform platform,
        ChunkPreSendOptions options
    ) {
        return new ChunkPreSendService<>(platform, () -> options);
    }

    private static long failureCount(String reason) {
        Long count = WormholesTelemetry.failureBreakdown().get(reason);
        return count == null ? 0L : count;
    }
}
