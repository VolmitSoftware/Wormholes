package art.arcane.optics.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;




public final class SpoofRegistry<O, R> {
    private static final int[] NO_PASSENGERS = new int[0];
    private static final int NO_LEASH_HOLDER = -1;
    /** Matches the untouched {@link SpoofedEntity#leashedToFakeId} default. */
    private static final int NEVER_LEASHED = Integer.MIN_VALUE;

    private final Map<UUID, SpoofedEntity> spoofed;
    private final Map<Integer, SpoofedEntity> pendingDestroy;
    private final Set<UUID> visible;
    private final Host<O, R> host;
    private final Motion<R> motion = new Motion<>();

    public SpoofRegistry(Host<O, R> host) {
        this.spoofed = new HashMap<UUID, SpoofedEntity>(16);
        this.pendingDestroy = new HashMap<Integer, SpoofedEntity>(4);
        this.visible = new HashSet<UUID>(16);
        this.host = Objects.requireNonNull(host);
    }

    public int size() {
        return spoofed.size() + pendingDestroy.size();
    }

    public boolean contains(UUID sourceId) {
        return spoofed.containsKey(sourceId);
    }

    public Set<UUID> sourceIds() {
        return Set.copyOf(spoofed.keySet());
    }

    public SpoofedEntity get(UUID sourceId) {
        return spoofed.get(sourceId);
    }

    public int livingId(UUID sourceId) {
        SpoofedEntity state = spoofed.get(sourceId);
        return state == null || !state.living ? -1 : state.fakeId;
    }

    public void track(UUID sourceId, SpoofedEntity state) {
        spoofed.put(sourceId, state);
    }

    public void clearVisible() {
        visible.clear();
    }

    public void markVisible(UUID sourceId) {
        visible.add(sourceId);
    }

    public void clear() {
        spoofed.clear();
        pendingDestroy.clear();
        visible.clear();
    }

    public void commitDestroyed() {
        pendingDestroy.clear();
    }

    public void syncMotion(O observer,
                    SpoofedEntity state,
                    SpoofedEntity.Move move,
                    boolean rotationChanged,
                    R position,
                    float yaw,
                    float pitch,
                    boolean onGround) {
        if (!move.moved && !rotationChanged) {
            return;
        }
        motion.kind = move.moved
            ? (move.relative ? (rotationChanged ? MotionKind.RELATIVE_ROTATION : MotionKind.RELATIVE) : MotionKind.TELEPORT)
            : MotionKind.ROTATION;
        motion.entityId = state.fakeId;
        motion.deltaX = move.deltaX;
        motion.deltaY = move.deltaY;
        motion.deltaZ = move.deltaZ;
        motion.position = position;
        motion.yaw = yaw;
        motion.pitch = pitch;
        motion.onGround = onGround;
        host.motion(observer, motion);
    }

    public void syncHeadLook(O observer, SpoofedEntity state, float yaw) {
        host.headLook(observer, state.fakeId, yaw);
    }

    public void applyRelationships(O observer, List<EntitySnapshot> visuals) {
        if (!hasVisualRelationshipWork(visuals)) {
            return;
        }
        List<EntityRelationship> relationships = new ArrayList<EntityRelationship>(visuals.size());
        for (EntitySnapshot visual : visuals) {
            relationships.add(new EntityRelationship(visual.id(), visual.passengerOf(), List.of(), visual.leashHolder()));
        }
        applyRelationships(observer, relationships);
    }

    public void applyRelationships(O observer, Collection<EntityRelationship> relationships) {
        if (!hasRelationshipWork(relationships)) {
            return;
        }
        Map<UUID, List<Integer>> declaredRiders = new HashMap<UUID, List<Integer>>();
        Map<UUID, List<Integer>> inferredRiders = new HashMap<UUID, List<Integer>>();
        for (EntityRelationship relationship : relationships) {
            List<Integer> riders = spoofedFakeIds(relationship.passengerIds());
            if (!riders.isEmpty()) {
                declaredRiders.put(relationship.entityId(), riders);
            }
            UUID vehicle = relationship.vehicleId();
            if (vehicle == null) {
                continue;
            }
            SpoofedEntity rider = spoofed.get(relationship.entityId());
            if (rider == null) {
                continue;
            }
            inferredRiders.computeIfAbsent(vehicle, ignored -> new ArrayList<Integer>()).add(Integer.valueOf(rider.fakeId));
        }
        for (Map.Entry<UUID, SpoofedEntity> entry : spoofed.entrySet()) {
            SpoofedEntity vehicleState = entry.getValue();
            List<Integer> riders = declaredRiders.get(entry.getKey());
            if (riders == null) {
                riders = inferredRiders.get(entry.getKey());
            }
            if (riders == null) {
                if (vehicleState.lastPassengers != null && vehicleState.lastPassengers.length > 0) {
                    vehicleState.lastPassengers = NO_PASSENGERS;
                    host.passengers(observer, vehicleState.fakeId, NO_PASSENGERS);
                }
                continue;
            }
            int[] passengers = new int[riders.size()];
            for (int i = 0; i < passengers.length; i++) {
                passengers[i] = riders.get(i).intValue();
            }
            if (!Arrays.equals(passengers, vehicleState.lastPassengers)) {
                vehicleState.lastPassengers = passengers;
                host.passengers(observer, vehicleState.fakeId, passengers);
            }
        }
        for (EntityRelationship relationship : relationships) {
            SpoofedEntity mob = spoofed.get(relationship.entityId());
            if (mob == null) {
                continue;
            }
            int holderFakeId = NO_LEASH_HOLDER;
            UUID holderUuid = relationship.leashHolderId();
            if (holderUuid != null) {
                SpoofedEntity holder = spoofed.get(holderUuid);
                if (holder != null) {
                    holderFakeId = holder.fakeId;
                }
            }
            int previousHolderFakeId = mob.leashedToFakeId;
            if (previousHolderFakeId == holderFakeId) {
                continue;
            }
            mob.leashedToFakeId = holderFakeId;
            if (previousHolderFakeId == NEVER_LEASHED && holderFakeId == NO_LEASH_HOLDER) {
                continue;
            }
            host.leash(observer, mob.fakeId, holderFakeId);
        }
    }

    private List<Integer> spoofedFakeIds(List<UUID> sourceIds) {
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        List<Integer> fakeIds = new ArrayList<Integer>(sourceIds.size());
        for (UUID sourceId : sourceIds) {
            SpoofedEntity state = spoofed.get(sourceId);
            if (state != null) {
                fakeIds.add(Integer.valueOf(state.fakeId));
            }
        }
        return fakeIds;
    }

    private boolean hasVisualRelationshipWork(List<EntitySnapshot> visuals) {
        for (EntitySnapshot visual : visuals) {
            if (visual.passengerOf() != null || visual.leashHolder() != null) {
                return true;
            }
        }
        return hasTrackedRelationshipState();
    }

    private boolean hasRelationshipWork(Collection<EntityRelationship> relationships) {
        for (EntityRelationship relationship : relationships) {
            if (relationship.vehicleId() != null
                || !relationship.passengerIds().isEmpty()
                || relationship.leashHolderId() != null) {
                return true;
            }
        }
        return hasTrackedRelationshipState();
    }

    private boolean hasTrackedRelationshipState() {
        for (SpoofedEntity state : spoofed.values()) {
            if (state.leashedToFakeId >= 0) {
                return true;
            }
            int[] lastPassengers = state.lastPassengers;
            if (lastPassengers != null && lastPassengers.length > 0) {
                return true;
            }
        }
        return false;
    }

    public void destroyHidden(O observer) {
        if (spoofed.isEmpty()) {
            return;
        }
        List<SpoofedEntity> hiddenStates = new ArrayList<SpoofedEntity>(4);
        Iterator<Map.Entry<UUID, SpoofedEntity>> iterator = spoofed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SpoofedEntity> entry = iterator.next();
            if (visible.contains(entry.getKey())) {
                continue;
            }
            SpoofedEntity state = entry.getValue();
            host.culled(observer, entry.getKey(), state);
            iterator.remove();
            pendingDestroy.put(Integer.valueOf(state.fakeId), state);
            hiddenStates.add(state);
        }
        if (hiddenStates.isEmpty()) {
            return;
        }
        sendDestroyStates(observer, hiddenStates);
    }

    public void destroyAll(O observer) {
        for (SpoofedEntity state : spoofed.values()) {
            pendingDestroy.put(Integer.valueOf(state.fakeId), state);
        }
        spoofed.clear();
        if (pendingDestroy.isEmpty()) {
            return;
        }
        sendDestroyStates(observer, new ArrayList<SpoofedEntity>(pendingDestroy.values()));
    }

    public void destroySingle(O observer, UUID sourceId, SpoofedEntity state) {
        if (sourceId == null || state == null || !spoofed.remove(sourceId, state)) {
            return;
        }
        pendingDestroy.put(Integer.valueOf(state.fakeId), state);
        sendDestroyStates(observer, List.of(state));
    }

    private void sendDestroyStates(O observer, List<SpoofedEntity> states) {
        int idCapacity = states.size() * 2;
        int[] ids = new int[idCapacity];
        List<UUID> playerInfos = new ArrayList<UUID>(Math.min(4, states.size()));
        int count = 0;
        for (SpoofedEntity state : states) {
            ids[count] = state.fakeId;
            count++;
            if (!state.playerEntry) {
                continue;
            }
            ids[count] = state.labelFakeId;
            count++;
            playerInfos.add(state.fakeUuid);
        }
        int[] trimmed = count == ids.length ? ids : Arrays.copyOf(ids, count);
        host.destroy(observer, trimmed);
        if (playerInfos.isEmpty()) {
            return;
        }
        host.removePlayerInfo(observer, playerInfos);
        for (SpoofedEntity state : states) {
            if (state.playerEntry) {
                host.releaseName(observer, state);
            }
        }
    }

    public interface Host<O, R> {
        void motion(O observer, Motion<R> motion);
        void headLook(O observer, int entityId, float yaw);
        void passengers(O observer, int entityId, int[] passengers);
        void leash(O observer, int entityId, int holderId);
        void destroy(O observer, int[] entityIds);
        void removePlayerInfo(O observer, List<UUID> playerIds);
        void releaseName(O observer, SpoofedEntity state);
        void culled(O observer, UUID sourceId, SpoofedEntity state);
    }

    public enum MotionKind {
        RELATIVE, RELATIVE_ROTATION, TELEPORT, ROTATION
    }

    public static final class Motion<R> {
        private MotionKind kind;
        private int entityId;
        private double deltaX;
        private double deltaY;
        private double deltaZ;
        private R position;
        private float yaw;
        private float pitch;
        private boolean onGround;

        public MotionKind kind() { return kind; }
        public int entityId() { return entityId; }
        public double deltaX() { return deltaX; }
        public double deltaY() { return deltaY; }
        public double deltaZ() { return deltaZ; }
        public R position() { return position; }
        public float yaw() { return yaw; }
        public float pitch() { return pitch; }
        public boolean onGround() { return onGround; }
    }
}
