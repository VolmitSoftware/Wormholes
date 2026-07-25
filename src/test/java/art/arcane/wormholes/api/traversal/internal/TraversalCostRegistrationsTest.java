package art.arcane.wormholes.api.traversal.internal;

import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraversalCostRegistrationsTest {
    @Test
    void providersAreOrderedByServicePriorityHighestFirst() {
        TraversalCostRegistration low = registration("low", "LowPlugin", ServicePriority.Low);
        TraversalCostRegistration highest = registration("highest", "HighestPlugin", ServicePriority.Highest);
        TraversalCostRegistration normal = registration("normal", "NormalPlugin", ServicePriority.Normal);

        List<TraversalCostRegistration> ordered =
            TraversalCostRegistrations.order(List.of(low, highest, normal), null);

        assertEquals(List.of("highest", "normal", "low"), ids(ordered));
    }

    @Test
    void tiesAreBrokenByPluginNameThenProviderIdSoTheOrderSurvivesARestart() {
        TraversalCostRegistration zeta = registration("zeta", "Zeta", ServicePriority.Normal);
        TraversalCostRegistration alphaTwo = registration("b", "Alpha", ServicePriority.Normal);
        TraversalCostRegistration alphaOne = registration("a", "Alpha", ServicePriority.Normal);

        List<TraversalCostRegistration> ordered =
            TraversalCostRegistrations.order(List.of(zeta, alphaTwo, alphaOne), null);

        assertEquals(List.of("a", "b", "zeta"), ids(ordered));
    }

    @Test
    void theSameProviderInstanceRegisteredTwiceIsChargedOnlyOnce() {
        TraversalCostProvider provider = context -> TraversalQuote.pass();
        TraversalCostRegistration once =
            new TraversalCostRegistration(provider, "shop", "ManaPlugin", ServicePriority.Normal, () -> true);
        TraversalCostRegistration twice =
            new TraversalCostRegistration(provider, "shop", "ManaPlugin", ServicePriority.High, () -> true);

        List<TraversalCostRegistration> ordered = TraversalCostRegistrations.order(List.of(once, twice), null);

        assertEquals(1, ordered.size());
        assertEquals(ServicePriority.High, ordered.get(0).priority());
    }

    @Test
    void twoDistinctProvidersClaimingOneIdCollapseToTheHigherPriorityOneAndTheDropIsReported() {
        TraversalCostRegistration loud = registration("shop", "LoudPlugin", ServicePriority.Highest);
        TraversalCostRegistration quiet = registration("shop", "QuietPlugin", ServicePriority.Low);
        List<String> reported = new CopyOnWriteArrayList<>();

        List<TraversalCostRegistration> ordered = TraversalCostRegistrations.order(List.of(loud, quiet),
            (kept, dropped) -> reported.add(kept.pluginName() + "<-" + dropped.pluginName()));

        assertEquals(List.of("shop"), ids(ordered));
        assertEquals("LoudPlugin", ordered.get(0).pluginName());
        assertEquals(List.of("LoudPlugin<-QuietPlugin"), reported);
    }

    @Test
    void aProviderWhoseIdChangesBetweenCallsIsStillChargedOnlyOnce() {
        TraversalCostProvider shifty = new ShiftyProvider();
        TraversalCostRegistration first =
            new TraversalCostRegistration(shifty, shifty.providerId(), "ShiftyPlugin", ServicePriority.Normal,
                () -> true);
        TraversalCostRegistration second =
            new TraversalCostRegistration(shifty, shifty.providerId(), "ShiftyPlugin", ServicePriority.Normal,
                () -> true);
        assertNotEquals(first.providerId(), second.providerId());

        List<TraversalCostRegistration> ordered = TraversalCostRegistrations.order(List.of(first, second), null);

        assertEquals(1, ordered.size(), "one provider object must never be able to charge twice in one traversal");
    }

    @Test
    void anEmptyOrMissingRegistrationListYieldsNoProviders() {
        assertTrue(TraversalCostRegistrations.order(null, null).isEmpty());
        assertTrue(TraversalCostRegistrations.order(List.of(), null).isEmpty());
    }

    private static TraversalCostRegistration registration(String providerId, String pluginName,
                                                          ServicePriority priority) {
        return new TraversalCostRegistration(new FreeProvider(), providerId, pluginName, priority, () -> true);
    }

    private static final class FreeProvider implements TraversalCostProvider {
        @Override
        public TraversalQuote quote(TraversalContext context) {
            return TraversalQuote.pass();
        }
    }

    private static final class ShiftyProvider implements TraversalCostProvider {
        private int calls;

        @Override
        public TraversalQuote quote(TraversalContext context) {
            return TraversalQuote.pass();
        }

        @Override
        public String providerId() {
            calls++;
            return "shifty-" + calls;
        }
    }

    private static List<String> ids(List<TraversalCostRegistration> registrations) {
        List<String> ids = new ArrayList<>(registrations.size());

        for (TraversalCostRegistration registration : registrations) {
            ids.add(registration.providerId());
        }

        return ids;
    }
}
