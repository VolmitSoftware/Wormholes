package art.arcane.wormholes.api.traversal.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

public final class TraversalCostRegistrations {
    private TraversalCostRegistrations() {
    }

    public static <C, R extends TraversalCostEngine.Registration<C>> List<R> order(List<R> raw,
                                                        BiConsumer<R, R> duplicateReporter) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        List<R> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingInt((R registration) -> registration.priorityValue())
            .reversed().thenComparing(R::ownerName).thenComparing(R::providerId));
        return dedupe(sorted, duplicateReporter);
    }

    public static <C, R extends TraversalCostEngine.Registration<C>> List<R> dedupe(List<R> raw,
                                                         BiConsumer<R, R> duplicateReporter) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        if (raw.size() == 1) {
            return List.copyOf(raw);
        }

        List<R> deduped = new ArrayList<>(raw.size());
        Set<String> seenIds = new HashSet<>(raw.size());
        Set<TraversalCostProviderIdentity> seenProviders = new HashSet<>(raw.size());

        for (R registration : raw) {
            if (!seenProviders.add(new TraversalCostProviderIdentity(registration.providerIdentity()))) {
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

    private static <C, R extends TraversalCostEngine.Registration<C>> R findById(List<R> kept, String providerId) {
        for (R registration : kept) {
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
