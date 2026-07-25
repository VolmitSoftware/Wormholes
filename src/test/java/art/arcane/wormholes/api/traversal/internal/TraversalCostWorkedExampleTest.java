package art.arcane.wormholes.api.traversal.internal;

import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalOutcome;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.RecordingEventSink;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.localContext;
import static art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.player;
import static art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.registration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraversalCostWorkedExampleTest {
    private final ManaPool pool = new ManaPool();
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final AtomicReference<List<TraversalCostRegistration>> registrations = new AtomicReference<>(List.of());

    private TraversalCostGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new TraversalCostGateway(registrations::get, TraversalCostPolicy::defaults,
            new RecordingEventSink(), TraversalCostTestSupport.logger().logger(), clock::get);
        registrations.set(List.of(registration(new ManaTravelCost(pool), "ExampleManaPlugin")));
    }

    @Test
    void anUnattunedPlayerIsRefusedWithTheProvidersOwnReason() {
        Player traveler = player(UUID.randomUUID());

        assertEquals(TraversalOutcome.DENIED_BY_PROVIDER, gateway.evaluate(localContext(traveler)).outcome());
    }

    @Test
    void anAttunedPlayerWithoutEnoughManaIsToldWhatItCosts() {
        Player traveler = player(UUID.randomUUID());
        pool.attune(traveler.getUniqueId());
        pool.deposit(traveler.getUniqueId(), 1);

        assertEquals(TraversalOutcome.DENIED_INSUFFICIENT, gateway.evaluate(localContext(traveler)).outcome());
        assertEquals(1, pool.balance(traveler.getUniqueId()), "a refused traversal must not move a single point");
    }

    @Test
    void theProvidersQuoteCarriesAnAmountWormholesCanShowWithoutEverOwningThePrice() {
        Player traveler = player(UUID.randomUUID());
        pool.attune(traveler.getUniqueId());
        pool.deposit(traveler.getUniqueId(), 20);
        TraversalQuote quote = new ManaTravelCost(pool).quote(localContext(traveler));

        assertEquals(3L, quote.amount().orElseThrow());
        assertEquals("Mana", quote.unit());
        assertEquals("3 Mana", quote.description());
    }

    @Test
    void aCommittedTraversalKeepsTheManaAndARefundedOneGivesItBack() {
        Player traveler = player(UUID.randomUUID());
        pool.attune(traveler.getUniqueId());
        pool.deposit(traveler.getUniqueId(), 20);

        TraversalContext first = localContext(traveler);
        assertEquals(TraversalOutcome.ALLOWED_CHARGED, gateway.evaluate(first).outcome());
        assertEquals(17, pool.balance(traveler.getUniqueId()));
        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(first.traversalId()));
        assertEquals(17, pool.balance(traveler.getUniqueId()));
        assertEquals(3, pool.spent(traveler.getUniqueId()));

        TraversalContext second = localContext(traveler);
        assertEquals(TraversalOutcome.ALLOWED_CHARGED, gateway.evaluate(second).outcome());
        assertEquals(14, pool.balance(traveler.getUniqueId()));
        assertEquals(TraversalSettlement.REFUNDED,
            gateway.refund(second.traversalId(), TraversalRefundReason.DESTINATION_REJECTED));
        assertEquals(17, pool.balance(traveler.getUniqueId()), "a traversal that did not happen must cost nothing");
        assertEquals(3, pool.spent(traveler.getUniqueId()));
    }

    @Test
    void aRandomTeleportIsPricedHigherThanALocalHopFromTheSameProvider() {
        Player traveler = player(UUID.randomUUID());
        pool.attune(traveler.getUniqueId());
        pool.deposit(traveler.getUniqueId(), 12);

        TraversalContext random = TraversalContext.randomTeleport(traveler, UUID.randomUUID(), "Wilds",
            new Location(null, 0.0D, 64.0D, 0.0D));

        assertEquals(TraversalOutcome.ALLOWED_CHARGED, gateway.evaluate(random).outcome());
        assertEquals(2, pool.balance(traveler.getUniqueId()));
        assertEquals(TraversalSettlement.COMMITTED, gateway.commit(random.traversalId()));

        assertEquals(TraversalOutcome.DENIED_INSUFFICIENT, gateway.evaluate(localContext(traveler)).outcome());
    }

    @Test
    void aPureVetoProviderIsASingleLambda() {
        Set<UUID> owners = ConcurrentHashMap.newKeySet();
        Player owner = player(UUID.randomUUID());
        Player stranger = player(UUID.randomUUID());
        owners.add(owner.getUniqueId());
        TraversalCostProvider claims = context -> owners.contains(context.travelerId())
            ? TraversalQuote.pass()
            : TraversalQuote.denied("This gate belongs to someone else");
        registrations.set(List.of(registration(claims, "ExampleClaimsPlugin")));

        assertTrue(gateway.evaluate(localContext(owner)).allowed());
        assertFalse(gateway.evaluate(localContext(stranger)).allowed());
        assertEquals("This gate belongs to someone else", gateway.evaluate(localContext(stranger)).reason());
    }

    private static final class ManaPool {
        private final Map<UUID, Integer> balances = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> spends = new ConcurrentHashMap<>();
        private final Set<UUID> attuned = ConcurrentHashMap.newKeySet();

        void attune(UUID playerId) {
            attuned.add(playerId);
        }

        boolean isAttuned(UUID playerId) {
            return attuned.contains(playerId);
        }

        int balance(UUID playerId) {
            return balances.getOrDefault(playerId, 0);
        }

        int spent(UUID playerId) {
            return spends.getOrDefault(playerId, 0);
        }

        void deposit(UUID playerId, int amount) {
            balances.merge(playerId, amount, Integer::sum);
        }

        void recordSpend(UUID playerId, int amount) {
            spends.merge(playerId, amount, Integer::sum);
        }

        boolean withdraw(UUID playerId, int amount) {
            boolean[] taken = new boolean[1];
            balances.compute(playerId, (key, current) -> {
                int balance = current == null ? 0 : current;

                if (balance < amount) {
                    return current;
                }

                taken[0] = true;
                return balance - amount;
            });
            return taken[0];
        }
    }

    private record ManaReceipt(UUID playerId, int amount) implements TraversalReceipt {
    }

    private static final class ManaTravelCost implements TraversalCostProvider {
        static final String ID = "example-mana";

        private final ManaPool pool;

        ManaTravelCost(ManaPool pool) {
            this.pool = pool;
        }

        @Override
        public String providerId() {
            return ID;
        }

        @Override
        public TraversalQuote quote(TraversalContext context) {
            if (!pool.isAttuned(context.travelerId())) {
                return TraversalQuote.denied("You are not attuned to the ley lines");
            }

            int price = priceOf(context);

            if (price <= 0) {
                return TraversalQuote.pass();
            }

            if (pool.balance(context.travelerId()) < price) {
                return TraversalQuote.insufficient(price + " Mana").withPrice(price, "Mana");
            }

            return TraversalQuote.payable(price + " Mana").withPrice(price, "Mana");
        }

        @Override
        public TraversalReservation reserve(TraversalContext context, TraversalQuote quote) {
            UUID playerId = context.travelerId();
            int price = priceOf(context);

            if (!pool.withdraw(playerId, price)) {
                return TraversalReservation.failed("Your mana ran out");
            }

            return TraversalReservation.reserved(new ManaReceipt(playerId, price));
        }

        @Override
        public void commit(TraversalReceipt receipt) {
            if (receipt instanceof ManaReceipt mana) {
                pool.recordSpend(mana.playerId(), mana.amount());
            }
        }

        @Override
        public void refund(TraversalReceipt receipt, TraversalRefundReason reason) {
            if (receipt instanceof ManaReceipt mana) {
                pool.deposit(mana.playerId(), mana.amount());
            }
        }

        private int priceOf(TraversalContext context) {
            return switch (context.kind()) {
                case RANDOM_TELEPORT -> 10;
                case CROSS_SERVER -> 5;
                case LOCAL, DIMENSIONAL_DOOR -> 3;
            };
        }
    }
}
