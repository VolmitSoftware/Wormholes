package art.arcane.wormholes.api.traversal;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraversalApiContractTest {
    @Test
    void aPureVetoProviderNeedsNothingBeyondQuote() {
        TraversalCostProvider veto = context -> TraversalQuote.denied("Not attuned to this gate");
        TraversalContext context = context();

        assertEquals(TraversalQuoteStatus.DENIED, veto.quote(context).status());
        assertEquals(veto.getClass().getName(), veto.providerId());
        veto.commit(TraversalReceipt.of("anything"));
        veto.refund(TraversalReceipt.of("anything"), TraversalRefundReason.TRAVERSAL_ABORTED);
    }

    @Test
    void aProviderThatQuotesAPriceWithoutImplementingReserveFailsInsteadOfSilentlyPassing() {
        TraversalCostProvider halfBaked = context -> TraversalQuote.payable("3 Mana");

        TraversalReservation reservation = halfBaked.reserve(context(), TraversalQuote.payable("3 Mana"));

        assertFalse(reservation.reserved());
        assertTrue(reservation.reason().contains("does not implement reserve"));
    }

    @Test
    void everyPublicApiTypeStaysFreeOfInternalWormholesTypes() {
        Class<?>[] surface = {
            TraversalCostProvider.class, TraversalContext.class, TraversalDestination.class, TraversalQuote.class,
            TraversalReservation.class, TraversalReceipt.class, TraversalDecision.class, TraversalOutcome.class,
            TraversalKind.class, TraversalQuoteStatus.class, TraversalReservationStatus.class,
            TraversalRefundReason.class, WormholesPortalTraverseEvent.class, WormholesPortalTraversedEvent.class
        };

        for (Class<?> type : surface) {
            assertTrue(Modifier.isPublic(type.getModifiers()), type.getName() + " must be public");

            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }

                assertAllowed(method.getReturnType(), type, method.getName());

                for (Class<?> parameter : method.getParameterTypes()) {
                    assertAllowed(parameter, type, method.getName());
                }
            }
        }
    }

    @Test
    void aQuoteDescriptionIsNeverNullAndIsBoundedSoAHostileProviderCannotFloodAMessage() {
        assertEquals("", TraversalQuote.payable(null).description());
        assertEquals("", TraversalQuote.pass().description());
        assertEquals(128, TraversalQuote.denied("x".repeat(500)).description().length());
        assertEquals("a b", TraversalQuote.denied("a\nb").description());
    }

    @Test
    void aReservedReservationWithoutAReceiptCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class,
            () -> new TraversalReservation(TraversalReservationStatus.RESERVED, null, ""));
        assertThrows(NullPointerException.class, () -> TraversalReservation.reserved(null));
    }

    @Test
    void theReceiptIsAnOpaqueTokenWormholesCanNeverCallBackInto() {
        long callable = Arrays.stream(TraversalReceipt.class.getMethods())
            .filter(method -> Modifier.isAbstract(method.getModifiers()))
            .count();

        assertEquals(0L, callable,
            "a receipt is third-party code held across a tick; every method on it would be a hostile call");
        assertEquals("", ((TraversalReceipt.SimpleReceipt) TraversalReceipt.of(null)).label());
        assertEquals(128, ((TraversalReceipt.SimpleReceipt) TraversalReceipt.of("x".repeat(500))).label().length());
    }

    @Test
    void aQuoteCarriesAnOptionalAmountThatWormholesDisplaysButNeverCharges() {
        TraversalQuote plain = TraversalQuote.payable("3 Mana");
        TraversalQuote priced = TraversalQuote.payable("3 Mana").withPrice(3L, "Mana");

        assertEquals(OptionalLong.empty(), plain.amount());
        assertEquals("", plain.unit());
        assertEquals(3L, priced.amount().orElseThrow());
        assertEquals("Mana", priced.unit());
        assertEquals(TraversalQuoteStatus.PAYABLE, priced.status());
        assertEquals(128, priced.withPrice(3L, "y".repeat(500)).unit().length());
        assertEquals("", new TraversalQuote(TraversalQuoteStatus.PASS, "", OptionalLong.empty(), "Mana").unit());
        assertEquals(OptionalLong.empty(), new TraversalQuote(TraversalQuoteStatus.PASS, "", null, "").amount());
    }

    @Test
    void aNegativeAmountIsRefusedAtConstructionSoNoProviderCanQuoteOne() {
        assertThrows(IllegalArgumentException.class, () -> TraversalQuote.payable("3 Mana").withPrice(-1L, "Mana"));
        assertEquals(0L, TraversalQuote.payable("free").withPrice(0L, "Mana").amount().orElseThrow());
    }

    @Test
    void theContextHandsOutDefensiveCopiesSoOneProviderCannotMutateWhatTheNextOneSees() {
        Location origin = new Location(null, 1.0D, 64.0D, 2.0D);
        Location target = new Location(null, 9.0D, 64.0D, 9.0D);
        TraversalContext context = TraversalContext.local(player(UUID.randomUUID()), UUID.randomUUID(), "Alpha", origin,
            TraversalDestination.portal(UUID.randomUUID(), "Beta", target));

        context.origin().setX(999.0D);
        context.destination().orElseThrow().location().orElseThrow().setX(999.0D);
        origin.setX(777.0D);

        assertEquals(1.0D, context.origin().getX());
        assertEquals(9.0D, context.destination().orElseThrow().location().orElseThrow().getX());
        assertNotSame(context.origin(), context.origin());
    }

    @Test
    void eachFactoryStampsItsOwnTraversalKindAndAFreshTraversalId() {
        Player traveler = player(UUID.randomUUID());
        Location origin = new Location(null, 0.0D, 64.0D, 0.0D);

        TraversalContext local = TraversalContext.local(traveler, UUID.randomUUID(), "Alpha", origin, null);
        TraversalContext cross = TraversalContext.crossServer(traveler, UUID.randomUUID(), "Alpha", origin,
            TraversalDestination.remotePortal("hub", UUID.randomUUID(), "Beta"));
        TraversalContext random = TraversalContext.randomTeleport(traveler, UUID.randomUUID(), "Wilds", origin);
        TraversalContext door = TraversalContext.dimensionalDoor(traveler, UUID.randomUUID(), "Door", origin, null);

        assertEquals(TraversalKind.LOCAL, local.kind());
        assertEquals(TraversalKind.CROSS_SERVER, cross.kind());
        assertEquals(TraversalKind.RANDOM_TELEPORT, random.kind());
        assertEquals(TraversalKind.DIMENSIONAL_DOOR, door.kind());
        assertEquals(Optional.empty(), random.destination());
        assertEquals(Optional.empty(), local.destination());
        assertFalse(cross.destination().orElseThrow().sameServer());
        assertEquals("hub", cross.destination().orElseThrow().serverName());
        assertNotSame(local.traversalId(), cross.traversalId());
    }

    @Test
    void everyOutcomeAgreesWithItsOwnAllowedFlag() {
        for (TraversalOutcome outcome : TraversalOutcome.values()) {
            boolean denial = outcome.name().startsWith("DENIED_");
            assertEquals(!denial, outcome.allowed(), outcome.name());
            assertEquals(outcome.allowed(),
                new TraversalDecision(UUID.randomUUID(), outcome, "", "").allowed());
        }
    }

    @Test
    void theTwoEventsDoNotShareAHandlerList() {
        assertNotSame(WormholesPortalTraverseEvent.getHandlerList(), WormholesPortalTraversedEvent.getHandlerList());
        assertSame(WormholesPortalTraverseEvent.getHandlerList(),
            new WormholesPortalTraverseEvent(context()).getHandlers());
        assertSame(WormholesPortalTraversedEvent.getHandlerList(),
            new WormholesPortalTraversedEvent(context(), TraversalOutcome.ALLOWED_FREE, null).getHandlers());
    }

    @Test
    void aCancelReasonSuppliedByAListenerIsSanitizedLikeEveryOtherThirdPartyString() {
        WormholesPortalTraverseEvent event = new WormholesPortalTraverseEvent(context());

        event.setCancelReason(null);
        assertEquals("", event.getCancelReason());

        event.setCancelReason("y".repeat(400));
        assertEquals(128, event.getCancelReason().length());
    }

    private static void assertAllowed(Class<?> type, Class<?> owner, String member) {
        Class<?> component = type;

        while (component.isArray()) {
            component = component.getComponentType();
        }

        if (component.isPrimitive()) {
            return;
        }

        String name = component.getName();
        boolean allowed = name.startsWith("java.")
            || name.startsWith("org.bukkit.")
            || name.startsWith("art.arcane.wormholes.api.traversal.");

        assertTrue(allowed, owner.getName() + "#" + member + " leaks " + name + " into the public API");
    }

    private static TraversalContext context() {
        return TraversalContext.local(player(UUID.randomUUID()), UUID.randomUUID(), "Alpha",
            new Location(null, 0.0D, 64.0D, 0.0D), null);
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(TraversalApiContractTest.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "player(" + id + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
