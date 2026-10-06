package art.arcane.wormholes.render.client.session;

import java.util.ArrayList;
import java.util.UUID;
import java.nio.ByteBuffer;

import art.arcane.optics.stream.BrickLightSource;
import art.arcane.wormholes.network.client.EncodedPlate;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.ViewPlate;

final class ClientViewPortalSlot<B> {
    final UUID portalId;
    final int key;
    final boolean standby;
    final UUID childId;
    final UUID contextId;
    final ArrayList<ClientViewPortalSlot<B>> children;

    boolean effects;
    long lastInterestTick;
    long lastEffectTick;
    long geometryStamp;
    ApertureDescriptor baseGeometry;
    ViewPlate<B> observedPlate;
    ClientViewPortalSlot<B> standbySlot;

    volatile ApertureDescriptor geometry;
    volatile PlateTarget<B> target;
    volatile boolean needFullEntities;
    volatile boolean needFullScene;
    volatile boolean failed;
    volatile long missDeadlineNanos;

    boolean laneAttached;
    boolean announced;
    ApertureDescriptor sentGeometry;
    int geometryRevision;
    ViewPlate<B> sentPlate;
    EncodedPlate sentEncoded;
    int plateRevision;
    EncodedPlate awaitingEncoded;
    int awaitingRevision;
    boolean windowOpen;
    int windowSequence;

    ClientViewPortalSlot(UUID portalId, int key, boolean standby) {
        this(portalId, key, standby, null);
    }

    ClientViewPortalSlot(UUID portalId, int key, boolean standby, UUID childId) {
        this.portalId = portalId;
        this.key = key;
        this.standby = standby;
        this.childId = childId;
        this.contextId = childId == null ? portalId : contextId(portalId, childId);
        this.children = new ArrayList<ClientViewPortalSlot<B>>(0);
    }

    static UUID contextId(UUID parent, UUID child) {
        return UUID.nameUUIDFromBytes(ByteBuffer.allocate(32)
            .putLong(parent.getMostSignificantBits()).putLong(parent.getLeastSignificantBits())
            .putLong(child.getMostSignificantBits()).putLong(child.getLeastSignificantBits()).array());
    }

    ClientViewPortalSlot<B> successor(int newKey) {
        ClientViewPortalSlot<B> next = new ClientViewPortalSlot<B>(portalId, newKey, standby, childId);
        next.effects = effects;
        next.lastInterestTick = lastInterestTick;
        next.lastEffectTick = lastEffectTick;
        next.geometryStamp = geometryStamp;
        next.baseGeometry = baseGeometry;
        next.observedPlate = observedPlate;
        next.geometry = baseGeometry;
        next.target = target;
        next.needFullEntities = true;
        next.needFullScene = true;
        return next;
    }

    boolean nestedChild() {
        return childId != null;
    }

    ClientViewPortalSlot<B> child(UUID id) {
        for (int i = 0; i < children.size(); i++) {
            ClientViewPortalSlot<B> child = children.get(i);
            if (child.childId.equals(id)) {
                return child;
            }
        }
        return null;
    }

    boolean awaitingMiss() {
        return awaitingEncoded != null;
    }

    record PlateTarget<B>(ViewPlate<B> plate, BrickLightSource light) {
    }
}
