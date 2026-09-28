package art.arcane.wormholes.access;

import java.util.UUID;
import java.util.function.Function;

public final class PortalLookup {
    private static final int ID_PREFIX_LENGTH = 4;

    private PortalLookup() {
    }

    /**
     * Exact id first, then an exact name, then an id prefix. Two portals sharing a name report as
     * ambiguous so the operator falls back to the id.
     */
    public static <P> Match<P> find(String token, Iterable<P> portals, Function<P, UUID> ids, Function<P, String> names) {
        String requested = token == null ? "" : token.trim();
        if (requested.isEmpty()) {
            return new Match<>(null, 0);
        }
        P named = null;
        P prefixed = null;
        int namedMatches = 0;
        int prefixedMatches = 0;
        for (P portal : portals) {
            String id = ids.apply(portal).toString();
            if (id.equalsIgnoreCase(requested)) {
                return new Match<>(portal, 1);
            }
            if (requested.equalsIgnoreCase(names.apply(portal))) {
                named = portal;
                namedMatches++;
            } else if (requested.length() >= ID_PREFIX_LENGTH && id.regionMatches(true, 0, requested, 0, requested.length())) {
                prefixed = portal;
                prefixedMatches++;
            }
        }
        if (namedMatches > 0) {
            return new Match<>(namedMatches == 1 ? named : null, namedMatches);
        }
        return new Match<>(prefixedMatches == 1 ? prefixed : null, prefixedMatches);
    }

    public record Match<P>(P portal, int matches) {
    }
}
