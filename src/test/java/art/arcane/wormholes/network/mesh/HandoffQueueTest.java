package art.arcane.wormholes.network.mesh;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandoffQueueTest {
    private static final DestinationPolicyEngine.Resolution QUEUE = DestinationPolicyEngine.Resolution.queue();
    private static final DestinationPolicyEngine.Resolution CHOSEN = DestinationPolicyEngine.Resolution.chosen("beta", UUID.randomUUID());

    @Test
    void deadlineStaysUnderTheThirtySecondInFlightLimitAndTimeoutReleases() {
        HandoffQueue queue = new HandoffQueue();
        UUID player = UUID.randomUUID();
        AtomicInteger timeouts = new AtomicInteger();
        List<String> positions = new ArrayList<>();
        HandoffQueue.Ticket ticket = queue.enqueue(player, 1_000L, 29_000L, () -> QUEUE,
            resolution -> positions.add("resolved"), timeouts::incrementAndGet,
            (position, remainingMillis) -> positions.add(position + "@" + remainingMillis));

        assertTrue(ticket.deadlineMillis() - 1_000L < 30_000L);
        assertEquals(30_000L, ticket.deadlineMillis());
        assertEquals(1, queue.position(player));
        queue.tick(2_000L);
        assertEquals(List.of("1@28000"), positions);
        queue.tick(30_000L);
        assertEquals(1, timeouts.get());
        assertEquals(0, queue.size());
        assertEquals(0, queue.position(player));
        queue.tick(31_000L);
        assertEquals(1, timeouts.get());
    }

    @Test
    void queuedPlayerDequeuesAsSoonAsAResolutionIsChosenAndPositionsShiftUp() {
        HandoffQueue queue = new HandoffQueue();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtomicReference<DestinationPolicyEngine.Resolution> firstResolution = new AtomicReference<>(QUEUE);
        AtomicReference<DestinationPolicyEngine.Resolution> resolvedFirst = new AtomicReference<>();
        List<Integer> secondPositions = new ArrayList<>();
        queue.enqueue(first, 0L, 25_000L, firstResolution::get, resolvedFirst::set, () -> { }, (position, remaining) -> { });
        queue.enqueue(second, 0L, 25_000L, () -> QUEUE, resolution -> { }, () -> { }, (position, remaining) -> secondPositions.add(position));

        queue.tick(1_000L);
        assertEquals(List.of(2), secondPositions);
        firstResolution.set(CHOSEN);
        queue.tick(2_000L);
        assertEquals(CHOSEN, resolvedFirst.get());
        assertEquals(1, queue.size());
        assertEquals(List.of(2, 1), secondPositions);
        assertEquals(1, queue.position(second));
    }

    @Test
    void removeDropsATicketWithoutCallbacksAndDuplicateEnqueueReplacesTheOldTicket() {
        HandoffQueue queue = new HandoffQueue();
        UUID player = UUID.randomUUID();
        AtomicInteger timeouts = new AtomicInteger();
        queue.enqueue(player, 0L, 5_000L, () -> QUEUE, resolution -> { }, timeouts::incrementAndGet, (position, remaining) -> { });
        queue.enqueue(player, 0L, 9_000L, () -> QUEUE, resolution -> { }, timeouts::incrementAndGet, (position, remaining) -> { });
        assertEquals(1, queue.size());
        queue.tick(6_000L);
        assertEquals(0, timeouts.get());
        HandoffQueue.Ticket ticket = queue.ticket(player);
        assertTrue(queue.remove(ticket));
        assertFalse(queue.remove(ticket));
        queue.tick(10_000L);
        assertEquals(0, timeouts.get());
        assertNull(queue.ticket(player));
    }

    @Test
    void noneResolutionReleasesImmediatelyThroughTheTimeoutPath() {
        HandoffQueue queue = new HandoffQueue();
        UUID player = UUID.randomUUID();
        AtomicInteger releases = new AtomicInteger();
        queue.enqueue(player, 0L, 25_000L, DestinationPolicyEngine.Resolution::none, resolution -> { }, releases::incrementAndGet, (position, remaining) -> { });
        queue.tick(1_000L);
        assertEquals(1, releases.get());
        assertEquals(0, queue.size());
    }

    @Test
    void aResolverCannotRemoveOrDispatchAReplacementTicket() {
        HandoffQueue queue = new HandoffQueue();
        UUID player = UUID.randomUUID();
        AtomicInteger dispatches = new AtomicInteger();
        AtomicReference<HandoffQueue.Ticket> replacement = new AtomicReference<>();
        HandoffQueue.Ticket old = queue.enqueue(player, 0L, 25_000L, () -> {
            replacement.set(queue.enqueue(player, 1L, 30_000L, () -> QUEUE,
                resolution -> dispatches.incrementAndGet(), () -> { }, (position, remaining) -> { }));
            return CHOSEN;
        }, resolution -> dispatches.incrementAndGet(), () -> { }, (position, remaining) -> { });

        queue.tick(old, 1_000L);

        assertSame(replacement.get(), queue.ticket(player));
        assertEquals(0, dispatches.get());
        assertFalse(queue.remove(old));
        queue.tick(old, 40_000L);
        assertSame(replacement.get(), queue.ticket(player));
    }

    @Test
    void ticketOwnershipUsesIdentityEvenWhenEveryValueMatches() {
        HandoffQueue queue = new HandoffQueue();
        UUID player = UUID.randomUUID();
        HandoffQueue.Ticket first = queue.enqueue(player, 0L, 25_000L, () -> QUEUE,
            resolution -> { }, () -> { }, (position, remaining) -> { });
        HandoffQueue.Ticket second = queue.enqueue(player, first.enqueuedAtMillis(), 25_000L,
            first.resolver(), first.onResolved(), first.onTimeout(), first.onPosition());

        assertEquals(first, second);
        assertFalse(queue.remove(first));
        assertSame(second, queue.ticket(player));
        assertTrue(queue.remove(second));
    }
}
