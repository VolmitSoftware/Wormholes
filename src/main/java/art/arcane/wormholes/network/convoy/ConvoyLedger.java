package art.arcane.wormholes.network.convoy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All-or-nothing bookkeeping for cross-server rigs on either side of the link. A group is admitted only
 * once every member is admitted; a single refusal, denial, or timeout fails the whole group exactly once.
 * Terminal groups are kept briefly so late messages are recognised and ignored.
 */
public final class ConvoyLedger {
    public enum Phase {
        OPEN,
        ADMITTED,
        DISPATCHED,
        COMPLETED,
        FAILED
    }

    public record Group(UUID groupId, UUID playerId, String peerName, List<UUID> members, long openedAtMillis, Phase phase, String reason) {
        public Group {
            members = List.copyOf(members);
        }

        public boolean terminal() {
            return phase == Phase.COMPLETED || phase == Phase.FAILED;
        }
    }

    private static final class Entry {
        private final UUID groupId;
        private final UUID playerId;
        private final String peerName;
        private final List<UUID> members;
        private final long openedAtMillis;
        private final Set<UUID> admitted = new HashSet<UUID>();
        private Phase phase = Phase.OPEN;
        private String reason = "";
        private long closedAtMillis;

        private Entry(UUID groupId, UUID playerId, String peerName, List<UUID> members, long openedAtMillis) {
            this.groupId = groupId;
            this.playerId = playerId;
            this.peerName = peerName;
            this.members = List.copyOf(members);
            this.openedAtMillis = openedAtMillis;
        }

        private Group snapshot() {
            return new Group(groupId, playerId, peerName, members, openedAtMillis, phase, reason);
        }

        private boolean terminal() {
            return phase == Phase.COMPLETED || phase == Phase.FAILED;
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<UUID, Entry>();

    public synchronized Group open(UUID groupId, UUID playerId, String peerName, List<UUID> members, long nowMillis) {
        if (entries.containsKey(groupId)) {
            return null;
        }
        Entry entry = new Entry(groupId, playerId, peerName, members, nowMillis);
        entries.put(groupId, entry);
        return entry.snapshot();
    }

    /** Records one admitted member; the group becomes ADMITTED when every member has been admitted. */
    public synchronized Group admitMember(UUID groupId, UUID memberId) {
        Entry entry = live(groupId);
        if (entry == null) {
            return null;
        }
        entry.admitted.add(memberId);
        if (entry.phase == Phase.OPEN && entry.admitted.containsAll(entry.members)) {
            entry.phase = Phase.ADMITTED;
        }
        return entry.snapshot();
    }

    public synchronized Group admit(UUID groupId) {
        Entry entry = live(groupId);
        if (entry == null) {
            return null;
        }
        if (entry.phase == Phase.OPEN) {
            entry.phase = Phase.ADMITTED;
        }
        return entry.snapshot();
    }

    public synchronized Group dispatch(UUID groupId) {
        Entry entry = live(groupId);
        if (entry == null) {
            return null;
        }
        if (entry.phase == Phase.OPEN || entry.phase == Phase.ADMITTED) {
            entry.phase = Phase.DISPATCHED;
        }
        return entry.snapshot();
    }

    public synchronized Group complete(UUID groupId) {
        return close(groupId, Phase.COMPLETED, "");
    }

    public synchronized Group fail(UUID groupId, String reason) {
        return close(groupId, Phase.FAILED, reason == null ? "" : reason);
    }

    /** A live (non-terminal) group, or null. */
    public synchronized Group find(UUID groupId) {
        Entry entry = live(groupId);
        return entry == null ? null : entry.snapshot();
    }

    public synchronized Group findByPlayer(UUID playerId) {
        for (Entry entry : entries.values()) {
            if (!entry.terminal() && entry.playerId != null && entry.playerId.equals(playerId)) {
                return entry.snapshot();
            }
        }
        return null;
    }

    /** Fails every live group older than {@code ttlMillis}; each group is reported once. */
    public synchronized List<Group> expire(long nowMillis, long ttlMillis) {
        List<Group> expired = new ArrayList<Group>();
        for (Entry entry : entries.values()) {
            if (!entry.terminal() && nowMillis - entry.openedAtMillis >= ttlMillis) {
                entry.phase = Phase.FAILED;
                entry.reason = "timeout";
                entry.closedAtMillis = nowMillis;
                expired.add(entry.snapshot());
            }
        }
        return expired;
    }

    public synchronized List<Group> inFlight() {
        List<Group> live = new ArrayList<Group>();
        for (Entry entry : entries.values()) {
            if (!entry.terminal()) {
                live.add(entry.snapshot());
            }
        }
        return live;
    }

    /** Forgets terminal groups closed longer than {@code retainMillis} ago. */
    public synchronized void prune(long nowMillis, long retainMillis) {
        entries.values().removeIf(entry -> entry.terminal() && nowMillis - entry.closedAtMillis >= retainMillis);
    }

    private Entry live(UUID groupId) {
        Entry entry = entries.get(groupId);
        return entry == null || entry.terminal() ? null : entry;
    }

    private Group close(UUID groupId, Phase phase, String reason) {
        Entry entry = live(groupId);
        if (entry == null) {
            return null;
        }
        entry.phase = phase;
        entry.reason = reason;
        entry.closedAtMillis = System.currentTimeMillis();
        return entry.snapshot();
    }
}
