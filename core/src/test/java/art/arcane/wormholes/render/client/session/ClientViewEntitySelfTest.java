package art.arcane.wormholes.render.client.session;

import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewMessageType;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.client.ClientViewEntityTransform;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientViewEntitySelfTest {
    @Test
    void observerBindingPrecedesAllViewsOnceAndResendsAfterNativeReset() throws Exception {
        SessionHarness harness = session();
        harness.handshake(SessionHarness.NATIVE_CAPS);
        harness.tick();
        UUID opaque = harness.entities.projectedId(harness.playerId);
        assertFalse(opaque.equals(harness.playerId));
        assertEquals(opaque, ((ClientViewMessage.EntitySelf) harness.last(ClientViewMessageType.ENTITY_SELF)).projectedId());
        assertEquals(1, harness.sent(ClientViewMessageType.ENTITY_SELF));
        assertTrue(index(harness, ClientViewMessageType.ENTITY_SELF) < index(harness, ClientViewMessageType.ENTITY_FRAME));
        harness.tick();
        assertEquals(1, harness.sent(ClientViewMessageType.ENTITY_SELF));
        int resetStart = harness.client.received.size();
        harness.session.end(ClientViewMessage.ResetReason.PROTOCOL);
        harness.tick();
        assertEquals(2, harness.sent(ClientViewMessageType.ENTITY_SELF));
        List<ClientViewMessage> reset = harness.client.received.subList(resetStart, harness.client.received.size());
        assertTrue(index(reset, ClientViewMessageType.SESSION_RESET) < index(reset, ClientViewMessageType.ENTITY_SELF));
        assertTrue(index(reset, ClientViewMessageType.ENTITY_SELF) < index(reset, ClientViewMessageType.ENTITY_FRAME));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void oldEntityPeersStreamWithoutAnUnknownBinding() throws Exception {
        SessionHarness harness = session();
        harness.handshake(SessionHarness.NATIVE_CAPS & ~ClientViewCapability.ENTITY_SELF.mask());
        harness.tick();
        assertEquals(0, harness.sent(ClientViewMessageType.ENTITY_SELF));
        assertTrue(harness.sent(ClientViewMessageType.ENTITY_FRAME) > 0);
    }

    @Test
    void bindingRequiresNegotiatedEntityFrames() throws Exception {
        SessionHarness harness = session();
        harness.handshake(SessionHarness.NATIVE_CAPS & ~ClientViewCapability.ENTITY_FRAMES.mask());
        harness.tick();
        assertFalse(ClientViewCapability.ENTITY_SELF.in(harness.client.accept.caps()));
        assertEquals(0, harness.sent(ClientViewMessageType.ENTITY_SELF));
        assertEquals(0, harness.sent(ClientViewMessageType.ENTITY_FRAME));
    }

    private static SessionHarness session() {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 32;
        harness.access.add(new SessionPortal("self-a", 0));
        harness.access.add(new SessionPortal("self-b", 8));
        harness.entities = new ClientViewEntitySource<String>() {
            @Override
            public ClientViewMessage.EntityFrame frame(String observer, UUID portal, int key, long tick, boolean full, boolean hideObserver) {
                UUID id = projectedId(harness.playerId);
                EntityVisual visual = new EntityVisual(EntityVisual.MODE_FULL, 1, EntityVisual.FIELD_ALL_FULL, id,
                    "minecraft:player", 1, 64, 1, 1.8, 0, 0, 1, 0, 0, 0, 0, 0, true, "Observer", "", "", null,
                    null, EntityVisual.EMPTY, EntityVisual.EMPTY, EntityVisual.EMPTY);
                return new ClientViewMessage.EntityFrame(key, (int) tick, List.of(visual), List.of(id), true);
            }

            @Override
            public UUID projectedId(UUID sourceId) {
                return ClientViewEntityTransform.opaque(123L, sourceId);
            }
        };
        return harness;
    }

    private static int index(SessionHarness harness, ClientViewMessageType type) {
        return index(harness.client.received, type);
    }

    private static int index(List<ClientViewMessage> messages, ClientViewMessageType type) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).type() == type) {
                return index;
            }
        }
        throw new AssertionError(type);
    }
}
