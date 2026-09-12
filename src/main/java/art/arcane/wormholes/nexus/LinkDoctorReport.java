package art.arcane.wormholes.nexus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** What {@link LinkDoctor} found. One finding per broken link, in portal order. */
public record LinkDoctorReport(List<Finding> findings) {
    public LinkDoctorReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public enum Kind {
        /** The destination does not point back, and both ends share a network or a return link. */
        ONE_WAY,
        /** The tunnel names a destination that no longer exists. */
        DANGLING,
        /** The destination portal sits in a world the server has not loaded. */
        UNLOADED_WORLD,
        /** A gateway link whose peer has not been reachable for days. */
        PEER_OFFLINE
    }

    /** One problem. Unused components are empty strings or zero, so the message arguments stay uniform. */
    public record Finding(Kind kind, String portal, String destination, String world, String server, long offlineDays) {
        public Finding {
            kind = Objects.requireNonNull(kind, "kind");
            portal = portal == null ? "" : portal;
            destination = destination == null ? "" : destination;
            world = world == null ? "" : world;
            server = server == null ? "" : server;
        }

        static Finding oneWay(String portal, String destination) {
            return new Finding(Kind.ONE_WAY, portal, destination, "", "", 0L);
        }

        static Finding dangling(String portal) {
            return new Finding(Kind.DANGLING, portal, "", "", "", 0L);
        }

        static Finding unloadedWorld(String portal, String world) {
            return new Finding(Kind.UNLOADED_WORLD, portal, "", world, "", 0L);
        }

        static Finding peerOffline(String portal, String server, long days) {
            return new Finding(Kind.PEER_OFFLINE, portal, "", "", server, days);
        }
    }

    public boolean isClean() {
        return findings.isEmpty();
    }

    public List<Finding> of(Kind kind) {
        List<Finding> matching = new ArrayList<>();
        for (Finding finding : findings) {
            if (finding.kind() == kind) {
                matching.add(finding);
            }
        }
        return matching;
    }
}
