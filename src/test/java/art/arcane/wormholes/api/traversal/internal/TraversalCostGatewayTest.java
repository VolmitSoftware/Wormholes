package art.arcane.wormholes.api.traversal.internal;

import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalDecision;
import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalOutcome;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.api.traversal.WormholesPortalTraverseEvent;
import art.arcane.wormholes.api.traversal.WormholesPortalTraversedEvent;
import art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.CapturingLogger;
import art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.RecordingEventSink;
import art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.TestProvider;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.localContext;
import static art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.player;
import static art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.registration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraversalCostGatewayTest {
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final AtomicReference<TraversalCostPolicy> policy = new AtomicReference<>(TraversalCostPolicy.defaults());
    private final AtomicReference<List<TraversalCostRegistration>> registrations = new AtomicReference<>(List.of());
    private final RecordingEventSink sink = new RecordingEventSink();
    private final CapturingLogger log = TraversalCostTestSupport.logger();

    private TraversalCostGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new TraversalCostGateway(registrations::get, policy::get, sink, log.logger(), clock::get);
    }

    @Test
    void withNoProvidersRegisteredTheTraversalIsAllowedForFreeAndStillOpensATicket() {
        TraversalContext context = localContext(player(UUID.randomUUID()));

        TraversalDecision decision = gateway.evaluate(context);

        assertTrue(decision.allowed());
        assertEquals(TraversalOutcome.ALLOWED_FREE, decision.outcome());
        assertTrue(gateway.isOpen(context.traversalId()),
            "every allowed traversal must leave exactly one ticket so the post-commit event has something to fire on");
        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(context.traversalId()));
        assertFalse(gateway.isOpen(context.traversalId()));
    }

    @Test
    void aProviderThatDeniesStopsTheTraversalAndNothingIsReserved() {
        TestProvider gatekeeper = new TestProvider("gate");
        gatekeeper.onQuote = context -> TraversalQuote.denied("Not attuned to this gate");
        registrations.set(List.of(registration(gatekeeper, "GatePlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        TraversalDecision decision = gateway.evaluate(context);

        assertFalse(decision.allowed());
        assertEquals(TraversalOutcome.DENIED_BY_PROVIDER, decision.outcome());
        assertEquals("Not attuned to this gate", decision.reason());
        assertEquals("gate", decision.providerId());
        assertEquals(0, gatekeeper.reserveCalls.get());
        assertFalse(gateway.isOpen(context.traversalId()));
    }

    @Test
    void aPricingProviderIsQuotedOnceReservedOnceAndReportedAsCharged() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        TraversalDecision decision = gateway.evaluate(context);

        assertTrue(decision.allowed());
        assertEquals(TraversalOutcome.ALLOWED_CHARGED, decision.outcome());
        assertEquals(1, shop.quoteCalls.get());
        assertEquals(1, shop.reserveCalls.get());
        assertTrue(shop.refunds.isEmpty());
    }

    @Test
    void admissionSettlesAnAllowedTraversalExactlyOnce() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalCostGateway.Admission admission = gateway.open(localContext(player(UUID.randomUUID())));

        assertTrue(admission.allowed());
        assertEquals(TraversalSettlement.REFUNDED, admission.refund(TraversalRefundReason.TELEPORT_FAILED));
        assertEquals(TraversalSettlement.NOT_OPEN, admission.commit());
        assertEquals(List.of(TraversalRefundReason.TELEPORT_FAILED), shop.refunds);
        assertEquals(0, shop.commitCalls.get());
    }

    @Test
    void evaluationOutsideTheTravelerOwnerNeverCallsProvidersOrEvents() {
        ControlledTravelerExecutor executor = new ControlledTravelerExecutor();
        executor.owned.set(false);
        gateway = gateway(executor);
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));

        TraversalDecision decision = gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertEquals(TraversalOutcome.DENIED_PROVIDER_FAILED, decision.outcome());
        assertEquals(0, shop.quoteCalls.get());
        assertTrue(sink.immediate.isEmpty());
        assertTrue(log.messages().stream().anyMatch(message -> message.contains("outside the traveler entity owner")));
    }

    @Test
    void offOwnerCommitIsPendingUntilTheTravelerExecutorRunsIt() {
        ControlledTravelerExecutor executor = new ControlledTravelerExecutor();
        gateway = gateway(executor);
        TestProvider shop = new TestProvider("shop");
        shop.onQuote = context -> {
            assertTrue(executor.owned.get());
            return TraversalQuote.payable("3 Mana");
        };
        shop.onReserve = (context, quote) -> {
            assertTrue(executor.owned.get());
            return TraversalReservation.reserved(TraversalReceipt.of("shop"));
        };
        shop.onCommit = receipt -> assertTrue(executor.owned.get());
        sink.onImmediate = event -> assertTrue(executor.owned.get());
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));
        gateway.evaluate(context);

        executor.owned.set(false);

        assertEquals(TraversalSettlement.PENDING, gateway.commit(context.traversalId()));
        assertFalse(TraversalSettlement.PENDING.settled());
        assertEquals(0, shop.commitCalls.get());
        assertTrue(sink.entity.isEmpty());
        assertTrue(gateway.isOpen(context.traversalId()));

        executor.runNextEntity();

        assertEquals(1, shop.commitCalls.get());
        assertEquals(1, sink.entity.size());
        assertFalse(gateway.isOpen(context.traversalId()));
        assertEquals(TraversalSettlement.NOT_OPEN, gateway.commit(context.traversalId()));
    }

    @Test
    void anInsufficientQuoteDeniesBeforeAnythingIsReserved() {
        TestProvider shop = new TestProvider("shop");
        shop.onQuote = context -> TraversalQuote.insufficient("You need 3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));

        TraversalDecision decision = gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertEquals(TraversalOutcome.DENIED_INSUFFICIENT, decision.outcome());
        assertEquals("You need 3 Mana", decision.reason());
        assertEquals(0, shop.reserveCalls.get());
    }

    @Test
    void aChargeThatFailsAfterAGoodQuoteRollsBackEveryEarlierProviderAndDenies() {
        TestProvider first = new TestProvider("first").charging("3 Mana");
        TestProvider second = new TestProvider("second").charging("1 Emerald");
        second.onReserve = (context, quote) -> TraversalReservation.failed("your pouch is empty");
        registrations.set(List.of(registration(first, "ManaPlugin"), registration(second, "PouchPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        TraversalDecision decision = gateway.evaluate(context);

        assertFalse(decision.allowed());
        assertEquals(TraversalOutcome.DENIED_INSUFFICIENT, decision.outcome());
        assertEquals("your pouch is empty", decision.reason());
        assertEquals("second", decision.providerId());
        assertEquals(List.of(TraversalRefundReason.CHARGE_ROLLBACK), first.refunds,
            "a charge that cannot be completed for everyone must be completed for nobody");
        assertFalse(gateway.isOpen(context.traversalId()));
    }

    @Test
    void refundAfterCommitIsANoOpAndNeverReachesTheProvider() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        assertTrue(gateway.evaluate(context).allowed());
        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(context.traversalId()));
        assertEquals(1, shop.commitCalls.get());

        assertEquals(TraversalSettlement.NOT_OPEN,
            gateway.refund(context.traversalId(), TraversalRefundReason.TELEPORT_FAILED),
            "commit is final; a later refund must not double-move value");
        assertTrue(shop.refunds.isEmpty());
    }

    @Test
    void settlingATraversalTwiceIsReportedDifferentlyFromSettlingWithTheMasterSwitchOff() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        gateway.evaluate(context);

        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(context.traversalId()));
        assertEquals(TraversalSettlement.NOT_OPEN, gateway.commit(context.traversalId()),
            "a double commit is a wiring bug and must not look like the feature being switched off");
        assertEquals(TraversalSettlement.NOT_OPEN, gateway.commit(UUID.randomUUID()));

        policy.set(TraversalCostPolicy.of(false, "allow", 5, 5L));

        assertEquals(TraversalSettlement.DISABLED, gateway.commit(context.traversalId()));
        assertEquals(TraversalSettlement.DISABLED,
            gateway.refund(context.traversalId(), TraversalRefundReason.TRAVERSAL_ABORTED));
        assertFalse(TraversalSettlement.DISABLED.settled());
        assertTrue(TraversalSettlement.COMMITTED.settled());
        assertTrue(TraversalSettlement.REFUNDED.settled());
    }

    @Test
    void committingFiresTheTraversedEventCarryingTheChargedProviderIds() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        gateway.evaluate(context);
        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(context.traversalId()));

        assertEquals(1, sink.entity.size());
        WormholesPortalTraversedEvent event = assertInstanceOf(WormholesPortalTraversedEvent.class, sink.entity.get(0));
        assertEquals(TraversalOutcome.ALLOWED_CHARGED, event.getOutcome());
        assertEquals(List.of("shop"), event.getChargedProviderIds());
        assertEquals(context.traversalId(), event.getContext().traversalId());
    }

    @Test
    void aProviderThatThrowsWhileQuotingIsTreatedAsARefusalToChargeAndTheTraversalProceedsFree() {
        TestProvider broken = new TestProvider("broken");
        broken.onQuote = context -> {
            throw new IllegalStateException("boom");
        };
        registrations.set(List.of(registration(broken, "BrokenPlugin")));

        TraversalDecision decision = gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertTrue(decision.allowed());
        assertEquals(TraversalOutcome.ALLOWED_PROVIDER_FAILED, decision.outcome());
        assertEquals("broken", decision.providerId());
        assertTrue(log.messages().stream().anyMatch(message -> message.contains("failed during quote")
            && message.contains("BrokenPlugin")));
    }

    @Test
    void aProviderThatThrowsWhileQuotingDeniesWhenTheFailurePolicyIsDeny() {
        policy.set(TraversalCostPolicy.of(true, "deny", 5, 5L));
        TestProvider broken = new TestProvider("broken");
        broken.onQuote = context -> {
            throw new IllegalStateException("boom");
        };
        registrations.set(List.of(registration(broken, "BrokenPlugin")));

        TraversalDecision decision = gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertFalse(decision.allowed());
        assertEquals(TraversalOutcome.DENIED_PROVIDER_FAILED, decision.outcome());
    }

    @Test
    void aProviderThatReturnsNullFromQuoteIsTreatedExactlyLikeOneThatThrows() {
        TestProvider broken = new TestProvider("broken");
        broken.onQuote = context -> null;
        registrations.set(List.of(registration(broken, "BrokenPlugin")));

        TraversalDecision decision = gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertEquals(TraversalOutcome.ALLOWED_PROVIDER_FAILED, decision.outcome());
        assertTrue(log.messages().stream().anyMatch(message -> message.contains("returned null")));
    }

    @Test
    void aProviderThatThrowsWhileReservingRollsBackEveryEarlierChargeAndProceedsFree() {
        TestProvider payer = new TestProvider("payer").charging("3 Mana");
        TestProvider broken = new TestProvider("broken").charging("1 Emerald");
        broken.onReserve = (context, quote) -> {
            throw new IllegalStateException("boom");
        };
        registrations.set(List.of(registration(payer, "ManaPlugin"), registration(broken, "BrokenPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        TraversalDecision decision = gateway.evaluate(context);

        assertTrue(decision.allowed());
        assertEquals(TraversalOutcome.ALLOWED_PROVIDER_FAILED, decision.outcome());
        assertEquals(List.of(TraversalRefundReason.CHARGE_ROLLBACK), payer.refunds);
        assertTrue(gateway.isOpen(context.traversalId()));
        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(context.traversalId()));
        assertEquals(0, payer.commitCalls.get(), "a rolled-back charge must never be committed");
    }

    @Test
    void aProviderThatThrowsWhileCommittingDoesNotBreakTheCommit() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        shop.onCommit = receipt -> {
            throw new IllegalStateException("boom");
        };
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        gateway.evaluate(context);

        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(context.traversalId()));
        assertEquals(1, sink.entity.size(), "the traversed notification must still reach listeners");
        assertTrue(log.messages().stream().anyMatch(message -> message.contains("failed during commit")));
    }

    @Test
    void aProviderThatThrowsWhileRefundingNeverStopsTheRollbackLoopWhichRunsInReverseChargeOrder() {
        List<String> refundOrder = new CopyOnWriteArrayList<>();
        TestProvider first = new TestProvider("first").charging("3 Mana");
        TestProvider second = new TestProvider("second").charging("1 Emerald");
        TestProvider third = new TestProvider("third").charging("2 Shards");
        first.onRefund = (receipt, reason) -> refundOrder.add("first");
        second.onRefund = (receipt, reason) -> {
            throw new IllegalStateException("boom");
        };
        third.onRefund = (receipt, reason) -> refundOrder.add("third");
        registrations.set(List.of(registration(first, "A"), registration(second, "B"), registration(third, "C")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        assertEquals(TraversalOutcome.ALLOWED_CHARGED, gateway.evaluate(context).outcome());
        assertEquals(TraversalSettlement.REFUNDED,
            gateway.refund(context.traversalId(), TraversalRefundReason.TELEPORT_FAILED));

        assertEquals(List.of("third", "first"), refundOrder,
            "a throwing refund must not strand the receipts behind it, and rollback must unwind in reverse");
        assertEquals(List.of(TraversalRefundReason.TELEPORT_FAILED), second.refunds);
        assertTrue(log.messages().stream().anyMatch(message -> message.contains("failed to refund")));
    }

    @Test
    void theFirstProviderToDenyStopsTheQuotePassBeforeTheSecondProviderIsAsked() {
        TestProvider gatekeeper = new TestProvider("gate");
        gatekeeper.onQuote = context -> TraversalQuote.denied("Not attuned");
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(gatekeeper, "GatePlugin"), registration(shop, "ManaPlugin")));

        TraversalDecision decision = gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertEquals(TraversalOutcome.DENIED_BY_PROVIDER, decision.outcome());
        assertEquals(0, shop.quoteCalls.get());
        assertEquals(0, shop.reserveCalls.get());
    }

    @Test
    void theMasterSwitchShortCircuitsEveryProviderAndBothEvents() {
        policy.set(TraversalCostPolicy.of(false, "allow", 5, 5L));
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        TraversalDecision decision = gateway.evaluate(context);

        assertTrue(decision.allowed());
        assertEquals(TraversalOutcome.DISABLED, decision.outcome());
        assertEquals(0, shop.quoteCalls.get());
        assertTrue(sink.immediate.isEmpty());
        assertFalse(gateway.isOpen(context.traversalId()));
        assertEquals(TraversalSettlement.DISABLED, gateway.commit(context.traversalId()));
        assertEquals(TraversalSettlement.DISABLED,
            gateway.refund(context.traversalId(), TraversalRefundReason.TRAVERSAL_ABORTED));
    }

    @Test
    void aDisabledEvaluationStillSweepsAndOwnerRefundsAnOlderTicket() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext old = localContext(player(UUID.randomUUID()));
        gateway.evaluate(old);
        clock.addAndGet(TraversalCostGateway.TICKET_TTL_MILLIS);
        policy.set(TraversalCostPolicy.of(false, "allow", 5, 5L));

        TraversalDecision disabled = gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertEquals(TraversalOutcome.DISABLED, disabled.outcome());
        assertEquals(List.of(TraversalRefundReason.EXPIRED), shop.refunds);
        assertFalse(gateway.isOpen(old.traversalId()));
    }

    @Test
    void aCancelledTraverseEventDeniesBeforeAnyProviderIsAsked() {
        sink.onImmediate = event -> {
            if (event instanceof WormholesPortalTraverseEvent traverse) {
                traverse.setCancelReason("This gate is warded");
                traverse.setCancelled(true);
            }
        };
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        TraversalDecision decision = gateway.evaluate(context);

        assertFalse(decision.allowed());
        assertEquals(TraversalOutcome.DENIED_BY_LISTENER, decision.outcome());
        assertEquals("This gate is warded", decision.reason());
        assertEquals(0, shop.quoteCalls.get());
        assertFalse(gateway.isOpen(context.traversalId()));
    }

    @Test
    void aProviderThatKeepsFailingIsQuarantinedWithOneLogLineNamingThePlugin() {
        policy.set(TraversalCostPolicy.of(true, "allow", 2, 5L));
        TestProvider broken = new TestProvider("broken");
        broken.onQuote = context -> {
            throw new IllegalStateException("boom");
        };
        registrations.set(List.of(registration(broken, "BrokenPlugin")));

        gateway.evaluate(localContext(player(UUID.randomUUID())));
        gateway.evaluate(localContext(player(UUID.randomUUID())));
        gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertEquals(2, broken.quoteCalls.get(), "a quarantined provider must not be called again");
        List<String> severe = log.messagesAt(Level.SEVERE);
        assertEquals(1, severe.size());
        assertTrue(severe.get(0).contains("Disabled traversal cost provider 'broken'"));
        assertTrue(severe.get(0).contains("BrokenPlugin"));
    }

    @Test
    void aTicketThatIsNeverCommittedOrRefundedIsReclaimedAndRefundedAfterTheTtl() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        gateway.evaluate(context);
        assertEquals(0, gateway.sweep());

        clock.addAndGet(TraversalCostGateway.TICKET_TTL_MILLIS);

        assertEquals(1, gateway.sweep());
        assertEquals(List.of(TraversalRefundReason.EXPIRED), shop.refunds);
        assertFalse(gateway.isOpen(context.traversalId()));
    }

    @Test
    void expiryDispatchesRefundToTheTravelerOwnerInsteadOfTheSweeper() {
        ControlledTravelerExecutor executor = new ControlledTravelerExecutor();
        gateway = gateway(executor);
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        shop.onRefund = (receipt, reason) -> assertTrue(executor.owned.get());
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));
        gateway.evaluate(context);
        clock.addAndGet(TraversalCostGateway.TICKET_TTL_MILLIS);
        executor.owned.set(false);

        assertEquals(1, gateway.sweep());
        assertTrue(shop.refunds.isEmpty());
        assertTrue(gateway.isOpen(context.traversalId()));

        executor.runNextEntity();

        assertEquals(List.of(TraversalRefundReason.EXPIRED), shop.refunds);
        assertFalse(gateway.isOpen(context.traversalId()));
    }

    @Test
    void deferredSuccessfulCommitSurvivesExpiryAndWinsQuitExactlyOnce() {
        ControlledTravelerExecutor executor = new ControlledTravelerExecutor();
        gateway = gateway(executor);
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        shop.onCommit = receipt -> assertTrue(executor.owned.get());
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        Player traveler = player(UUID.randomUUID());
        TraversalCostGateway.Admission admission = gateway.open(localContext(traveler));
        executor.owned.set(false);
        executor.rejectEntity = true;

        assertTrue(admission.deferCommit());
        assertEquals(0, shop.commitCalls.get());
        assertEquals(1, executor.retryTasks.size());
        clock.addAndGet(TraversalCostGateway.TICKET_TTL_MILLIS);
        assertEquals(1, gateway.sweep());
        assertTrue(shop.refunds.isEmpty());

        executor.owned.set(true);
        gateway.travelerQuit(traveler);
        executor.runNextRetry();

        assertEquals(1, shop.commitCalls.get());
        assertTrue(shop.refunds.isEmpty());
        assertFalse(gateway.isOpen(admission.decision().traversalId()));
        assertFalse(admission.deferCommit());
        assertEquals(TraversalSettlement.NOT_OPEN, admission.refund(TraversalRefundReason.TRAVELER_LEFT));
    }

    @Test
    void quitStealsAnAcceptedQueuedCommitWithoutDoubleSettlement() {
        ControlledTravelerExecutor executor = new ControlledTravelerExecutor();
        gateway = gateway(executor);
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        Player traveler = player(UUID.randomUUID());
        TraversalContext context = localContext(traveler);
        gateway.evaluate(context);
        executor.owned.set(false);

        assertEquals(TraversalSettlement.PENDING, gateway.commit(context.traversalId()));
        assertEquals(1, executor.entityTasks.size());

        executor.owned.set(true);
        gateway.travelerQuit(traveler);
        executor.runNextEntity();

        assertEquals(1, shop.commitCalls.get());
        assertTrue(shop.refunds.isEmpty());
        assertEquals(1, sink.entity.size());
    }

    @Test
    void quitRefundsAnOtherwiseUnsettledTicketOnTheTravelerOwner() {
        ControlledTravelerExecutor executor = new ControlledTravelerExecutor();
        gateway = gateway(executor);
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        shop.onRefund = (receipt, reason) -> assertTrue(executor.owned.get());
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        Player traveler = player(UUID.randomUUID());
        TraversalContext context = localContext(traveler);
        gateway.evaluate(context);

        gateway.travelerQuit(traveler);

        assertEquals(List.of(TraversalRefundReason.TRAVELER_LEFT), shop.refunds);
        assertFalse(gateway.isOpen(context.traversalId()));
    }

    @Test
    void aSecondEvaluationForTheSameTravelerIsDeniedWhileATicketIsStillOpen() {
        Player traveler = player(UUID.randomUUID());
        TraversalContext first = localContext(traveler);
        TraversalContext second = localContext(traveler);

        assertTrue(gateway.evaluate(first).allowed());
        TraversalDecision decision = gateway.evaluate(second);

        assertFalse(decision.allowed());
        assertEquals(TraversalOutcome.DENIED_IN_PROGRESS, decision.outcome());

        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(first.traversalId()));
        assertTrue(gateway.evaluate(localContext(traveler)).allowed());
    }

    @Test
    void aReentrantEvaluationIsDeniedWithoutTouchingAnyProvider() {
        AtomicReference<TraversalDecision> nested = new AtomicReference<>();
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        sink.onImmediate = event -> {
            if (event instanceof WormholesPortalTraverseEvent && nested.get() == null) {
                nested.set(gateway.evaluate(localContext(player(UUID.randomUUID()))));
            }
        };

        gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertEquals(TraversalOutcome.DENIED_REENTRANT, nested.get().outcome());
        assertEquals(1, shop.quoteCalls.get());
    }

    @Test
    void aReceiptIsPairedWithTheProviderThatIssuedItAndNeverWithWhatTheReceiptClaims() {
        TestProvider payer = new TestProvider("payer").charging("3 Mana");
        TestProvider liar = new TestProvider("liar").charging("1 Emerald");
        liar.onReserve = (context, quote) -> TraversalReservation.reserved(TraversalReceipt.of("someone-else"));
        registrations.set(List.of(registration(payer, "ManaPlugin"), registration(liar, "LiarPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        TraversalDecision decision = gateway.evaluate(context);

        assertEquals(TraversalOutcome.ALLOWED_CHARGED, decision.outcome());
        assertTrue(payer.refunds.isEmpty(), "nothing a receipt says about itself may roll back a good charge");
        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(context.traversalId()));
        assertEquals(1, payer.commitCalls.get());
        assertEquals(1, liar.commitCalls.get());
    }

    @Test
    void aReceiptThatThrowsFromEveryMethodItInheritsCannotEscapeEvaluateOrStrandACharge() {
        TestProvider payer = new TestProvider("payer").charging("3 Mana");
        TestProvider hostile = new TestProvider("hostile").charging("1 Emerald");
        hostile.onReserve = (context, quote) -> TraversalReservation.reserved(new HostileReceipt());
        registrations.set(List.of(registration(payer, "ManaPlugin"), registration(hostile, "HostilePlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        TraversalDecision decision = gateway.evaluate(context);

        assertEquals(TraversalOutcome.ALLOWED_CHARGED, decision.outcome());
        assertTrue(gateway.isOpen(context.traversalId()),
            "a hostile receipt must never leave a charge outside a ticket");
        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(context.traversalId()));
        assertEquals(1, payer.commitCalls.get());
        assertEquals(1, hostile.commitCalls.get());
        assertTrue(payer.refunds.isEmpty());
    }

    @Test
    void aHostileReceiptIsStillRefundedByExpiryAndByShutdown() {
        TestProvider payer = new TestProvider("payer").charging("3 Mana");
        TestProvider hostile = new TestProvider("hostile").charging("1 Emerald");
        hostile.onReserve = (context, quote) -> TraversalReservation.reserved(new HostileReceipt());
        registrations.set(List.of(registration(payer, "ManaPlugin"), registration(hostile, "HostilePlugin")));

        TraversalContext expiring = localContext(player(UUID.randomUUID()));
        gateway.evaluate(expiring);
        clock.addAndGet(TraversalCostGateway.TICKET_TTL_MILLIS);

        assertEquals(1, gateway.sweep());
        assertEquals(List.of(TraversalRefundReason.EXPIRED), payer.refunds);
        assertEquals(List.of(TraversalRefundReason.EXPIRED), hostile.refunds);

        TraversalContext open = localContext(player(UUID.randomUUID()));
        gateway.evaluate(open);
        gateway.shutdown();

        assertEquals(List.of(TraversalRefundReason.EXPIRED, TraversalRefundReason.SERVER_SHUTDOWN), payer.refunds);
        assertFalse(gateway.isOpen(open.traversalId()));
    }

    @Test
    void aProviderThatQuotesANegativePriceFaultsInsteadOfBreakingTheTraversal() {
        TestProvider broken = new TestProvider("broken");
        broken.onQuote = context -> TraversalQuote.payable("free money").withPrice(-1L, "Mana");
        registrations.set(List.of(registration(broken, "BrokenPlugin")));

        TraversalDecision decision = gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertTrue(decision.allowed());
        assertEquals(TraversalOutcome.ALLOWED_PROVIDER_FAILED, decision.outcome());
        assertEquals(0, broken.reserveCalls.get());
        assertTrue(log.messages().stream().anyMatch(message -> message.contains("failed during quote")
            && message.contains("BrokenPlugin")));
    }

    @Test
    void theSameProviderInjectedTwiceIsOnlyQuotedAndChargedOnce() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        TraversalCostRegistration first = registration(shop, "ManaPlugin");
        registrations.set(List.of(first, first, registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        assertEquals(TraversalOutcome.ALLOWED_CHARGED, gateway.evaluate(context).outcome());

        assertEquals(1, shop.quoteCalls.get());
        assertEquals(1, shop.reserveCalls.get(), "a duplicated registration must never charge a player twice");
        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(context.traversalId()));
        assertEquals(1, shop.commitCalls.get());
    }

    @Test
    void quarantineIsForgottenOnceTheProviderIsNoLongerRegistered() {
        policy.set(TraversalCostPolicy.of(true, "allow", 1, 5L));
        TestProvider broken = new TestProvider("broken");
        broken.onQuote = context -> {
            throw new IllegalStateException("boom");
        };
        registrations.set(List.of(registration(broken, "BrokenPlugin")));

        gateway.evaluate(localContext(player(UUID.randomUUID())));
        gateway.evaluate(localContext(player(UUID.randomUUID())));
        assertEquals(1, broken.quoteCalls.get());

        registrations.set(List.of());
        gateway.evaluate(localContext(player(UUID.randomUUID())));
        registrations.set(List.of(registration(broken, "BrokenPlugin")));
        gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertEquals(2, broken.quoteCalls.get(), "a provider that re-registers starts with a clean slate");
    }

    @Test
    void theReentrancyGuardIsReleasedSoNoRegionThreadKeepsGatewayStateForever() throws Exception {
        gateway.evaluate(localContext(player(UUID.randomUUID())));

        java.lang.reflect.Field field = TraversalCostGateway.class.getDeclaredField("inPipeline");
        field.setAccessible(true);
        ThreadLocal<?> guard = (ThreadLocal<?>) field.get(gateway);

        assertNull(guard.get(), "the pipeline guard must not pin a value on every thread that ever traverses");
    }

    @Test
    void aProviderWhoseOwningPluginIsDisabledIsSkippedEntirely() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin", ServicePriority.Normal, false)));

        TraversalDecision decision = gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertEquals(TraversalOutcome.ALLOWED_FREE, decision.outcome());
        assertEquals(0, shop.quoteCalls.get());
    }

    @Test
    void shutdownRefundsEveryTicketThatIsStillOpen() {
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));

        gateway.evaluate(context);
        gateway.shutdown();

        assertEquals(List.of(TraversalRefundReason.SERVER_SHUTDOWN), shop.refunds);
        assertFalse(gateway.isOpen(context.traversalId()));
    }

    @Test
    void shutdownHonorsDeferredSuccessAndRefundsOnlyTicketsWithoutAnOutcome() {
        ControlledTravelerExecutor executor = new ControlledTravelerExecutor();
        gateway = gateway(executor);
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        shop.onCommit = receipt -> assertTrue(executor.owned.get());
        shop.onRefund = (receipt, reason) -> assertTrue(executor.owned.get());
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalCostGateway.Admission successful = gateway.open(localContext(player(UUID.randomUUID())));
        TraversalContext unfinished = localContext(player(UUID.randomUUID()));
        gateway.evaluate(unfinished);
        executor.owned.set(false);
        executor.rejectEntity = true;
        assertTrue(successful.deferCommit());
        executor.rejectEntity = false;
        executor.runEntityImmediately = true;

        gateway.shutdown();

        assertEquals(1, shop.commitCalls.get());
        assertEquals(List.of(TraversalRefundReason.SERVER_SHUTDOWN), shop.refunds);
        assertFalse(gateway.isOpen(successful.decision().traversalId()));
        assertFalse(gateway.isOpen(unfinished.traversalId()));
    }

    @Test
    void shutdownGraceLetsALateSuccessfulCallbackCommitBeforeFallbackRefund() throws Exception {
        ControlledTravelerExecutor executor = new ControlledTravelerExecutor();
        gateway = gateway(executor);
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        shop.onCommit = receipt -> assertTrue(executor.owned.get());
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalCostGateway.Admission admission = gateway.open(localContext(player(UUID.randomUUID())));
        executor.owned.set(false);
        Thread shutdown = new Thread(gateway::shutdown, "traversal-cost-shutdown-test");

        shutdown.start();
        awaitClosing(gateway);
        assertTrue(admission.deferCommit());
        executor.runNextEntity();
        shutdown.join(3_000L);

        assertFalse(shutdown.isAlive());
        assertEquals(1, shop.commitCalls.get());
        assertTrue(shop.refunds.isEmpty());
        assertFalse(gateway.isOpen(admission.decision().traversalId()));
    }

    @Test
    void shutdownLogsAndAbandonsAReceiptWhenTheOwnerSchedulerCannotAcceptIt() {
        ControlledTravelerExecutor executor = new ControlledTravelerExecutor();
        gateway = gateway(executor);
        TestProvider shop = new TestProvider("shop").charging("3 Mana");
        registrations.set(List.of(registration(shop, "ManaPlugin")));
        TraversalContext context = localContext(player(UUID.randomUUID()));
        gateway.evaluate(context);
        executor.owned.set(false);
        executor.rejectEntity = true;

        gateway.shutdown();

        assertEquals(0, shop.commitCalls.get());
        assertTrue(shop.refunds.isEmpty());
        assertFalse(gateway.isOpen(context.traversalId()));
        assertTrue(log.messagesAt(Level.SEVERE).stream()
            .anyMatch(message -> message.contains("provider receipts remain unresolved")));
    }

    @Test
    void aProviderCanPriceARandomTeleportDifferentlyFromALocalHop() {
        TestProvider pricer = new TestProvider("pricer");
        pricer.onQuote = context -> context.kind() == TraversalKind.RANDOM_TELEPORT
            ? TraversalQuote.payable("10 Mana")
            : TraversalQuote.pass();
        registrations.set(List.of(registration(pricer, "ManaPlugin")));

        TraversalDecision local = gateway.evaluate(localContext(player(UUID.randomUUID())));
        TraversalDecision random = gateway.evaluate(TraversalContext.randomTeleport(player(UUID.randomUUID()),
            UUID.randomUUID(), "Wilds", new Location(null, 0.0D, 64.0D, 0.0D)));

        assertEquals(TraversalOutcome.ALLOWED_FREE, local.outcome());
        assertEquals(TraversalOutcome.ALLOWED_CHARGED, random.outcome());
    }

    @Test
    void aSlowProviderIsWarnedAboutButItsAnswerIsStillHonoured() {
        policy.set(TraversalCostPolicy.of(true, "allow", 5, 5L));
        TestProvider slow = new TestProvider("slow");
        slow.onQuote = context -> {
            clock.addAndGet(20L);
            return TraversalQuote.pass();
        };
        registrations.set(List.of(registration(slow, "SlowPlugin")));

        TraversalDecision decision = gateway.evaluate(localContext(player(UUID.randomUUID())));

        assertEquals(TraversalOutcome.ALLOWED_FREE, decision.outcome());
        assertTrue(log.messages().stream().anyMatch(message -> message.contains("spent 20ms in quote")
            && message.contains("SlowPlugin")));
    }

    @Test
    void theTraverseEventIsFiredExactlyOncePerEvaluationAndCarriesTheContext() {
        TraversalContext context = localContext(player(UUID.randomUUID()));

        gateway.evaluate(context);

        assertEquals(1, sink.immediate.size());
        Event event = sink.immediate.get(0);
        WormholesPortalTraverseEvent traverse = assertInstanceOf(WormholesPortalTraverseEvent.class, event);
        assertEquals(context.traversalId(), traverse.getContext().traversalId());
        assertEquals(TraversalKind.LOCAL, traverse.getContext().kind());
    }

    private TraversalCostGateway gateway(TraversalCostGateway.TravelerExecutor executor) {
        return new TraversalCostGateway(
            registrations::get,
            policy::get,
            sink,
            log.logger(),
            clock::get,
            executor);
    }

    private static void awaitClosing(TraversalCostGateway gateway) throws Exception {
        java.lang.reflect.Field field = TraversalCostGateway.class.getDeclaredField("closing");
        field.setAccessible(true);
        AtomicBoolean closing = (AtomicBoolean) field.get(gateway);
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (!closing.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(closing.get());
    }

    private static final class ControlledTravelerExecutor implements TraversalCostGateway.TravelerExecutor {
        private final AtomicBoolean owned = new AtomicBoolean(true);
        private final Queue<Runnable> entityTasks = new ConcurrentLinkedQueue<>();
        private final Queue<Runnable> retryTasks = new ConcurrentLinkedQueue<>();

        private boolean rejectEntity;
        private boolean runEntityImmediately;

        @Override
        public boolean isOwned(Player traveler) {
            return owned.get();
        }

        @Override
        public boolean dispatch(Player traveler, Runnable task, Runnable retired) {
            if (rejectEntity) {
                retired.run();
                return false;
            }
            if (runEntityImmediately) {
                runOwned(task);
                return true;
            }
            entityTasks.add(task);
            return true;
        }

        @Override
        public boolean retry(Runnable task, long delayTicks) {
            retryTasks.add(task);
            return true;
        }

        private void runNextEntity() {
            Runnable task = entityTasks.remove();
            runOwned(task);
        }

        private void runNextRetry() {
            Runnable task = retryTasks.remove();
            boolean previous = owned.getAndSet(false);
            try {
                task.run();
            } finally {
                owned.set(previous);
            }
        }

        private void runOwned(Runnable task) {
            boolean previous = owned.getAndSet(true);
            try {
                task.run();
            } finally {
                owned.set(previous);
            }
        }
    }

    private static final class HostileReceipt implements TraversalReceipt {
        @Override
        public String toString() {
            throw new IllegalStateException("hostile receipt");
        }

        @Override
        public boolean equals(Object other) {
            throw new IllegalStateException("hostile receipt");
        }

        @Override
        public int hashCode() {
            throw new IllegalStateException("hostile receipt");
        }
    }
}
