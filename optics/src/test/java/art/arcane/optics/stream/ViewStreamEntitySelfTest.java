package art.arcane.optics.stream;

import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.entity.EntityProjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ViewStreamEntitySelfTest {
    @Test
    void observerBindingPrecedesAllViewsOnceAndResendsAfterNativeReset() throws Exception {
        SessionHarness harness = session();
        harness.handshake(SessionHarness.NATIVE_CAPS);
        harness.tick();
        UUID opaque = harness.entities.projectedId(harness.playerId);
        assertFalse(opaque.equals(harness.playerId));
        assertEquals(opaque, ((ViewStreamMessage.EntitySelf) harness.last(ViewStreamMessageType.ENTITY_SELF)).projectedId());
        assertEquals(1, harness.sent(ViewStreamMessageType.ENTITY_SELF));
        assertTrue(index(harness, ViewStreamMessageType.ENTITY_SELF) < index(harness, ViewStreamMessageType.ENTITY_FRAME));
        harness.tick();
        assertEquals(1, harness.sent(ViewStreamMessageType.ENTITY_SELF));
        int resetStart = harness.client.received.size();
        harness.session.end(ViewStreamMessage.ResetReason.PROTOCOL);
        harness.tick();
        assertEquals(2, harness.sent(ViewStreamMessageType.ENTITY_SELF));
        List<ViewStreamMessage> reset = harness.client.received.subList(resetStart, harness.client.received.size());
        assertTrue(index(reset, ViewStreamMessageType.SESSION_RESET) < index(reset, ViewStreamMessageType.ENTITY_SELF));
        assertTrue(index(reset, ViewStreamMessageType.ENTITY_SELF) < index(reset, ViewStreamMessageType.ENTITY_FRAME));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void oldEntityPeersStreamWithoutAnUnknownBinding() throws Exception {
        SessionHarness harness = session();
        harness.handshake(SessionHarness.NATIVE_CAPS & ~ViewStreamCapability.ENTITY_SELF.mask());
        harness.tick();
        assertEquals(0, harness.sent(ViewStreamMessageType.ENTITY_SELF));
        assertTrue(harness.sent(ViewStreamMessageType.ENTITY_FRAME) > 0);
    }

    @Test
    void bindingRequiresNegotiatedEntityFrames() throws Exception {
        SessionHarness harness = session();
        harness.handshake(SessionHarness.NATIVE_CAPS & ~ViewStreamCapability.ENTITY_FRAMES.mask());
        harness.tick();
        assertFalse(ViewStreamCapability.ENTITY_SELF.in(harness.client.accept.caps()));
        assertEquals(0, harness.sent(ViewStreamMessageType.ENTITY_SELF));
        assertEquals(0, harness.sent(ViewStreamMessageType.ENTITY_FRAME));
    }

    private static SessionHarness session() {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 32;
        harness.access.add(new SessionPortal("self-a", 0));
        harness.access.add(new SessionPortal("self-b", 8));
        harness.entities = new EntityFrameSource<String>() {
            @Override
            public ViewStreamMessage.EntityFrame frame(String observer, UUID portal, int key, long tick, boolean full, boolean hideObserver) {
                UUID id = projectedId(harness.playerId);
                EntitySnapshot visual = new EntitySnapshot(EntitySnapshot.MODE_FULL, 1, EntitySnapshot.FIELD_ALL_FULL, id,
                    "minecraft:player", 1, 64, 1, 1.8, 0, 0, 1, 0, 0, 0, 0, 0, true, "Observer", "", "", null,
                    null, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY);
                return new ViewStreamMessage.EntityFrame(key, (int) tick, List.of(visual), List.of(id), true);
            }

            @Override
            public UUID projectedId(UUID sourceId) {
                return EntityProjection.opaque(123L, sourceId);
            }
        };
        return harness;
    }

    private static int index(SessionHarness harness, ViewStreamMessageType type) {
        return index(harness.client.received, type);
    }

    private static int index(List<ViewStreamMessage> messages, ViewStreamMessageType type) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).id() == type.id()) {
                return index;
            }
        }
        throw new AssertionError(type);
    }
}
