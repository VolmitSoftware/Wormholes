package art.arcane.wormholes.network.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class ViewEntityInterestIndex<K> {
    private final Map<K, Set<UUID>> entitiesBySession;
    private final Map<UUID, Set<K>> sessionsByEntity;
    private boolean closed;

    ViewEntityInterestIndex() {
        entitiesBySession = new HashMap<K, Set<UUID>>();
        sessionsByEntity = new HashMap<UUID, Set<K>>();
    }

    synchronized void activate(K session) {
        if (!closed) {
            entitiesBySession.putIfAbsent(Objects.requireNonNull(session, "session"), Set.of());
        }
    }

    synchronized boolean replace(K session, Set<UUID> entityIds) {
        K requiredSession = Objects.requireNonNull(session, "session");
        Set<UUID> previous = entitiesBySession.get(requiredSession);
        if (closed || previous == null) {
            return false;
        }
        Set<UUID> next = Set.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        if (previous.equals(next)) {
            return false;
        }
        for (UUID entityId : previous) {
            if (!next.contains(entityId)) {
                unlink(entityId, requiredSession);
            }
        }
        for (UUID entityId : next) {
            if (!previous.contains(entityId)) {
                sessionsByEntity.computeIfAbsent(entityId, ignored -> new HashSet<K>()).add(requiredSession);
            }
        }
        entitiesBySession.put(requiredSession, next);
        return true;
    }

    synchronized void retire(K session) {
        Set<UUID> previous = entitiesBySession.remove(session);
        if (previous == null) {
            return;
        }
        for (UUID entityId : previous) {
            unlink(entityId, session);
        }
    }

    synchronized List<K> sessions(UUID entityId) {
        Set<K> sessions = sessionsByEntity.get(entityId);
        return sessions == null || sessions.isEmpty() ? List.of() : new ArrayList<K>(sessions);
    }

    synchronized void close() {
        closed = true;
        entitiesBySession.clear();
        sessionsByEntity.clear();
    }

    synchronized int entityCount() {
        return sessionsByEntity.size();
    }

    synchronized int sessionCount() {
        return entitiesBySession.size();
    }

    private void unlink(UUID entityId, K session) {
        Set<K> sessions = sessionsByEntity.get(entityId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByEntity.remove(entityId);
        }
    }
}
