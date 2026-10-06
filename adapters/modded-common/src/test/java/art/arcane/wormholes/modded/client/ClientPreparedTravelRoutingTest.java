package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.optics.frame.OpticTransform;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientPreparedTravelRoutingTest extends MinecraftTestBase {
    @After
    public void deactivate() {
        ProjectionOverlay overlay = ProjectionOverlay.active();
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
    }

    @Test
    public void completeTravelDispatchesOnceAndOrdinaryTickKeepsSessionAccountingActive() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        List<ClientViewMessage.TravelCoordinate> coordinates = new ArrayList<>(49);
        List<ClientViewMessage.TravelChunkRevision> revisions = new ArrayList<>(49);
        for (int z = -3; z <= 3; z++) {
            for (int x = -3; x <= 3; x++) {
                coordinates.add(new ClientViewMessage.TravelCoordinate(x, z));
                revisions.add(new ClientViewMessage.TravelChunkRevision(x, z, 1));
            }
        }
        UUID token = new UUID(4, 17);
        ClientViewMessage.TravelBegin begin = new ClientViewMessage.TravelBegin(token, 8, new UUID(2, 9),
            "minecraft:the_nether", ClientTravelTestFixtures.geometry(), OpticTransform.IDENTITY, new ClientViewMessage.TravelWorld("minecraft:overworld", "minecraft:overworld",
            7, false, false, 63, -64, 384), new ClientViewMessage.TravelPose(0, 80, 0, 0, 0), coordinates,
            PortalEnvironmentTest.environment(OpticTransform.IDENTITY), 30_000);
        AtomicReference<ClientTravelChunks> chunks = new AtomicReference<>();
        AtomicInteger deliveries = new AtomicInteger();
        AtomicInteger columns = new AtomicInteger();
        byte[] payload = new byte[]{21, 42};
        harness.receiver.travel(message -> {
            deliveries.incrementAndGet();
            switch (message) {
                case ClientViewMessage.TravelBegin value -> chunks.set(new ClientTravelChunks(value));
                case ClientViewMessage.TravelChunk value -> {
                    byte[] column = chunks.get().accept(value);
                    if (column != null) {
                        assertArrayEquals(payload, column);
                        columns.incrementAndGet();
                    }
                }
                case ClientViewMessage.TravelEnd value -> chunks.get().end(value);
                default -> { }
            }
        });
        receive(harness, begin);
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            receive(harness, new ClientViewMessage.TravelChunk(token, 8, coordinate.x(), coordinate.z(), 1,
                0, 1, payload.length, payload));
        }
        receive(harness, new ClientViewMessage.TravelEnd(token, 8, 12, revisions));
        assertEquals(49, columns.get());
        assertEquals(12, chunks.get().completeRevision());
        receive(harness, new ClientViewMessage.TravelCommit(token, 8, 12, begin.sourceWorld(), begin.world().dimension(), begin.arrival(), new Vec3d(0, 0, 0)));
        receive(harness, new ClientViewMessage.TravelCancel(token, 8));
        assertEquals(53, deliveries.get());
        assertEquals(53, harness.stats.framesReceived());
        assertEquals(harness.receiver.receivedBytes(), harness.stats.bytesReceived());
        assertEquals(harness.lastSeq, harness.acks().getLast().seq());
        assertEquals(0, harness.receiver.pending());
        assertEquals(0, harness.receiver.decodeFailures());
        assertEquals(0, harness.tick.protocolFailures());
        assertEquals(0, harness.session.protocolFailures());
    }

    private static void receive(ClientViewHarness harness, ClientViewMessage message) throws ClientViewProtocolException {
        harness.receive(message, ViewStreamLimits.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertTrue(harness.session.active());
    }
}
