package art.arcane.wormholes.api.traversal.internal;

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
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final long SWEEP_INTERVAL_MILLIS = 1_000L;
    private static final long SLOW_WARN_INTERVAL_MILLIS = 60_000L;

    private final Supplier<List<TraversalCostRegistration>> registrations;
    private final Supplier<TraversalCostPolicy> policy;
    private final TraversalEventSink events;
    private final Logger logger;
    private final LongSupplier clock;

    private final ConcurrentMap<UUID, Ticket> tickets = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> openTraversals = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> faults = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> slowWarnedAt = new ConcurrentHashMap<>();
    private final Set<String> quarantined = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastSweep = new AtomicLong();
    private final ThreadLocal<Boolean> inPipeline = new ThreadLocal<>();

    private volatile Listener serviceListener;
    private volatile CleanedRegistrations cleaned;

    public TraversalCostGateway(Supplier<List<TraversalCostRegistration>> registrations,
                                Supplier<TraversalCostPolicy> policy, TraversalEventSink events, Logger logger,
                                LongSupplier clock) {
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.events = Objects.requireNonNull(events, "events");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static TraversalCostGateway bukkit(Plugin plugin, Supplier<TraversalCostPolicy> policy) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(policy, "policy");
        Logger pluginLogger = plugin.getLogger();
        BukkitTraversalCostProviderSource source = new BukkitTraversalCostProviderSource(pluginLogger);
        TraversalCostGateway gateway = new TraversalCostGateway(source, policy,
            new BukkitTraversalEventSink(plugin, pluginLogger), pluginLogger, System::currentTimeMillis);
        TraversalCostServiceListener listener = new TraversalCostServiceListener(source);
        gateway.serviceListener = listener;
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        return gateway;
    }

    public TraversalDecision evaluate(TraversalContext context) {
        Objects.requireNonNull(context, "context");
        TraversalCostPolicy active = activePolicy();

        if (!active.enabled()) {
            return new TraversalDecision(context.traversalId(), TraversalOutcome.DISABLED, "", "");
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

    public TraversalSettlement commit(UUID traversalId) {
        Ticket ticket = claim(traversalId);

        if (ticket == null) {
            return unsettled();
        }

        TraversalCostPolicy active = activePolicy();
        List<String> chargedIds = new ArrayList<>(ticket.charges().size());

        for (ChargedEntry entry : ticket.charges()) {
            guardedCommit(entry, active);
            chargedIds.add(entry.registration().providerId());
        }

        events.fireOnEntity(ticket.context().traveler(),
            new WormholesPortalTraversedEvent(ticket.context(), ticket.outcome(), chargedIds));
        return TraversalSettlement.COMMITTED;
    }

    public TraversalSettlement refund(UUID traversalId, TraversalRefundReason reason) {
        Ticket ticket = claim(traversalId);

        if (ticket == null) {
            return unsettled();
        }

        refundAll(ticket, reason == null ? TraversalRefundReason.TRAVERSAL_ABORTED : reason);
        return TraversalSettlement.REFUNDED;
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
            if (now - ticket.openedAt() < TICKET_TTL_MILLIS) {
                continue;
            }

            if (!tickets.remove(ticket.traversalId(), ticket)) {
                continue;
            }

            openTraversals.remove(ticket.travelerId(), ticket.traversalId());
            WormholesTelemetry.countFailure(FAILURE_TICKET_EXPIRED);
            logger.warning("Traversal cost ticket " + ticket.traversalId() + " was never committed or refunded; "
                + "reclaiming it after " + (now - ticket.openedAt()) + "ms");
            refundAll(ticket, TraversalRefundReason.EXPIRED);
            expired++;
        }

        return expired;
    }

    public void shutdown() {
        Listener listener = serviceListener;

        if (listener != null) {
            HandlerList.unregisterAll(listener);
            serviceListener = null;
        }

        for (Ticket ticket : tickets.values()) {
            if (!tickets.remove(ticket.traversalId(), ticket)) {
                continue;
            }

            openTraversals.remove(ticket.travelerId(), ticket.traversalId());
            refundAll(ticket, TraversalRefundReason.SERVER_SHUTDOWN);
        }
    }

    private TraversalDecision evaluateInternal(TraversalContext context, TraversalCostPolicy active) {
        sweepIfDue();

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

            tickets.put(traversalId,
                new Ticket(traversalId, travelerId, context, outcome, List.copyOf(charged), clock.getAsLong()));
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

    private Ticket claim(UUID traversalId) {
        if (traversalId == null) {
            return null;
        }

        Ticket ticket = tickets.remove(traversalId);

        if (ticket == null) {
            return null;
        }

        openTraversals.remove(ticket.travelerId(), traversalId);
        return ticket;
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
            + "; provider calls run on the portal region thread and must not block");
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

    private record Ticket(UUID traversalId, UUID travelerId, TraversalContext context, TraversalOutcome outcome,
                          List<ChargedEntry> charges, long openedAt) {
    }
}
