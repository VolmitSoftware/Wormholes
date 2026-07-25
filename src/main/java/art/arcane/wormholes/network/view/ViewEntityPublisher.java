package art.arcane.wormholes.network.view;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.WireMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

final class ViewEntityPublisher {
    private final ViewSessionRegistry registry;
    private final AtomicLong sendCount = new AtomicLong();

    ViewEntityPublisher(ViewSessionRegistry registry) {
        this.registry = registry;
    }

    long sendCount() {
        return sendCount.get();
    }

    void publish(ViewSession session, long entityTick, EntityRateScheduler scheduler,
                 boolean deltaEnabled, Map<UUID, EntityVisual> captured) {
        if (!registry.isSessionCurrent(session)) {
            return;
        }
        session.captureFailureLogged.set(false);
        List<EntityVisual> visuals = new ArrayList<>(captured.values());
        Set<UUID> presentIds = new HashSet<>(captured.keySet());
        List<UUID> presentIdList = new ArrayList<>(presentIds);
        session.sentProfiles.retainAll(presentIds);
        session.lastCapturedSnapshots.keySet().retainAll(presentIds);
        session.blobCaptureStates.keySet().retainAll(presentIds);

        Set<UUID> sidebandAllowed = null;
        List<UUID> sidebandPresentIdList = null;
        for (String peerName : session.peers) {
            boolean sideband = registry.network().isSidebandOnlyPeer(peerName);
            List<UUID> peerPresentIdList;
            if (sideband) {
                Long nextTick = session.sidebandEntityNextTick.get(peerName);
                if (nextTick != null && entityTick < nextTick.longValue()) {
                    continue;
                }
                session.sidebandEntityNextTick.put(peerName, entityTick + ViewServer.SIDEBAND_ENTITY_INTERVAL_TICKS);
                if (sidebandAllowed == null) {
                    sidebandAllowed = nearestEntityIds(session, visuals, ViewServer.SIDEBAND_MAX_ENTITIES);
                    sidebandPresentIdList = new ArrayList<>(sidebandAllowed);
                }
                peerPresentIdList = sidebandPresentIdList;
            } else {
                session.sidebandEntityNextTick.remove(peerName);
                peerPresentIdList = presentIdList;
            }
            Map<UUID, EntitySendState> peerStates = session.sendStatesFor(peerName);
            Set<UUID> peerPresentIds = ViewServer.presentIdsForPeer(sideband, presentIds, sidebandAllowed);
            peerStates.keySet().retainAll(peerPresentIds);
            Boolean previousSideband = session.lastPeerSideband.put(peerName, Boolean.valueOf(sideband));
            if (previousSideband != null && previousSideband.booleanValue() != sideband) {
                for (EntitySendState transitioned : peerStates.values()) {
                    transitioned.requestFull();
                }
            }
            int outboundCapacity = sideband ? Math.min(visuals.size(), ViewServer.SIDEBAND_MAX_ENTITIES) : visuals.size();
            List<EntityVisual> outbound = new ArrayList<>(outboundCapacity);
            for (EntityVisual currentFull : visuals) {
                if (sideband && !sidebandAllowed.contains(currentFull.id())) {
                    continue;
                }
                EntitySendState state = peerStates.computeIfAbsent(currentFull.id(), EntitySendState::new);
                boolean rateAllowsSend = entityTick >= state.getNextEligibleTick();
                if (rateAllowsSend) {
                    double dx = currentFull.x() - session.portalCenterX;
                    double dy = currentFull.y() - session.portalCenterY;
                    double dz = currentFull.z() - session.portalCenterZ;
                    state.setNextEligibleTick(entityTick + scheduler.claimSendInterval((dx * dx) + (dy * dy) + (dz * dz)));
                }
                EntityVisual lastSent = state.getLastSentSnapshot();
                boolean forceFull = !deltaEnabled
                    || state.isForceFullNext()
                    || lastSent == null
                    || (sideband && state.isSidebandFullDue(entityTick, ViewServer.SIDEBAND_FULL_RESYNC_TICKS, ViewServer.SIDEBAND_FULL_RESYNC_JITTER_TICKS));
                if (!rateAllowsSend && !forceFull) {
                    continue;
                }
                if (forceFull) {
                    int sequence = state.allocateSequence();
                    outbound.add(withSequenceAndMode(currentFull, sequence, EntityVisual.MODE_FULL));
                    state.recordSent(currentFull, true, entityTick);
                } else {
                    int mask = EntityDeltaCodec.computeMask(currentFull, lastSent);
                    if (mask == 0) {
                        continue;
                    }
                    int sequence = state.allocateSequence();
                    outbound.add(EntityDeltaCodec.buildDelta(currentFull, lastSent, sequence, mask));
                    state.recordSent(currentFull, false, entityTick);
                }
            }
            Set<UUID> previousPresent = session.lastSentPresentIds.get(peerName);
            boolean presentChanged = previousPresent == null || !previousPresent.equals(peerPresentIds);
            if (Settings.DEBUG && presentChanged && previousPresent != null) {
                Set<UUID> left = new HashSet<>(previousPresent);
                left.removeAll(peerPresentIds);
                Set<UUID> joined = new HashSet<>(peerPresentIds);
                joined.removeAll(previousPresent);
                if (!left.isEmpty() || !joined.isEmpty()) {
                    Wormholes.v("[stream] portal=" + session.portalId + " peer=" + peerName + " present=" + peerPresentIds.size()
                        + (left.isEmpty() ? "" : " LEFT=" + left) + (joined.isEmpty() ? "" : " JOINED=" + joined));
                }
            }
            if (outbound.isEmpty() && !presentChanged) {
                continue;
            }
            WireMessage.ViewEntities message = new WireMessage.ViewEntities(session.portalId, outbound, peerPresentIdList);
            boolean sent = registry.network().send(peerName, message);
            if (sent) {
                session.lastSentPresentIds.put(peerName, peerPresentIds);
                sendCount.addAndGet(outbound.size());
            } else {
                for (EntityVisual failedVisual : outbound) {
                    EntitySendState failedState = peerStates.get(failedVisual.id());
                    if (failedState != null) {
                        failedState.requestFull();
                    }
                }
            }
        }
    }

    void publishEmptyPresence(ViewSession session, Throwable error) {
        if (!registry.isSessionCurrent(session)) {
            return;
        }
        if (session.captureFailureLogged.compareAndSet(false, true)) {
            Wormholes.instance.getLogger().log(Level.WARNING, "Entity view capture failed for portal " + session.portalId, error);
        }
        session.sentProfiles.clear();
        session.lastCapturedSnapshots.clear();
        session.blobCaptureStates.clear();
        session.sidebandEntityNextTick.clear();
        for (String peerName : session.peers) {
            session.sendStatesFor(peerName).clear();
            boolean sent = registry.network().send(peerName, new WireMessage.ViewEntities(session.portalId, List.of(), List.of()));
            if (sent) {
                session.lastSentPresentIds.put(peerName, Set.of());
            } else {
                session.lastSentPresentIds.remove(peerName);
            }
        }
    }

    private static EntityVisual withSequenceAndMode(EntityVisual source, int sequence, byte mode) {
        return new EntityVisual(
            mode,
            sequence,
            source.presentMask(),
            source.id(),
            source.typeKey(),
            source.x(), source.y(), source.z(),
            source.height(),
            source.lookX(), source.lookY(), source.lookZ(),
            source.yaw(), source.pitch(),
            source.velocityX(), source.velocityY(), source.velocityZ(),
            source.onGround(),
            source.playerName(),
            source.textureValue(),
            source.textureSignature(),
            source.passengerOf(),
            source.leashHolder(),
            source.metadata(),
            source.equipment()
        );
    }

    private static Set<UUID> nearestEntityIds(ViewSession session, List<EntityVisual> visuals, int max) {
        Set<UUID> nearest = new HashSet<>(Math.min(visuals.size(), max));
        if (visuals.size() <= max) {
            for (EntityVisual visual : visuals) {
                nearest.add(visual.id());
            }
            return nearest;
        }
        List<EntityVisual> sorted = new ArrayList<>(visuals);
        sorted.sort(Comparator.comparingDouble(visual -> {
            double dx = visual.x() - session.portalCenterX;
            double dy = visual.y() - session.portalCenterY;
            double dz = visual.z() - session.portalCenterZ;
            return (dx * dx) + (dy * dy) + (dz * dz);
        }));
        for (int i = 0; i < max; i++) {
            nearest.add(sorted.get(i).id());
        }
        return nearest;
    }
}
