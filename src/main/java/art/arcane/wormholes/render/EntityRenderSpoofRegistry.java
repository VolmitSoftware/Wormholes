package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.EntityVisual;

final class EntityRenderSpoofRegistry {
    private static final int[] NO_PASSENGERS = new int[0];
    private static final int NO_LEASH_HOLDER = -1;
    /** Matches the untouched {@link EntityRenderSpoofedEntity#leashedToFakeId} default. */
    private static final int NEVER_LEASHED = Integer.MIN_VALUE;

    private final Map<UUID, EntityRenderSpoofedEntity> spoofed;
    private final Map<Integer, EntityRenderSpoofedEntity> pendingDestroy;
    private final Set<UUID> visible;
    private final EntityRenderPacketChannel channel;
    private final EntityRenderPlayerIdentity identity;

    EntityRenderSpoofRegistry(EntityRenderPacketChannel channel, EntityRenderPlayerIdentity identity) {
        this.spoofed = new HashMap<UUID, EntityRenderSpoofedEntity>(16);
        this.pendingDestroy = new HashMap<Integer, EntityRenderSpoofedEntity>(4);
        this.visible = new HashSet<UUID>(16);
        this.channel = channel;
        this.identity = identity;
    }

    int size() {
        return spoofed.size() + pendingDestroy.size();
    }

    boolean contains(UUID sourceId) {
        return spoofed.containsKey(sourceId);
    }

    Set<UUID> sourceIds() {
        return Set.copyOf(spoofed.keySet());
    }

    EntityRenderSpoofedEntity get(UUID sourceId) {
        return spoofed.get(sourceId);
    }

    void track(UUID sourceId, EntityRenderSpoofedEntity state) {
        spoofed.put(sourceId, state);
    }

    void clearVisible() {
        visible.clear();
    }

    void markVisible(UUID sourceId) {
        visible.add(sourceId);
    }

    void clear() {
        spoofed.clear();
        pendingDestroy.clear();
        visible.clear();
    }

    void commitDestroyed() {
        pendingDestroy.clear();
    }

    void syncMotion(Player observer,
                    EntityRenderSpoofedEntity state,
                    EntityRenderSpoofedEntity.Move move,
                    boolean rotationChanged,
                    Vector3d position,
                    float yaw,
                    float pitch,
                    boolean onGround) {
        if (move.moved) {
            if (move.relative) {
                if (rotationChanged) {
                    channel.send(observer,
                        new WrapperPlayServerEntityRelativeMoveAndRotation(state.fakeId, move.deltaX, move.deltaY, move.deltaZ, yaw, pitch, onGround));
                } else {
                    channel.send(observer,
                        new WrapperPlayServerEntityRelativeMove(state.fakeId, move.deltaX, move.deltaY, move.deltaZ, onGround));
                }
                return;
            }
            channel.send(observer,
                new WrapperPlayServerEntityTeleport(state.fakeId, position, yaw, pitch, onGround));
            return;
        }
        if (rotationChanged) {
            channel.send(observer, new WrapperPlayServerEntityRotation(state.fakeId, yaw, pitch, onGround));
        }
    }

    void syncHeadLook(Player observer, EntityRenderSpoofedEntity state, float yaw) {
        channel.send(observer, new WrapperPlayServerEntityHeadLook(state.fakeId, yaw));
    }

    void applyRelationships(Player observer, List<EntityVisual> visuals) {
        if (!hasVisualRelationshipWork(visuals)) {
            return;
        }
        List<EntityRelationship> relationships = new ArrayList<EntityRelationship>(visuals.size());
        for (EntityVisual visual : visuals) {
            relationships.add(new EntityRelationship(visual.id(), visual.passengerOf(), List.of(), visual.leashHolder()));
        }
        applyRelationships(observer, relationships);
    }

    void applyRelationships(Player observer, Collection<EntityRelationship> relationships) {
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
            EntityRenderSpoofedEntity rider = spoofed.get(relationship.entityId());
            if (rider == null) {
                continue;
            }
            inferredRiders.computeIfAbsent(vehicle, ignored -> new ArrayList<Integer>()).add(Integer.valueOf(rider.fakeId));
        }
        for (Map.Entry<UUID, EntityRenderSpoofedEntity> entry : spoofed.entrySet()) {
            EntityRenderSpoofedEntity vehicleState = entry.getValue();
            List<Integer> riders = declaredRiders.get(entry.getKey());
            if (riders == null) {
                riders = inferredRiders.get(entry.getKey());
            }
            if (riders == null) {
                if (vehicleState.lastPassengers != null && vehicleState.lastPassengers.length > 0) {
                    vehicleState.lastPassengers = NO_PASSENGERS;
                    channel.send(observer, new WrapperPlayServerSetPassengers(vehicleState.fakeId, NO_PASSENGERS));
                }
                continue;
            }
            int[] passengers = new int[riders.size()];
            for (int i = 0; i < passengers.length; i++) {
                passengers[i] = riders.get(i).intValue();
            }
            if (!Arrays.equals(passengers, vehicleState.lastPassengers)) {
                vehicleState.lastPassengers = passengers;
                channel.send(observer, new WrapperPlayServerSetPassengers(vehicleState.fakeId, passengers));
            }
        }
        for (EntityRelationship relationship : relationships) {
            EntityRenderSpoofedEntity mob = spoofed.get(relationship.entityId());
            if (mob == null) {
                continue;
            }
            int holderFakeId = NO_LEASH_HOLDER;
            UUID holderUuid = relationship.leashHolderId();
            if (holderUuid != null) {
                EntityRenderSpoofedEntity holder = spoofed.get(holderUuid);
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
            channel.send(observer, new WrapperPlayServerAttachEntity(mob.fakeId, holderFakeId, true));
        }
    }

    private List<Integer> spoofedFakeIds(List<UUID> sourceIds) {
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        List<Integer> fakeIds = new ArrayList<Integer>(sourceIds.size());
        for (UUID sourceId : sourceIds) {
            EntityRenderSpoofedEntity state = spoofed.get(sourceId);
            if (state != null) {
                fakeIds.add(Integer.valueOf(state.fakeId));
            }
        }
        return fakeIds;
    }

    private boolean hasVisualRelationshipWork(List<EntityVisual> visuals) {
        for (EntityVisual visual : visuals) {
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
        for (EntityRenderSpoofedEntity state : spoofed.values()) {
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

    void destroyHidden(Player observer) {
        if (spoofed.isEmpty()) {
            return;
        }
        List<EntityRenderSpoofedEntity> hiddenStates = new ArrayList<EntityRenderSpoofedEntity>(4);
        Iterator<Map.Entry<UUID, EntityRenderSpoofedEntity>> iterator = spoofed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, EntityRenderSpoofedEntity> entry = iterator.next();
            if (visible.contains(entry.getKey())) {
                continue;
            }
            EntityRenderSpoofedEntity state = entry.getValue();
            if (Settings.DEBUG) {
                Wormholes.v("[spoof] CULL " + (state.playerEntry ? "player" : "entity") + " src=" + entry.getKey() + " fakeId=" + state.fakeId + " -> " + observer.getName() + " (no longer in view)");
            }
            iterator.remove();
            pendingDestroy.put(Integer.valueOf(state.fakeId), state);
            hiddenStates.add(state);
        }
        if (hiddenStates.isEmpty()) {
            return;
        }
        sendDestroyStates(observer, hiddenStates);
    }

    void destroyAll(Player observer) {
        for (EntityRenderSpoofedEntity state : spoofed.values()) {
            pendingDestroy.put(Integer.valueOf(state.fakeId), state);
        }
        spoofed.clear();
        if (pendingDestroy.isEmpty()) {
            return;
        }
        sendDestroyStates(observer, new ArrayList<EntityRenderSpoofedEntity>(pendingDestroy.values()));
    }

    void destroySingle(Player observer, UUID sourceId, EntityRenderSpoofedEntity state) {
        if (sourceId == null || state == null || !spoofed.remove(sourceId, state)) {
            return;
        }
        pendingDestroy.put(Integer.valueOf(state.fakeId), state);
        sendDestroyStates(observer, List.of(state));
    }

    private void sendDestroyStates(Player observer, List<EntityRenderSpoofedEntity> states) {
        int idCapacity = states.size() * 2;
        int[] ids = new int[idCapacity];
        List<UUID> playerInfos = new ArrayList<UUID>(Math.min(4, states.size()));
        int count = 0;
        for (EntityRenderSpoofedEntity state : states) {
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
        channel.send(observer, new WrapperPlayServerDestroyEntities(trimmed));
        if (playerInfos.isEmpty()) {
            return;
        }
        channel.send(observer, new WrapperPlayServerPlayerInfoRemove(playerInfos));
        for (EntityRenderSpoofedEntity state : states) {
            if (state.playerEntry) {
                identity.releaseVanillaNametag(observer, state);
            }
        }
    }
}
