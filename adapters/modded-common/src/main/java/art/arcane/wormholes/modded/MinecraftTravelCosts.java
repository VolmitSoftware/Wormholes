package art.arcane.wormholes.modded;

import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalOutcome;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.api.traversal.internal.TraversalCostEngine;
import art.arcane.wormholes.api.traversal.internal.TraversalCostPolicy;
import art.arcane.wormholes.api.traversal.internal.TraversalCostRegistrations;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.ExactItemPayment;
import art.arcane.wormholes.portal.OwnerRefundSettlement;
import art.arcane.wormholes.portal.TravelCurrencyAmount;
import art.arcane.wormholes.rules.RuleCostReservation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.logging.Level;

public final class MinecraftTravelCosts {
    private static final Logger LOGGER = Logger.getLogger("Wormholes");
    private static final ConcurrentMap<MinecraftServer, MinecraftTravelCosts> SERVICES = new ConcurrentHashMap<>();

    private final WormholesModRuntime runtime;
    private final LongSupplier clock;
    private final List<Registration> registrations = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<UUID, Admission> pending = new LinkedHashMap<>();
    private final Map<UUID, CachedItem> templates = new HashMap<>();
    private final Map<String, Long> failures = new HashMap<>();
    private List<Registration> ordered = List.of();
    private TraversalCostEngine<ServerPlayer, MinecraftTraversalContext, Registration> engine;
    private MinecraftRuleCostSubject.Currency currency;
    private MinecraftServer server;
    private boolean active;
    private boolean closing;

    public MinecraftTravelCosts(WormholesModRuntime runtime) {
        this(runtime, System::currentTimeMillis);
    }

    MinecraftTravelCosts(WormholesModRuntime runtime, LongSupplier clock) {
        this.runtime = Objects.requireNonNull(runtime);
        this.clock = Objects.requireNonNull(clock);
    }

    public static Optional<MinecraftTravelCosts> forServer(MinecraftServer server) {
        return Optional.ofNullable(SERVICES.get(server));
    }

    public void start() {
        runtime.requireServerThread();
        server = runtime.server();
        active = true;
        closing = false;
        SERVICES.put(server, this);
        engine = new TraversalCostEngine<>(new TraversalCostEngine.Options<>(() -> ordered, this::policy,
            new Events(), LOGGER, clock, new Executor(),
            failure -> failures.merge(failure, 1L, Long::sum)));
    }

    public void tick() {
        runtime.requireServerThread();
        long now = clock.getAsLong();
        for (Admission admission : List.copyOf(pending.values())) {
            if (now >= admission.expiresAt) {
                admission.refund(TraversalRefundReason.EXPIRED);
            }
        }
        engine.sweep();
    }

    public void disconnected(ServerPlayer player) {
        runtime.requireServerThread();
        for (Admission admission : List.copyOf(pending.values())) {
            if (admission.playerId.equals(player.getUUID())) {
                admission.refund(TraversalRefundReason.TRAVELER_LEFT);
            }
        }
        engine.travelerQuit(player);
    }

    public void close() {
        if (!active || closing) {
            return;
        }
        runtime.requireServerThread();
        closing = true;
        for (Admission admission : List.copyOf(pending.values())) {
            admission.refund(TraversalRefundReason.SERVER_SHUTDOWN);
        }
        engine.shutdown();
        active = false;
        SERVICES.remove(server, this);
        pending.clear();
        registrations.clear();
        ordered = List.of();
        templates.clear();
        listeners.clear();
        currency = null;
    }

    public AutoCloseable register(Registration registration) {
        runtime.requireServerThread();
        registrations.add(Objects.requireNonNull(registration));
        ordered = TraversalCostRegistrations.order(registrations, (kept, ignored) ->
            LOGGER.warning("Duplicate traversal cost provider '" + ignored.providerId() + "' from " + ignored.ownerName()));
        return () -> {
            runtime.requireServerThread();
            registrations.remove(registration);
            ordered = TraversalCostRegistrations.order(registrations, null);
        };
    }

    public AutoCloseable listen(Listener listener) {
        runtime.requireServerThread();
        listeners.add(Objects.requireNonNull(listener));
        return () -> {
            runtime.requireServerThread();
            listeners.remove(listener);
        };
    }

    public AutoCloseable currency(MinecraftRuleCostSubject.Currency provider) {
        runtime.requireServerThread();
        if (currency != null) {
            throw new IllegalStateException("A native currency provider is already registered");
        }
        currency = Objects.requireNonNull(provider);
        return () -> {
            runtime.requireServerThread();
            if (currency == provider) {
                currency = null;
            }
        };
    }

    public boolean currencyAvailable() {
        runtime.requireServerThread();
        return currency != null;
    }

    public MinecraftRuleCostSubject ruleSubject(ServerPlayer player) {
        runtime.requireServerThread();
        return new MinecraftRuleCostSubject(player, currency);
    }

    public Admission open(MinecraftTraversalContext context) {
        runtime.requireServerThread();
        if (!active || closing) {
            throw new IllegalStateException("Traversal costs are not running");
        }
        TraversalCostEngine.Admission external = engine.open(context);
        if (!external.allowed()) {
            completed(new MinecraftTraversalCompletion(context, MinecraftTraversalCompletion.Status.DENIED,
                external.decision().outcome(), List.of(), Optional.empty()));
            return new Admission(context, external, null, false);
        }
        MinecraftPortal portal = runtime.portals().get(context.portalId());
        if (portal == null || portal.setting("travelCost") == null) {
            Admission admission = new Admission(context, external, null, true);
            pending.put(context.traversalId(), admission);
            return admission;
        }
        try {
            PriceReservation price = reservePrice(context.traveler(), portal);
            if (price == null) {
                external.refund(TraversalRefundReason.CHARGE_ROLLBACK);
                if (external.decision().outcome() == TraversalOutcome.DISABLED) {
                    refunded(context, external, TraversalRefundReason.CHARGE_ROLLBACK);
                }
                return new Admission(context, external, null, false);
            }
            Admission admission = new Admission(context, external, price, true);
            if (!active || closing || external.decision().outcome() != TraversalOutcome.DISABLED && !engine.isOpen(context.traversalId())) {
                admission.refunded();
                if (external.decision().outcome() == TraversalOutcome.DISABLED) {
                    refunded(context, external, TraversalRefundReason.SERVER_SHUTDOWN);
                }
                return new Admission(context, external, null, false);
            }
            pending.put(context.traversalId(), admission);
            return admission;
        } catch (RuntimeException error) {
            external.refund(TraversalRefundReason.CHARGE_ROLLBACK);
            if (external.decision().outcome() == TraversalOutcome.DISABLED) {
                refunded(context, external, TraversalRefundReason.CHARGE_ROLLBACK);
            }
            throw error;
        }
    }

    public Map<String, Long> failures() {
        runtime.requireServerThread();
        return Map.copyOf(failures);
    }

    private PriceReservation reservePrice(ServerPlayer player, MinecraftPortal portal) {
        if (!(portal.setting("travelCost") instanceof Map<?, ?> price)) {
            throw new IllegalArgumentException("Portal travel cost must be a document");
        }
        String type = Objects.toString(price.get("type"), "");
        if (type.equals("VANILLA")) {
            String encoded = Objects.toString(price.get("item"), "");
            CachedItem cached = templates.get(portal.getId());
            if (cached == null || !cached.encoded().equals(encoded)) {
                cached = new CachedItem(encoded, MinecraftItemEncoding.decode(encoded, server.registryAccess()).copyWithCount(1));
                templates.put(portal.getId(), cached);
            }
            ItemStack template = cached.item();
            int quantity = ExactItemPayment.clampQuantity(price.get("quantity") instanceof Number number ? number.intValue() : 1);
            if (!ExactItemPayment.take(new MinecraftExactItemInventory(player), template, quantity)) {
                player.sendSystemMessage(runtime.localization().text(player, WormholesMessages.PORTAL_COST_INSUFFICIENT,
                    Map.of("quantity", quantity, "item", template.getHoverName().getString())));
                return null;
            }
            OwnerRefundSettlement<ServerPlayer> settlement = new OwnerRefundSettlement<>(new OwnerRefundSettlement.Options<>(
                player, player.getUUID(), new RefundExecutor(), owned -> {
                    ExactItemPayment.restore(new MinecraftExactItemInventory(owned), template, quantity);
                    return true;
                }, "native portal item cost", LOGGER));
            return new PriceReservation(settlement, null);
        }
        if (type.equals("VAULT")) {
            BigDecimal amount = TravelCurrencyAmount.parse(Objects.toString(price.get("amount"), ""));
            if (currency == null) {
                player.sendSystemMessage(runtime.localization().text(player, WormholesMessages.PORTAL_COST_VAULT_UNAVAILABLE, Map.of()));
                return null;
            }
            if (!currency.canAfford(player, amount)) {
                player.sendSystemMessage(runtime.localization().text(player, WormholesMessages.PORTAL_COST_VAULT_INSUFFICIENT,
                    Map.of("amount", amount.toPlainString())));
                return null;
            }
            RuleCostReservation.CurrencyCharge charge = currency.withdraw(player, amount);
            if (charge == null) {
                player.sendSystemMessage(runtime.localization().text(player, WormholesMessages.PORTAL_COST_TRANSACTION_FAILED, Map.of()));
                return null;
            }
            OwnerRefundSettlement<ServerPlayer> settlement = new OwnerRefundSettlement<>(new OwnerRefundSettlement.Options<>(
                player, player.getUUID(), new RefundExecutor(), owned -> { charge.refund(); return true; },
                "native portal currency cost", LOGGER));
            return new PriceReservation(settlement, charge);
        }
        throw new IllegalArgumentException("Unknown portal travel cost type: " + type);
    }

    private TraversalCostPolicy policy() {
        MainConfig config = runtime.configuration().settings().getMain();
        return TraversalCostPolicy.of(config.traversalApiEnabled, config.traversalApiProviderFailurePolicy,
            config.traversalApiProviderFaultLimit, config.traversalApiSlowProviderMillis);
    }

    public final class Admission {
        private final MinecraftTraversalContext context;
        private final UUID playerId;
        private final TraversalCostEngine.Admission external;
        private final PriceReservation price;
        private final boolean allowed;
        private final long expiresAt = clock.getAsLong() + TraversalCostEngine.TICKET_TTL_MILLIS;
        private boolean settled;

        private Admission(MinecraftTraversalContext context, TraversalCostEngine.Admission external, PriceReservation price, boolean allowed) {
            this.context = context;
            this.playerId = context.travelerId();
            this.external = external;
            this.price = price;
            this.allowed = allowed;
        }

        public boolean allowed() {
            return allowed;
        }

        public void commit() {
            runtime.requireServerThread();
            if (!allowed || settled) {
                return;
            }
            external.commit();
            if (external.decision().outcome() == TraversalOutcome.DISABLED) {
                committed();
                pending.remove(context.traversalId(), this);
                completed(new MinecraftTraversalCompletion(context, MinecraftTraversalCompletion.Status.COMMITTED,
                    external.decision().outcome(), List.of(), Optional.empty()));
            }
        }

        public void refund(TraversalRefundReason reason) {
            runtime.requireServerThread();
            if (!allowed || settled) {
                return;
            }
            TraversalRefundReason normalized = reason == null ? TraversalRefundReason.TRAVERSAL_ABORTED : reason;
            external.refund(normalized);
            if (external.decision().outcome() == TraversalOutcome.DISABLED) {
                refunded();
                pending.remove(context.traversalId(), this);
                MinecraftTravelCosts.this.refunded(context, external, normalized);
            }
        }

        private void committed() {
            if (settled) {
                return;
            }
            settled = true;
            try {
                if (price != null) {
                    price.commit();
                }
            } catch (RuntimeException error) {
                LOGGER.log(Level.SEVERE, "Could not commit portal travel cost for " + playerId, error);
            }
        }

        private void refunded() {
            if (settled) {
                return;
            }
            settled = true;
            try {
                if (price != null) {
                    price.settlement().refund();
                }
            } catch (RuntimeException error) {
                LOGGER.log(Level.SEVERE, "Could not refund portal travel cost for " + playerId, error);
            }
        }
    }

    public interface Listener {
        String before(MinecraftTraversalContext context);
        void completed(MinecraftTraversalCompletion completion);
    }

    public record Registration(MinecraftTraversalCostProvider provider, String providerId, String ownerName,
                               int priorityValue, BooleanSupplier enabled)
        implements TraversalCostEngine.Registration<MinecraftTraversalContext> {
        public Registration {
            Objects.requireNonNull(provider);
            Objects.requireNonNull(providerId);
            Objects.requireNonNull(ownerName);
            Objects.requireNonNull(enabled);
        }

        @Override public boolean ownerEnabled() { return enabled.getAsBoolean(); }
        @Override public Object providerIdentity() { return provider; }
        @Override public TraversalQuote quote(MinecraftTraversalContext context) { return provider.quote(context); }
        @Override public TraversalReservation reserve(MinecraftTraversalContext context, TraversalQuote quote) { return provider.reserve(context, quote); }
        @Override public void commit(TraversalReceipt receipt) { provider.commit(receipt); }
        @Override public void refund(TraversalReceipt receipt, TraversalRefundReason reason) { provider.refund(receipt, reason); }
    }

    private final class Events implements TraversalCostEngine.Events<ServerPlayer, MinecraftTraversalContext> {
        @Override
        public String before(MinecraftTraversalContext context) {
            for (Listener listener : List.copyOf(listeners)) {
                try {
                    String refusal = listener.before(context);
                    if (refusal != null) {
                        return refusal;
                    }
                } catch (RuntimeException error) {
                    LOGGER.log(Level.WARNING, "Wormholes traversal listener failed before " + context.traversalId(), error);
                }
            }
            return null;
        }

        @Override
        public void committed(ServerPlayer player, TraversalCostEngine.Completion<MinecraftTraversalContext> completion) {
            Admission admission = pending.remove(completion.context().traversalId());
            if (admission != null) {
                admission.committed();
            }
            completed(new MinecraftTraversalCompletion(completion.context(), MinecraftTraversalCompletion.Status.COMMITTED,
                completion.outcome(), completion.chargedProviders(), Optional.empty()));
        }

        @Override
        public void refunded(ServerPlayer player, TraversalCostEngine.Completion<MinecraftTraversalContext> completion, TraversalRefundReason reason) {
            Admission admission = pending.remove(completion.context().traversalId());
            if (admission != null) {
                admission.refunded();
            }
            completed(new MinecraftTraversalCompletion(completion.context(), MinecraftTraversalCompletion.Status.REFUNDED,
                completion.outcome(), List.of(), Optional.of(reason)));
        }
    }

    private void refunded(MinecraftTraversalContext context, TraversalCostEngine.Admission admission, TraversalRefundReason reason) {
        completed(new MinecraftTraversalCompletion(context, MinecraftTraversalCompletion.Status.REFUNDED,
            admission.decision().outcome(), List.of(), Optional.of(reason)));
    }

    private void completed(MinecraftTraversalCompletion completion) {
        for (Listener listener : List.copyOf(listeners)) {
            try {
                listener.completed(completion);
            } catch (RuntimeException error) {
                LOGGER.log(Level.WARNING, "Wormholes traversal listener failed after " + completion.context().traversalId(), error);
            }
        }
    }

    private final class Executor implements TraversalCostEngine.TravelerExecutor<ServerPlayer> {
        @Override public UUID id(ServerPlayer player) { return player.getUUID(); }
        @Override public boolean isOwned(ServerPlayer player) { return server.isSameThread(); }
        @Override public boolean dispatch(ServerPlayer player, Runnable task, Runnable retired) { return runtime.schedule(task, 1); }
        @Override public boolean retry(Runnable task, long delayTicks) { return runtime.schedule(task, delayTicks); }
    }

    private final class RefundExecutor implements OwnerRefundSettlement.Executor<ServerPlayer> {
        @Override public boolean active() { return active; }
        @Override public boolean isOwned(ServerPlayer player) { return server.isSameThread(); }
        @Override public boolean dispatch(ServerPlayer player, Runnable task, Runnable retired) { return runtime.schedule(task, 1); }
        @Override public boolean retry(Runnable task, long delayTicks) { return runtime.schedule(task, delayTicks); }
    }

    private record CachedItem(String encoded, ItemStack item) {
    }

    private record PriceReservation(OwnerRefundSettlement<ServerPlayer> settlement, RuleCostReservation.CurrencyCharge charge) {
        private void commit() {
            if (settlement.commit() && charge != null) {
                charge.commit();
            }
        }
    }
}
