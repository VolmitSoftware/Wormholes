package art.arcane.wormholes.api.traversal.internal;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalDecision;
import art.arcane.wormholes.api.traversal.TraversalOutcome;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.api.traversal.TraversalReservationStatus;
import art.arcane.wormholes.api.traversal.WormholesPortalTraverseEvent;
import art.arcane.wormholes.api.traversal.WormholesPortalTraversedEvent;
import art.arcane.wormholes.service.WormholesTelemetry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TraversalCostGateway {
    public static final long TICKET_TTL_MILLIS = 30_000L;
    public static final String FAILURE_PROVIDER_FAULT = "TRAVERSAL_COST_PROVIDER_FAULT";
    public static final String FAILURE_PROVIDER_QUARANTINED = "TRAVERSAL_COST_PROVIDER_QUARANTINED";
    public static final String FAILURE_REFUND_FAILED = "TRAVERSAL_COST_REFUND_FAILED";
    public static final String FAILURE_TICKET_EXPIRED = "TRAVERSAL_COST_TICKET_EXPIRED";
    public static final String FAILURE_REENTRANT = "TRAVERSAL_COST_REENTRANT";
    public static final String FAILURE_OWNER_REQUIRED = "TRAVERSAL_COST_OWNER_REQUIRED";
    public static final String FAILURE_SETTLEMENT_UNRESOLVED = "TRAVERSAL_COST_SETTLEMENT_UNRESOLVED";

    private static final int MAX_SETTLEMENT_RETRIES = 4;
    private static final long SHUTDOWN_PRODUCER_GRACE_MILLIS = 500L;
    private static final long SHUTDOWN_DRAIN_MILLIS = 2_000L;
    private static final long SWEEP_INTERVAL_MILLIS = 1_000L;
    private static final long SLOW_WARN_INTERVAL_MILLIS = 60_000L;
    private static final int TICKET_IDLE = 0;
    private static final int TICKET_DISPATCHED = 1;
    private static final int TICKET_SETTLING = 2;
    private static final int TICKET_COMPLETE = 3;
    private static final int TICKET_ABANDONED = 4;
    private static final TravelerExecutor DIRECT_EXECUTOR = new TravelerExecutor() {
        @Override
        public boolean isOwned(Player traveler) {
            return true;
        }

        @Override
        public boolean dispatch(Player traveler, Runnable task, Runnable retired) {
            task.run();
            return true;
        }

        @Override
        public boolean retry(Runnable task, long delayTicks) {
            task.run();
            return true;
        }
    };

    private final Supplier<List<TraversalCostRegistration>> registrations;
    private final Supplier<TraversalCostPolicy> policy;
    private final TraversalEventSink events;
    private final Logger logger;
    private final LongSupplier clock;
    private final TravelerExecutor travelerExecutor;
    private final Object lifecycleMonitor = new Object();

    private final ConcurrentMap<UUID, Ticket> tickets = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> openTraversals = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> faults = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> slowWarnedAt = new ConcurrentHashMap<>();
    private final Set<String> quarantined = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong lastSweep = new AtomicLong();
    private final ThreadLocal<Boolean> inPipeline = new ThreadLocal<>();

    private volatile Listener serviceListener;
    private volatile CleanedRegistrations cleaned;
    private int activeEvaluations;

    public TraversalCostGateway(Supplier<List<TraversalCostRegistration>> registrations,
                                Supplier<TraversalCostPolicy> policy, TraversalEventSink events, Logger logger,
                                LongSupplier clock) {
        this(registrations, policy, events, logger, clock, DIRECT_EXECUTOR);
    }

    TraversalCostGateway(Supplier<List<TraversalCostRegistration>> registrations,
                         Supplier<TraversalCostPolicy> policy, TraversalEventSink events, Logger logger,
                         LongSupplier clock, TravelerExecutor travelerExecutor) {
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.events = Objects.requireNonNull(events, "events");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.travelerExecutor = Objects.requireNonNull(travelerExecutor, "travelerExecutor");
    }

    public static TraversalCostGateway bukkit(Plugin plugin, Supplier<TraversalCostPolicy> policy) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(policy, "policy");
        Logger pluginLogger = plugin.getLogger();
        BukkitTraversalCostProviderSource source = new BukkitTraversalCostProviderSource(pluginLogger);
        TraversalCostGateway gateway = new TraversalCostGateway(source, policy,
            new BukkitTraversalEventSink(plugin, pluginLogger), pluginLogger, System::currentTimeMillis,
            new BukkitTravelerExecutor(plugin));
        TraversalCostServiceListener listener = new TraversalCostServiceListener(source, gateway);
        gateway.serviceListener = listener;
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        return gateway;
    }

    public TraversalDecision evaluate(TraversalContext context) {
        Objects.requireNonNull(context, "context");
        sweepIfDue();
        TraversalCostPolicy active = activePolicy();

        if (!active.enabled()) {
            return new TraversalDecision(context.traversalId(), TraversalOutcome.DISABLED, "", "");
        }

        if (!beginEvaluation()) {
            return providerFailed(context.traversalId(), "");
        }

        try {
            return evaluateOwned(context, active);
        } finally {
            endEvaluation();
        }
    }

    public Admission open(TraversalContext context) {
        return new Admission(this, evaluate(context));
    }

    public TraversalSettlement commit(UUID traversalId) {
        return requestSettlement(traversalId, SettlementRequest.committed());
    }

    public TraversalSettlement refund(UUID traversalId, TraversalRefundReason reason) {
        TraversalRefundReason normalized = reason == null ? TraversalRefundReason.TRAVERSAL_ABORTED : reason;
        return requestSettlement(traversalId, SettlementRequest.refunded(normalized));
    }

    public boolean isOpen(UUID traversalId) {
        return traversalId != null && tickets.containsKey(traversalId);
    }

    public int sweep() {
        if (tickets.isEmpty()) {
            return 0;
        }

        long now = clock.getAsLong();
        int expired = 0;

        for (Ticket ticket : tickets.values()) {
            SettlementRequest pending = ticket.request.get();
            if (now - ticket.openedAt() < TICKET_TTL_MILLIS) {
                if (pending != null) {
                    dispatch(ticket);
                }
                continue;
            }

            if (ticket.expired.compareAndSet(false, true)) {
                ticket.retryAttempts.set(0);
                WormholesTelemetry.countFailure(FAILURE_TICKET_EXPIRED);
                logger.warning("Traversal cost ticket " + ticket.traversalId()
                    + " remained unsettled after " + (now - ticket.openedAt())
                    + "ms; retrying its terminal outcome on the traveler entity owner");
                expired++;
            }

            ticket.request.compareAndSet(null, SettlementRequest.refunded(TraversalRefundReason.EXPIRED));
            dispatch(ticket);
        }

        return expired;
    }

    public void shutdown() {
        synchronized (lifecycleMonitor) {
            closing.set(true);
        }
        Listener listener = serviceListener;

        awaitActiveEvaluations();
        awaitProducerOutcomes();
        drainShutdownTickets();

        if (listener != null) {
            HandlerList.unregisterAll(listener);
            serviceListener = null;
        }
    }

    private TraversalDecision evaluateOwned(TraversalContext context, TraversalCostPolicy active) {
        if (!travelerExecutor.isOwned(context.traveler())) {
            WormholesTelemetry.countFailure(FAILURE_OWNER_REQUIRED);
            logger.warning("Refused Wormholes traversal evaluation " + context.traversalId()
                + " outside the traveler entity owner");
            return providerFailed(context.traversalId(), "");
        }

        if (Boolean.TRUE.equals(inPipeline.get())) {
            WormholesTelemetry.countFailure(FAILURE_REENTRANT);
            logger.warning("Refused a reentrant Wormholes traversal evaluation for " + context.traversalId());
            return new TraversalDecision(context.traversalId(), TraversalOutcome.DENIED_REENTRANT, "", "");
        }

        inPipeline.set(Boolean.TRUE);

        try {
            return evaluateInternal(context, active);
        } finally {
            inPipeline.remove();
        }
    }

    private boolean beginEvaluation() {
        synchronized (lifecycleMonitor) {
            if (closing.get()) {
                return false;
            }
            activeEvaluations++;
            return true;
        }
    }

    private void endEvaluation() {
        synchronized (lifecycleMonitor) {
            activeEvaluations--;
            lifecycleMonitor.notifyAll();
        }
    }

    private void awaitActiveEvaluations() {
        if (Boolean.TRUE.equals(inPipeline.get())) {
            return;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_DRAIN_MILLIS);
        int remainingEvaluations;
        synchronized (lifecycleMonitor) {
            while (activeEvaluations > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (activeEvaluations == 0) {
                return;
            }
            remainingEvaluations = activeEvaluations;
        }

        WormholesTelemetry.countFailure(FAILURE_SETTLEMENT_UNRESOLVED);
        logger.severe("Traversal cost shutdown timed out waiting for " + remainingEvaluations
            + " active traveler-owned evaluations");
    }

    private void awaitProducerOutcomes() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_PRODUCER_GRACE_MILLIS);

        while (hasUnrequestedTickets()) {
            for (Ticket ticket : tickets.values()) {
                if (ticket.request.get() != null) {
                    dispatch(ticket);
                }
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }

            synchronized (lifecycleMonitor) {
                if (!hasUnrequestedTickets()) {
                    continue;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private boolean hasUnrequestedTickets() {
        for (Ticket ticket : tickets.values()) {
            if (ticket.request.get() == null) {
                return true;
            }
        }
        return false;
    }

    private void signalLifecycleChange() {
        synchronized (lifecycleMonitor) {
            lifecycleMonitor.notifyAll();
        }
    }

    private TraversalDecision evaluateInternal(TraversalContext context, TraversalCostPolicy active) {
        UUID traversalId = context.traversalId();
        UUID travelerId = context.travelerId();

        if (openTraversals.putIfAbsent(travelerId, traversalId) != null) {
            return new TraversalDecision(traversalId, TraversalOutcome.DENIED_IN_PROGRESS, "", "");
        }

        boolean opened = false;

        try {
            WormholesPortalTraverseEvent event = new WormholesPortalTraverseEvent(context);
            events.fireImmediate(event);

            if (event.isCancelled()) {
                return new TraversalDecision(traversalId, TraversalOutcome.DENIED_BY_LISTENER, event.getCancelReason(),
                    "");
            }

            List<TraversalCostRegistration> usable = usable(registrations.get());
            List<PayableQuote> payable = new ArrayList<>(usable.size());
            boolean faulted = false;
            String faultProviderId = "";

            for (TraversalCostRegistration registration : usable) {
                TraversalQuote quote = callQuote(registration, context, active);

                if (quote == null) {
                    faulted = true;
                    faultProviderId = registration.providerId();

                    if (active.failClosed()) {
                        return providerFailed(traversalId, faultProviderId);
                    }

                    continue;
                }

                switch (quote.status()) {
                    case PASS -> {
                    }
                    case PAYABLE -> payable.add(new PayableQuote(registration, quote));
                    case INSUFFICIENT -> {
                        return new TraversalDecision(traversalId, TraversalOutcome.DENIED_INSUFFICIENT,
                            quote.description(), registration.providerId());
                    }
                    case DENIED -> {
                        return new TraversalDecision(traversalId, TraversalOutcome.DENIED_BY_PROVIDER,
                            quote.description(), registration.providerId());
                    }
                }
            }

            List<ChargedEntry> charged = new ArrayList<>(payable.size());
            TraversalDecision refusal = null;
            boolean reserveFaulted = false;

            for (PayableQuote entry : payable) {
                TraversalReservation reservation = callReserve(entry.registration(), context, entry.quote(), active);

                if (reservation != null && reservation.status() == TraversalReservationStatus.RESERVED) {
                    charged.add(new ChargedEntry(entry.registration(), reservation.receipt()));
                    continue;
                }

                if (reservation != null) {
                    refusal = new TraversalDecision(traversalId, TraversalOutcome.DENIED_INSUFFICIENT,
                        reservation.reason(), entry.registration().providerId());
                } else {
                    reserveFaulted = true;
                    faultProviderId = entry.registration().providerId();
                }

                rollback(charged, TraversalRefundReason.CHARGE_ROLLBACK, active);
                charged.clear();
                break;
            }

            if (refusal != null) {
                return refusal;
            }

            if (reserveFaulted) {
                faulted = true;

                if (active.failClosed()) {
                    return providerFailed(traversalId, faultProviderId);
                }
            }

            TraversalOutcome outcome = faulted
                ? TraversalOutcome.ALLOWED_PROVIDER_FAILED
                : charged.isEmpty() ? TraversalOutcome.ALLOWED_FREE : TraversalOutcome.ALLOWED_CHARGED;

            Ticket ticket = new Ticket(
                traversalId, travelerId, context, outcome, List.copyOf(charged), clock.getAsLong());
            boolean closingNow;
            synchronized (lifecycleMonitor) {
                closingNow = closing.get();
                if (!closingNow) {
                    tickets.put(traversalId, ticket);
                }
            }
            if (closingNow) {
                rollback(charged, TraversalRefundReason.SERVER_SHUTDOWN, active);
                return providerFailed(traversalId, "");
            }
            opened = true;
            return new TraversalDecision(traversalId, outcome, "", faulted ? faultProviderId : "");
        } finally {
            if (!opened) {
                openTraversals.remove(travelerId, traversalId);
            }
        }
    }

    private TraversalSettlement unsettled() {
        return activePolicy().enabled() ? TraversalSettlement.NOT_OPEN : TraversalSettlement.DISABLED;
    }

    private TraversalDecision providerFailed(UUID traversalId, String providerId) {
        return new TraversalDecision(traversalId, TraversalOutcome.DENIED_PROVIDER_FAILED, "", providerId);
    }

    private TraversalSettlement requestSettlement(UUID traversalId, SettlementRequest request) {
        if (traversalId == null) {
            return unsettled();
        }

        Ticket ticket = tickets.get(traversalId);

        if (ticket == null || !ticket.request.compareAndSet(null, request)) {
            return unsettled();
        }

        signalLifecycleChange();
        dispatch(ticket);
        if (!ticket.providerSettled.get()) {
            return TraversalSettlement.PENDING;
        }
        return request.commit() ? TraversalSettlement.COMMITTED : TraversalSettlement.REFUNDED;
    }

    private boolean deferCommit(UUID traversalId) {
        if (traversalId == null) {
            return false;
        }

        Ticket ticket = tickets.get(traversalId);
        if (ticket == null || !ticket.request.compareAndSet(null, SettlementRequest.committed())) {
            return false;
        }

        signalLifecycleChange();
        dispatch(ticket);
        return true;
    }

    void travelerJoined(Player traveler) {
        Ticket ticket = ticketFor(traveler);
        if (ticket == null) {
            return;
        }

        ticket.traveler.set(traveler);
        ticket.retryAttempts.set(0);
        if (ticket.request.get() != null) {
            settleFromOwnedEvent(ticket);
        }
    }

    void travelerQuit(Player traveler) {
        Ticket ticket = ticketFor(traveler);
        if (ticket == null) {
            return;
        }

        ticket.traveler.set(traveler);
        if (ticket.request.compareAndSet(null, SettlementRequest.refunded(TraversalRefundReason.TRAVELER_LEFT))) {
            signalLifecycleChange();
        }
        settleFromOwnedEvent(ticket);
    }

    private Ticket ticketFor(Player traveler) {
        if (traveler == null) {
            return null;
        }

        UUID travelerId = traveler.getUniqueId();
        UUID traversalId = openTraversals.get(travelerId);
        return traversalId == null ? null : tickets.get(traversalId);
    }

    private void settleFromOwnedEvent(Ticket ticket) {
        Player traveler = ticket.traveler.get();
        if (!travelerExecutor.isOwned(traveler)) {
            dispatch(ticket);
            return;
        }

        int state = ticket.state.get();
        while (state == TICKET_IDLE || state == TICKET_DISPATCHED) {
            if (ticket.state.compareAndSet(state, TICKET_SETTLING)) {
                completeSettlement(ticket, traveler);
                return;
            }
            state = ticket.state.get();
        }
    }

    private boolean dispatch(Ticket ticket) {
        if (ticket.request.get() == null || tickets.get(ticket.traversalId()) != ticket) {
            return false;
        }

        Player traveler = ticket.traveler.get();
        if (travelerExecutor.isOwned(traveler)) {
            if (!ticket.state.compareAndSet(TICKET_IDLE, TICKET_SETTLING)) {
                return false;
            }
            completeSettlement(ticket, traveler);
            return true;
        }

        if (!ticket.state.compareAndSet(TICKET_IDLE, TICKET_DISPATCHED)) {
            return false;
        }

        boolean accepted = travelerExecutor.dispatch(
            traveler,
            () -> completeDispatchedSettlement(ticket),
            () -> settlementRetired(ticket));
        if (!accepted) {
            settlementRetired(ticket);
        }
        return accepted;
    }

    private void completeDispatchedSettlement(Ticket ticket) {
        Player traveler = ticket.traveler.get();
        if (!travelerExecutor.isOwned(traveler)) {
            settlementRetired(ticket);
            WormholesTelemetry.countFailure(FAILURE_OWNER_REQUIRED);
            logger.warning("Traveler settlement for " + ticket.traversalId()
                + " was dispatched outside the entity owner and will be retried");
            return;
        }

        if (!ticket.state.compareAndSet(TICKET_DISPATCHED, TICKET_SETTLING)) {
            return;
        }
        completeSettlement(ticket, traveler);
    }

    private void completeSettlement(Ticket ticket, Player traveler) {
        SettlementRequest request = ticket.request.get();
        if (request == null) {
            releaseSettlement(ticket);
            return;
        }

        try {
            if (request.commit()) {
                commitAll(ticket, traveler);
            } else {
                refundAll(ticket, request.refundReason());
            }
            ticket.providerSettled.set(true);
        } finally {
            ticket.state.set(TICKET_COMPLETE);
            tickets.remove(ticket.traversalId(), ticket);
            openTraversals.remove(ticket.travelerId(), ticket.traversalId());
            ticket.completion.complete(null);
        }
    }

    private void commitAll(Ticket ticket, Player traveler) {
        TraversalCostPolicy active = activePolicy();
        List<String> chargedIds = new ArrayList<>(ticket.charges().size());

        for (ChargedEntry entry : ticket.charges()) {
            guardedCommit(entry, active);
            chargedIds.add(entry.registration().providerId());
        }

        try {
            events.fireOnEntity(traveler,
                new WormholesPortalTraversedEvent(ticket.context(), ticket.outcome(), chargedIds));
        } catch (Throwable error) {
            logger.log(Level.WARNING, "Failed to dispatch traversal completion event for " + ticket.traversalId(),
                error);
        }
    }

    private void releaseSettlement(Ticket ticket) {
        ticket.state.compareAndSet(TICKET_SETTLING, TICKET_IDLE);
    }

    private void settlementRetired(Ticket ticket) {
        if (!ticket.state.compareAndSet(TICKET_DISPATCHED, TICKET_IDLE)) {
            return;
        }
        scheduleRetry(ticket);
    }

    private void scheduleRetry(Ticket ticket) {
        if (closing.get() || ticket.state.get() != TICKET_IDLE || !ticket.retryQueued.compareAndSet(false, true)) {
            return;
        }

        int attempt = ticket.retryAttempts.getAndIncrement();
        if (attempt >= MAX_SETTLEMENT_RETRIES) {
            ticket.retryQueued.set(false);
            return;
        }

        long delayTicks = 1L << attempt;
        boolean accepted = travelerExecutor.retry(() -> {
            ticket.retryQueued.set(false);
            dispatch(ticket);
        }, delayTicks);
        if (!accepted) {
            ticket.retryQueued.set(false);
        }
    }

    private void drainShutdownTickets() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_DRAIN_MILLIS);

        while (!tickets.isEmpty()) {
            List<CompletableFuture<Void>> active = new ArrayList<>(tickets.size());

            for (Ticket ticket : tickets.values()) {
                ticket.request.compareAndSet(null, SettlementRequest.refunded(TraversalRefundReason.SERVER_SHUTDOWN));
                dispatch(ticket);
                int state = ticket.state.get();
                if (state == TICKET_DISPATCHED || state == TICKET_SETTLING) {
                    active.add(ticket.completion);
                }
            }

            if (tickets.isEmpty()) {
                return;
            }
            if (active.isEmpty()) {
                break;
            }

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }

            try {
                CompletableFuture.allOf(active.toArray(CompletableFuture[]::new))
                    .get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                break;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception error) {
                logger.log(Level.WARNING, "Error while draining traversal cost settlements", error);
                break;
            }
        }

        for (Ticket ticket : List.copyOf(tickets.values())) {
            abandon(ticket);
        }
    }

    private void abandon(Ticket ticket) {
        int state = ticket.state.get();
        while (state != TICKET_COMPLETE && state != TICKET_ABANDONED) {
            if (state == TICKET_SETTLING) {
                WormholesTelemetry.countFailure(FAILURE_SETTLEMENT_UNRESOLVED);
                logger.severe("Traversal cost ticket " + ticket.traversalId()
                    + " was still settling on its traveler entity owner when the shutdown wait expired");
                return;
            }
            if (ticket.state.compareAndSet(state, TICKET_ABANDONED)) {
                tickets.remove(ticket.traversalId(), ticket);
                openTraversals.remove(ticket.travelerId(), ticket.traversalId());
                ticket.completion.complete(null);
                WormholesTelemetry.countFailure(FAILURE_SETTLEMENT_UNRESOLVED);
                logger.severe("Could not settle traversal cost ticket " + ticket.traversalId()
                    + " on the traveler entity owner before shutdown; provider receipts remain unresolved");
                return;
            }
            state = ticket.state.get();
        }
    }

    private List<TraversalCostRegistration> usable(List<TraversalCostRegistration> raw) {
        if (raw == null || raw.isEmpty()) {
            quarantined.clear();
            faults.clear();
            cleaned = null;
            return List.of();
        }

        CleanedRegistrations snapshot = cleaned;

        if (snapshot == null || snapshot.source() != raw) {
            snapshot = new CleanedRegistrations(raw, TraversalCostRegistrations.dedupe(raw, null));
            forgetAbsent(snapshot.deduped());
            cleaned = snapshot;
        }

        List<TraversalCostRegistration> deduped = snapshot.deduped();
        List<TraversalCostRegistration> out = new ArrayList<>(deduped.size());

        for (TraversalCostRegistration registration : deduped) {
            if (quarantined.contains(registration.providerId()) || !registration.ownerEnabled()) {
                continue;
            }

            out.add(registration);
        }

        return out;
    }

    private void forgetAbsent(List<TraversalCostRegistration> present) {
        if (quarantined.isEmpty() && faults.isEmpty()) {
            return;
        }

        Set<String> ids = new HashSet<>(present.size());

        for (TraversalCostRegistration registration : present) {
            ids.add(registration.providerId());
        }

        quarantined.retainAll(ids);
        faults.keySet().retainAll(ids);
    }

    private TraversalQuote callQuote(TraversalCostRegistration registration, TraversalContext context,
                                     TraversalCostPolicy active) {
        long started = clock.getAsLong();

        try {
            TraversalQuote quote = registration.provider().quote(context);

            if (quote == null) {
                fault(registration, active, "quote", null, "returned null");
                return null;
            }

            return quote;
        } catch (Throwable error) {
            fault(registration, active, "quote", error, "");
            return null;
        } finally {
            observe(registration, active, "quote", clock.getAsLong() - started);
        }
    }

    private TraversalReservation callReserve(TraversalCostRegistration registration, TraversalContext context,
                                             TraversalQuote quote, TraversalCostPolicy active) {
        long started = clock.getAsLong();

        try {
            TraversalReservation reservation = registration.provider().reserve(context, quote);

            if (reservation == null) {
                fault(registration, active, "reserve", null, "returned null");
            }

            return reservation;
        } catch (Throwable error) {
            fault(registration, active, "reserve", error, "");
            return null;
        } finally {
            observe(registration, active, "reserve", clock.getAsLong() - started);
        }
    }

    private void guardedCommit(ChargedEntry entry, TraversalCostPolicy active) {
        long started = clock.getAsLong();

        try {
            entry.registration().provider().commit(entry.receipt());
        } catch (Throwable error) {
            fault(entry.registration(), active, "commit", error, "");
        } finally {
            observe(entry.registration(), active, "commit", clock.getAsLong() - started);
        }
    }

    private void rollback(List<ChargedEntry> charged, TraversalRefundReason reason, TraversalCostPolicy active) {
        for (int index = charged.size() - 1; index >= 0; index--) {
            guardedRefund(charged.get(index), reason, active);
        }
    }

    private void refundAll(Ticket ticket, TraversalRefundReason reason) {
        rollback(ticket.charges(), reason, activePolicy());
    }

    private void guardedRefund(ChargedEntry entry, TraversalRefundReason reason, TraversalCostPolicy active) {
        TraversalCostRegistration registration = entry.registration();

        if (!registration.ownerEnabled()) {
            logger.warning("Refunding through traversal cost provider '" + registration.providerId()
                + "' whose plugin '" + registration.pluginName() + "' is disabled");
        }

        long started = clock.getAsLong();

        try {
            registration.provider().refund(entry.receipt(), reason);
        } catch (Throwable error) {
            WormholesTelemetry.countFailure(FAILURE_REFUND_FAILED);
            logger.log(Level.WARNING, "Traversal cost provider '" + registration.providerId() + "' from plugin '"
                + registration.pluginName() + "' failed to refund " + reason, error);
            strike(registration, active);
        } finally {
            observe(registration, active, "refund", clock.getAsLong() - started);
        }
    }

    private void fault(TraversalCostRegistration registration, TraversalCostPolicy active, String phase,
                       Throwable error, String detail) {
        WormholesTelemetry.countFailure(FAILURE_PROVIDER_FAULT);
        String message = "Traversal cost provider '" + registration.providerId() + "' from plugin '"
            + registration.pluginName() + "' failed during " + phase
            + (detail == null || detail.isEmpty() ? "" : ": " + detail);

        if (error == null) {
            logger.warning(message);
        } else {
            logger.log(Level.WARNING, message, error);
        }

        strike(registration, active);
    }

    private void strike(TraversalCostRegistration registration, TraversalCostPolicy active) {
        if (!active.quarantineEnabled()) {
            return;
        }

        int count = faults.computeIfAbsent(registration.providerId(), key -> new AtomicInteger()).incrementAndGet();

        if (count < active.providerFaultLimit() || !quarantined.add(registration.providerId())) {
            return;
        }

        WormholesTelemetry.countFailure(FAILURE_PROVIDER_QUARANTINED);
        logger.severe("Disabled traversal cost provider '" + registration.providerId() + "' from plugin '"
            + registration.pluginName() + "' after " + count + " failures");
    }

    private void observe(TraversalCostRegistration registration, TraversalCostPolicy active, String phase,
                         long elapsedMillis) {
        if (!active.watchdogEnabled() || elapsedMillis < active.slowProviderMillis()) {
            return;
        }

        long now = clock.getAsLong();
        AtomicLong last = slowWarnedAt.computeIfAbsent(registration.providerId(), key -> new AtomicLong());
        long previous = last.get();

        if (previous != 0L && now - previous < SLOW_WARN_INTERVAL_MILLIS) {
            return;
        }

        if (!last.compareAndSet(previous, now)) {
            return;
        }

        logger.warning("Traversal cost provider '" + registration.providerId() + "' from plugin '"
            + registration.pluginName() + "' spent " + elapsedMillis + "ms in " + phase
            + "; provider calls run on an owned traversal thread and must not block");
    }

    private void sweepIfDue() {
        if (tickets.isEmpty()) {
            return;
        }

        long now = clock.getAsLong();
        long last = lastSweep.get();

        if (now - last < SWEEP_INTERVAL_MILLIS || !lastSweep.compareAndSet(last, now)) {
            return;
        }

        sweep();
    }

    private TraversalCostPolicy activePolicy() {
        TraversalCostPolicy active = policy.get();
        return active == null ? TraversalCostPolicy.defaults() : active;
    }

    private record CleanedRegistrations(List<TraversalCostRegistration> source,
                                        List<TraversalCostRegistration> deduped) {
    }

    private record PayableQuote(TraversalCostRegistration registration, TraversalQuote quote) {
    }

    private record ChargedEntry(TraversalCostRegistration registration, TraversalReceipt receipt) {
    }

    private record SettlementRequest(boolean commit, TraversalRefundReason refundReason) {
        private static SettlementRequest committed() {
            return new SettlementRequest(true, null);
        }

        private static SettlementRequest refunded(TraversalRefundReason reason) {
            return new SettlementRequest(false, Objects.requireNonNull(reason, "reason"));
        }
    }

    private static final class Ticket {
        private final UUID traversalId;
        private final UUID travelerId;
        private final TraversalContext context;
        private final TraversalOutcome outcome;
        private final List<ChargedEntry> charges;
        private final long openedAt;
        private final AtomicReference<Player> traveler;
        private final AtomicReference<SettlementRequest> request;
        private final AtomicInteger state;
        private final AtomicInteger retryAttempts;
        private final AtomicBoolean retryQueued;
        private final AtomicBoolean expired;
        private final AtomicBoolean providerSettled;
        private final CompletableFuture<Void> completion;

        private Ticket(UUID traversalId, UUID travelerId, TraversalContext context, TraversalOutcome outcome,
                       List<ChargedEntry> charges, long openedAt) {
            this.traversalId = traversalId;
            this.travelerId = travelerId;
            this.context = context;
            this.outcome = outcome;
            this.charges = charges;
            this.openedAt = openedAt;
            this.traveler = new AtomicReference<>(context.traveler());
            this.request = new AtomicReference<>();
            this.state = new AtomicInteger(TICKET_IDLE);
            this.retryAttempts = new AtomicInteger();
            this.retryQueued = new AtomicBoolean();
            this.expired = new AtomicBoolean();
            this.providerSettled = new AtomicBoolean();
            this.completion = new CompletableFuture<>();
        }

        private UUID traversalId() {
            return traversalId;
        }

        private UUID travelerId() {
            return travelerId;
        }

        private TraversalContext context() {
            return context;
        }

        private TraversalOutcome outcome() {
            return outcome;
        }

        private List<ChargedEntry> charges() {
            return charges;
        }

        private long openedAt() {
            return openedAt;
        }
    }

    interface TravelerExecutor {
        boolean isOwned(Player traveler);

        boolean dispatch(Player traveler, Runnable task, Runnable retired);

        boolean retry(Runnable task, long delayTicks);
    }

    private static final class BukkitTravelerExecutor implements TravelerExecutor {
        private final Plugin plugin;

        private BukkitTravelerExecutor(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean isOwned(Player traveler) {
            return FoliaScheduler.isOwnedByCurrentRegion(traveler);
        }

        @Override
        public boolean dispatch(Player traveler, Runnable task, Runnable retired) {
            return FoliaScheduler.runEntity(plugin, traveler, task, 0L, retired);
        }

        @Override
        public boolean retry(Runnable task, long delayTicks) {
            return FoliaScheduler.runGlobal(plugin, task, delayTicks);
        }
    }

    public static final class Admission {
        private final TraversalCostGateway gateway;
        private final TraversalDecision decision;
        private final AtomicInteger settled;

        private Admission(TraversalCostGateway gateway, TraversalDecision decision) {
            this.gateway = gateway;
            this.decision = decision;
            this.settled = new AtomicInteger();
        }

        public TraversalDecision decision() {
            return decision;
        }

        public boolean allowed() {
            return decision.allowed();
        }

        public TraversalSettlement commit() {
            if (!allowed() || !settled.compareAndSet(0, 1)) {
                return TraversalSettlement.NOT_OPEN;
            }
            return gateway.commit(decision.traversalId());
        }

        public boolean deferCommit() {
            if (!allowed() || !settled.compareAndSet(0, 1)) {
                return false;
            }
            return gateway.deferCommit(decision.traversalId());
        }

        public TraversalSettlement refund(TraversalRefundReason reason) {
            if (!allowed() || !settled.compareAndSet(0, 1)) {
                return TraversalSettlement.NOT_OPEN;
            }
            return gateway.refund(decision.traversalId(), reason);
        }
    }
}
