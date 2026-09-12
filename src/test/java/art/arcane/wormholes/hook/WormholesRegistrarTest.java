package art.arcane.wormholes.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class WormholesRegistrarTest {
    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void gatesAndResolversPublishSortedByOrderAndClearEmptiesEveryTable() {
        TraversalGate late = gate(TraversalGate.ORDER_DEFAULT);
        TraversalGate access = gate(TraversalGate.ORDER_ACCESS);
        TraversalGate rules = gate(TraversalGate.ORDER_RULES);
        DestinationResolver second = resolver(200);
        DestinationResolver first = resolver(100);
        WormholesRegistrar registrar = new WormholesRegistrar()
            .traversalGate(late).traversalGate(access).traversalGate(rules)
            .destinationResolver(second).destinationResolver(first);

        WormholesHooks.install(registrar);

        assertEquals(List.of(access, rules, late), WormholesHooks.traversalGates());
        assertEquals(List.of(first, second), WormholesHooks.destinationResolvers());
        assertSame(access, WormholesHooks.traversalGates().get(0));

        WormholesHooks.clear();

        assertTrue(WormholesHooks.traversalGates().isEmpty());
        assertTrue(WormholesHooks.destinationResolvers().isEmpty());
        assertTrue(WormholesHooks.traversalObservers().isEmpty());
        assertTrue(WormholesHooks.portalExtensionFactories().isEmpty());
        assertTrue(WormholesHooks.projectionSources().isEmpty());
        assertTrue(WormholesHooks.portalMenuEntries().isEmpty());
    }

    @Test
    void defaultsAreEmptyBeforeAnyInstall() {
        assertTrue(WormholesHooks.traversalGates().isEmpty());
        assertTrue(WormholesHooks.portalMenuEntries().isEmpty());
    }

    private static TraversalGate gate(int order) {
        return new TraversalGate() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public TraversalVerdict evaluate(TraversalAttempt attempt) {
                return TraversalVerdict.ALLOW;
            }
        };
    }

    private static DestinationResolver resolver(int order) {
        return new DestinationResolver() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public art.arcane.wormholes.portal.ITunnel resolve(art.arcane.wormholes.portal.LocalPortal portal,
                                                                org.bukkit.entity.Entity traveler,
                                                                art.arcane.wormholes.portal.ITunnel current) {
                return current;
            }
        };
    }
}
