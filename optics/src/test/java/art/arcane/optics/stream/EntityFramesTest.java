package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import art.arcane.optics.entity.EntityAnimation;
import art.arcane.optics.entity.EntityDeltaCodec;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.entity.EntityProjection;

class EntityFramesTest {
    private static final UUID PORTAL = UUID.nameUUIDFromBytes("portal".getBytes());

    @Test
    void firstFrameIsFullThenOnlyChangesAndPresenceTravel() {
        List<EntitySnapshot> scene = new ArrayList<EntitySnapshot>();
        UUID stand = UUID.randomUUID();
        UUID pig = UUID.randomUUID();
        scene.add(visual(stand, 10.5D, 0.0D));
        scene.add(visual(pig, 12.5D, 0.0D));
        EntityFrames<String> frames = new EntityFrames<String>(scenes(scene, new AtomicInteger()));
        ViewStreamMessage.EntityFrame first = frames.frame("observer", new EntityFrameTarget(PORTAL, 3, true, false), 1L);
        assertNotNull(first);
        assertEquals(3, first.portalKey());
        assertEquals(2, first.entities().size());
        assertTrue(first.entities().stream().allMatch(EntitySnapshot::isFull));
        assertEquals(List.of(stand, pig), first.presentIds());
        assertTrue(first.presence());
        assertNull(frames.frame("observer", new EntityFrameTarget(PORTAL, 3, false, false), 2L), "an unchanged scene sends nothing");
        scene.set(1, visual(pig, 13.0D, 0.0D));
        ViewStreamMessage.EntityFrame moved = frames.frame("observer", new EntityFrameTarget(PORTAL, 3, false, false), 3L);
        assertEquals(1, moved.entities().size());
        EntitySnapshot delta = moved.entities().get(0);
        assertFalse(delta.isFull());
        assertEquals(pig, delta.id());
        assertTrue((delta.presentMask() & EntitySnapshot.FIELD_POSITION) != 0);
        assertEquals(13.0D, EntityDeltaCodec.applyDelta(delta, first.entities().get(1)).x(), 1.0E-3D);
        assertFalse(moved.presence(), "movement alone does not resend the presence set");
        assertTrue(moved.presentIds().isEmpty());
        scene.remove(0);
        ViewStreamMessage.EntityFrame left = frames.frame("observer", new EntityFrameTarget(PORTAL, 3, false, false), 4L);
        assertTrue(left.entities().isEmpty());
        assertTrue(left.presence());
        assertEquals(List.of(pig), left.presentIds());
        assertTrue(moved.entitySeq() > first.entitySeq() && left.entitySeq() > moved.entitySeq());
    }

    @Test
    void eventsUseOpaqueIdentityAndOnlyCurrentVisibleEntitiesReceiveThemOnce() {
        UUID source = UUID.randomUUID();
        UUID opaque = EntityProjection.opaque(123, source);
        List<EntitySnapshot> scene = new ArrayList<>(List.of(visual(opaque, 10.5D, 0)));
        boolean[] visible = {true};
        EntityFrames<String> frames = new EntityFrames<>(new EntityScenes<String>() {
            @Override
            public Object sceneKey(String observer, UUID portal) {
                return portal;
            }

            @Override
            public List<EntitySnapshot> capture(String observer, UUID portal, long tick) {
                return scene;
            }

            @Override
            public UUID projectedId(UUID id) {
                return EntityProjection.opaque(123, id);
            }

            @Override
            public boolean visible(String observer, EntitySnapshot visual) {
                return visible[0];
            }
        });
        frames.frame("observer", new EntityFrameTarget(PORTAL, 7, true, false), 1);
        frames.event(EntityAnimation.animation(source, 3));
        frames.event(EntityAnimation.hurt(source, 179));
        frames.event(EntityAnimation.animation(UUID.randomUUID(), 0));
        frames.frame("observer", new EntityFrameTarget(PORTAL, 7, true, false), 2);
        List<ViewStreamMessage.EntityEvent> events = frames.events("observer", PORTAL, 7);
        assertEquals(2, events.size());
        assertEquals(opaque, events.getFirst().entityId());
        assertEquals(3, events.getFirst().animation());
        assertTrue(events.getLast().hurt());
        assertEquals(179, events.getLast().yaw());
        assertTrue(frames.events("observer", PORTAL, 7).isEmpty());
        frames.frame("observer", new EntityFrameTarget(PORTAL, 7, true, false), 3);
        frames.event(EntityAnimation.animation(source, 0));
        assertTrue(frames.events("observer", PORTAL, 7).getFirst().eventSeq() > events.getLast().eventSeq());
        frames.event(EntityAnimation.hurt(source, 0));
        visible[0] = false;
        assertTrue(frames.events("observer", PORTAL, 7).isEmpty());
        visible[0] = true;
        frames.event(EntityAnimation.animation(source, 0));
        scene.clear();
        frames.frame("observer", new EntityFrameTarget(PORTAL, 7, false, false), 4);
        assertTrue(frames.events("observer", PORTAL, 7).isEmpty());
    }

    @Test
    void observerCopiesReceiveOneEventEachAndLostScenesNeverReplayEvents() {
        UUID source = UUID.randomUUID();
        List<EntitySnapshot> scene = new ArrayList<>(List.of(visual(source, 10.5D, 0)));
        EntityFrames<String> frames = new EntityFrames<>(scenes(scene, new AtomicInteger()));
        frames.frame("a", new EntityFrameTarget(PORTAL, 1, true, false), 1);
        frames.frame("b", new EntityFrameTarget(PORTAL, 2, true, false), 1);
        frames.event(EntityAnimation.animation(source, 0));
        assertEquals(1, frames.events("a", PORTAL, 1).size());
        assertEquals(1, frames.events("b", PORTAL, 2).size());
        assertTrue(frames.events("a", PORTAL, 1).isEmpty());
        frames.event(EntityAnimation.hurt(source, 0));
        frames.frame("a", new EntityFrameTarget(PORTAL, 8, false, false), 2);
        assertTrue(frames.events("a", PORTAL, 8).isEmpty());
        assertEquals(1, frames.events("b", PORTAL, 2).size());
    }

    @Test
    void oneCapturePerTickIsSharedByObserversOfTheSameScene() {
        AtomicInteger captures = new AtomicInteger();
        List<EntitySnapshot> scene = List.of(visual(UUID.randomUUID(), 10.5D, 0.0D));
        EntityFrames<String> frames = new EntityFrames<String>(scenes(scene, captures));
        assertNotNull(frames.frame("a", new EntityFrameTarget(PORTAL, 1, true, false), 7L));
        assertNotNull(frames.frame("b", new EntityFrameTarget(PORTAL, 4, true, false), 7L));
        assertEquals(1, captures.get());
        assertEquals(2, frames.observerStates());
        frames.frame("a", new EntityFrameTarget(PORTAL, 1, false, false), 8L);
        assertEquals(2, captures.get());
    }

    @Test
    void fullRequestAndNewPortalKeyResendEverything() {
        UUID stand = UUID.randomUUID();
        EntityFrames<String> frames = new EntityFrames<String>(scenes(List.of(visual(stand, 10.5D, 0.0D)), new AtomicInteger()));
        frames.frame("observer", new EntityFrameTarget(PORTAL, 1, true, false), 1L);
        ViewStreamMessage.EntityFrame full = frames.frame("observer", new EntityFrameTarget(PORTAL, 1, true, false), 2L);
        assertEquals(1, full.entities().size());
        assertTrue(full.entities().get(0).isFull());
        ViewStreamMessage.EntityFrame successor = frames.frame("observer", new EntityFrameTarget(PORTAL, 9, false, false), 3L);
        assertEquals(9, successor.portalKey());
        assertTrue(successor.entities().get(0).isFull());
    }

    @Test
    void emptyScenesSendNothingUntilEntitiesAppearAndStaleFullRequestsClearTheClient() {
        List<EntitySnapshot> scene = new ArrayList<EntitySnapshot>();
        EntityFrames<String> frames = new EntityFrames<String>(scenes(scene, new AtomicInteger()));
        assertNull(frames.frame("observer", new EntityFrameTarget(PORTAL, 1, true, false), 1L), "an empty first scene needs no frame");
        assertNull(frames.frame("observer", new EntityFrameTarget(PORTAL, 1, true, false), 2L));
        UUID stand = UUID.randomUUID();
        scene.add(visual(stand, 10.5D, 0.0D));
        assertTrue(frames.frame("observer", new EntityFrameTarget(PORTAL, 1, true, false), 3L).entities().get(0).isFull());
        scene.clear();
        ViewStreamMessage.EntityFrame cleared = frames.frame("observer", new EntityFrameTarget(PORTAL, 1, true, false), 4L);
        assertNotNull(cleared, "a full request over a populated client clears it even when the scene is empty");
        assertTrue(cleared.presentIds().isEmpty());
    }

    @Test
    void lostSceneClearsPresenceOnce() {
        List<EntitySnapshot> scene = List.of(visual(UUID.randomUUID(), 10.5D, 0.0D));
        boolean[] visible = {true};
        EntityFrames<String> frames = new EntityFrames<String>(new EntityScenes<String>() {
            @Override
            public Object sceneKey(String observer, UUID portal) {
                return visible[0] ? portal : null;
            }

            @Override
            public List<EntitySnapshot> capture(String observer, UUID portal, long tick) {
                return scene;
            }
        });
        frames.frame("observer", new EntityFrameTarget(PORTAL, 1, true, false), 1L);
        visible[0] = false;
        ViewStreamMessage.EntityFrame cleared = frames.frame("observer", new EntityFrameTarget(PORTAL, 1, false, false), 2L);
        assertTrue(cleared.entities().isEmpty());
        assertTrue(cleared.presentIds().isEmpty());
        assertNull(frames.frame("observer", new EntityFrameTarget(PORTAL, 1, false, false), 3L));
    }

    @Test
    void framesStayInsideTheProtocolCaps() throws ViewStreamProtocolException {
        List<EntitySnapshot> crowd = new ArrayList<EntitySnapshot>();
        for (int i = 0; i < 400; i++) {
            crowd.add(visual(UUID.randomUUID(), i, 0.0D));
        }
        EntityFrames<String> frames = new EntityFrames<String>(scenes(crowd, new AtomicInteger()));
        ViewStreamMessage.EntityFrame first = frames.frame("observer", new EntityFrameTarget(PORTAL, 1, true, false), 1L);
        assertEquals(ViewStreamLimits.MAX_ENTITIES_PER_FRAME, first.entities().size());
        assertEquals(ViewStreamLimits.MAX_ENTITIES_PER_FRAME, first.presentIds().size());
        ViewStreamMessage.EntityFrame second = frames.frame("observer", new EntityFrameTarget(PORTAL, 1, false, false), 2L);
        assertEquals(400 - ViewStreamLimits.MAX_ENTITIES_PER_FRAME, second.entities().size());
        assertEquals(400, second.presentIds().size());
        byte[] encoded = ViewStreamFixtures.CODEC.encodeS2C(second, 1, ViewStreamLimits.FLAG_LAST);
        assertTrue(encoded.length <= ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES);
        ViewStreamMessage.EntityFrame decoded = (ViewStreamMessage.EntityFrame) ViewStreamFixtures.CODEC.decodeS2C(encoded, -1L).message();
        assertEquals(second.entities().size(), decoded.entities().size());
        assertEquals(second.presentIds(), decoded.presentIds());
    }

    @Test
    void hiddenObserversLeaveTheFrameWhileOtherObserversStillSeeThem() {
        UUID self = UUID.randomUUID();
        UUID pig = UUID.randomUUID();
        List<EntitySnapshot> scene = List.of(visual(self, 10.5D, 0.0D), visual(pig, 12.5D, 0.0D));
        EntityFrames<String> frames = new EntityFrames<String>(new EntityScenes<String>() {
            @Override
            public Object sceneKey(String observer, UUID portal) {
                return portal;
            }

            @Override
            public List<EntitySnapshot> capture(String observer, UUID portal, long tick) {
                return scene;
            }

            @Override
            public boolean isObserver(String observer, EntitySnapshot visual) {
                return observer.equals("mirror") && visual.id().equals(self);
            }
        });
        ViewStreamMessage.EntityFrame hidden = frames.frame("mirror", new EntityFrameTarget(PORTAL, 1, true, true), 1L);
        assertEquals(List.of(pig), hidden.presentIds(), "the observer is left out of its own client-drawn mirror");
        ViewStreamMessage.EntityFrame shown = frames.frame("mirror", new EntityFrameTarget(PORTAL, 2, true, false), 1L);
        assertEquals(2, shown.presentIds().size(), "a server-drawn mirror still carries the observer");
        ViewStreamMessage.EntityFrame other = frames.frame("other", new EntityFrameTarget(PORTAL, 1, true, true), 1L);
        assertEquals(2, other.presentIds().size(), "only the observer itself is hidden");
    }

    private static EntityScenes<String> scenes(List<EntitySnapshot> scene, AtomicInteger captures) {
        return new EntityScenes<String>() {
            @Override
            public Object sceneKey(String observer, UUID portal) {
                return portal;
            }

            @Override
            public List<EntitySnapshot> capture(String observer, UUID portal, long tick) {
                captures.incrementAndGet();
                return scene;
            }
        };
    }

    private static EntitySnapshot visual(UUID id, double x, double velocityX) {
        return new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, id, "minecraft:armor_stand", x, 64.0D, 16.5D, 1.975D,
            0.0D, 0.0D, -1.0D, 180.0F, 0.0F, velocityX, 0.0D, 0.0D, true, "", "", "", null, null, new byte[] {1}, EntitySnapshot.EMPTY,
            EntitySnapshot.EMPTY);
    }
}
