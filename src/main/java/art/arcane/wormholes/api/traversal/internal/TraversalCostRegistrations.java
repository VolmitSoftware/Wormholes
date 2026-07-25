package art.arcane.wormholes.api.traversal.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

public final class TraversalCostRegistrations {
    private static final Comparator<TraversalCostRegistration> ORDER =
        Comparator.comparingInt((TraversalCostRegistration registration) -> registration.priority().ordinal())
            .reversed()
            .thenComparing(TraversalCostRegistration::pluginName)
            .thenComparing(TraversalCostRegistration::providerId);

    private TraversalCostRegistrations() {
    }

    public static List<TraversalCostRegistration> order(List<TraversalCostRegistration> raw,
                                                        BiConsumer<TraversalCostRegistration, TraversalCostRegistration> duplicateReporter) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        List<TraversalCostRegistration> sorted = new ArrayList<>(raw);
        sorted.sort(ORDER);
        return dedupe(sorted, duplicateReporter);
    }

    public static List<TraversalCostRegistration> dedupe(List<TraversalCostRegistration> raw,
                                                         BiConsumer<TraversalCostRegistration, TraversalCostRegistration> duplicateReporter) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        if (raw.size() == 1) {
            return List.copyOf(raw);
        }

        List<TraversalCostRegistration> deduped = new ArrayList<>(raw.size());
        Set<String> seenIds = new HashSet<>(raw.size());
        Set<TraversalCostProviderIdentity> seenProviders = new HashSet<>(raw.size());

        for (TraversalCostRegistration registration : raw) {
            if (!seenProviders.add(new TraversalCostProviderIdentity(registration.provider()))) {
                continue;
            }

            if (!seenIds.add(registration.providerId())) {
                if (duplicateReporter != null) {
                    duplicateReporter.accept(findById(deduped, registration.providerId()), registration);
                }
                continue;
            }

            deduped.add(registration);
        }

        return List.copyOf(deduped);
    }

    private static TraversalCostRegistration findById(List<TraversalCostRegistration> kept, String providerId) {
        for (TraversalCostRegistration registration : kept) {
            if (registration.providerId().equals(providerId)) {
                return registration;
            }
        }

        return null;
    }

    private record TraversalCostProviderIdentity(Object provider) {
        @Override
        public boolean equals(Object other) {
            return other instanceof TraversalCostProviderIdentity identity && identity.provider == provider;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(provider);
        }
    }
}
