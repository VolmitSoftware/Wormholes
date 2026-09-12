package art.arcane.wormholes.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Resolves an operator's portal argument: an exact name, a case-insensitive name, or an id prefix.
 * Ambiguity is reported, never guessed at.
 */
public final class PortalLocator {
    public static final int MINIMUM_ID_PREFIX = 4;

    /** One portal the operator could have meant. */
    public record Candidate(UUID id, String name) {
    }

    /** {@code id} is null unless exactly one candidate matched; {@code matches} says how many did. */
    public record Resolution(UUID id, int matches) {
        public boolean found() {
            return id != null;
        }

        public boolean ambiguous() {
            return id == null && matches > 1;
        }
    }

    private PortalLocator() {
    }

    public static Resolution resolve(Collection<Candidate> candidates, String query) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return new Resolution(null, 0);
        }
        String trimmed = query.trim();

        List<Candidate> exact = matching(candidates, candidate -> trimmed.equals(candidate.name()));
        if (!exact.isEmpty()) {
            return single(exact);
        }
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        List<Candidate> insensitive = matching(candidates,
            candidate -> candidate.name() != null && candidate.name().toLowerCase(Locale.ROOT).equals(lowered));
        if (!insensitive.isEmpty()) {
            return single(insensitive);
        }
        if (lowered.length() >= MINIMUM_ID_PREFIX) {
            List<Candidate> byId = matching(candidates,
                candidate -> candidate.id().toString().startsWith(lowered));
            if (!byId.isEmpty()) {
                return single(byId);
            }
        }
        return new Resolution(null, 0);
    }

    private static Resolution single(List<Candidate> matches) {
        return matches.size() == 1
            ? new Resolution(matches.get(0).id(), 1)
            : new Resolution(null, matches.size());
    }

    private static List<Candidate> matching(Collection<Candidate> candidates, Match match) {
        List<Candidate> matches = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (match.test(candidate)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private interface Match {
        boolean test(Candidate candidate);
    }
}
