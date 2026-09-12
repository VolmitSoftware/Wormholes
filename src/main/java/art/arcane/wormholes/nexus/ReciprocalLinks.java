package art.arcane.wormholes.nexus;

import art.arcane.wormholes.portal.LocalPortal;

import java.util.Objects;
import java.util.UUID;

/**
 * Two-way links. Pairing points both portals at each other and marks both ends, so destroying or
 * relinking one end tidies up the other instead of leaving a link that goes nowhere.
 */
public final class ReciprocalLinks {
    /** The portal operations pairing needs. Production wiring goes through the portal manager. */
    public interface Linker {
        LocalPortal portal(UUID portalId);

        /** The portal this one currently points at, or null. */
        UUID destinationOf(LocalPortal portal);

        boolean link(LocalPortal from, LocalPortal to);

        void unlink(LocalPortal portal);
    }

    public enum PairResult {
        PAIRED,
        UNPAIRED,
        SAME_PORTAL,
        REFUSED
    }

    private final Linker linker;

    public ReciprocalLinks(Linker linker) {
        this.linker = Objects.requireNonNull(linker, "linker");
    }

    public PairResult pair(LocalPortal first, LocalPortal second) {
        if (first == null || second == null) {
            return PairResult.REFUSED;
        }
        if (first.getId().equals(second.getId())) {
            return PairResult.SAME_PORTAL;
        }
        if (!linker.link(first, second)) {
            return PairResult.REFUSED;
        }
        if (!linker.link(second, first)) {
            linker.unlink(first);
            return PairResult.REFUSED;
        }
        mark(first, true);
        mark(second, true);
        return PairResult.PAIRED;
    }

    public PairResult unpair(LocalPortal first, LocalPortal second) {
        if (first == null || second == null) {
            return PairResult.REFUSED;
        }
        if (first.getId().equals(second.getId())) {
            return PairResult.SAME_PORTAL;
        }
        linker.unlink(second);
        mark(first, false);
        mark(second, false);
        return PairResult.UNPAIRED;
    }

    /** Drops the return link when one end of a pair goes away. */
    public void onPortalDestroyed(LocalPortal portal, NexusPortalExtension extension) {
        if (portal == null || extension == null || !extension.reciprocal()) {
            return;
        }
        UUID destinationId = linker.destinationOf(portal);
        LocalPortal other = destinationId == null ? null : linker.portal(destinationId);
        if (other == null || other.getId().equals(portal.getId())) {
            return;
        }
        NexusPortalExtension otherState = other.extension(NexusPortalExtension.class);
        if (otherState == null || !otherState.reciprocal()) {
            return;
        }
        if (!portal.getId().equals(linker.destinationOf(other))) {
            return;
        }
        linker.unlink(other);
        otherState.setReciprocal(false);
        other.save();
    }

    private static void mark(LocalPortal portal, boolean reciprocal) {
        NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
        if (state == null) {
            return;
        }
        state.setReciprocal(reciprocal);
        portal.save();
    }
}
