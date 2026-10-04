package art.arcane.wormholes.network.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.render.client.ClientPortalGeometry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientPreparedTravelCodecTest {
    private static final UUID TOKEN = new UUID(12, 34);

    @Test
    void allTravelMessagesRoundTripWithWorldAndManifestIdentity() throws ClientViewProtocolException {
        for (ClientViewFixtures.Vector vector : travelVectors()) {
            byte[] frame = encode(vector.message());
            assertEquals(vector.message(), decode(frame, vector.clientbound()), vector.name());
            assertEquals(vector.message().type().id(), Byte.toUnsignedInt(frame[0]));
        }
    }

    @Test
    void chunkFragmentsPreserveTheFullBoundaryAndFinalPartialPayload() throws ClientViewProtocolException {
        int total = ClientViewProtocol.TRAVEL_FRAGMENT_BYTES + 17;
        byte[] first = new byte[ClientViewProtocol.TRAVEL_FRAGMENT_BYTES];
        for (int index = 0; index < first.length; index++) {
            first[index] = (byte) index;
        }
        byte[] last = new byte[17];
        Arrays.fill(last, (byte) 0xFF);
        for (ClientViewMessage.TravelChunk chunk : List.of(
            new ClientViewMessage.TravelChunk(TOKEN, 3, -32, -10, 9, 0, 2, total, first),
            new ClientViewMessage.TravelChunk(TOKEN, 3, -32, -10, 9, 1, 2, total, last))) {
            byte[] frame = ClientViewCodec.encodeS2C(chunk, 11, ClientViewProtocol.FLAG_LAST, true);
            assertTrue(frame.length < ClientViewProtocol.MIN_MAX_FRAME_BYTES);
            assertEquals(chunk, ClientViewCodec.decodeS2C(frame, ClientViewCapability.ALL).message());
        }
    }

    @Test
    void payloadAndManifestCopiesCannotBeChangedAfterConstruction() {
        byte[] bytes = new byte[]{1, 2, 3};
        ClientViewMessage.TravelChunk chunk = new ClientViewMessage.TravelChunk(TOKEN, 3, 0, 0, 1, 0, 1, 3, bytes);
        bytes[0] = 9;
        byte[] returned = chunk.payload();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, chunk.payload());
        List<ClientViewMessage.TravelChunkRevision> manifest = new ArrayList<>(
            List.of(new ClientViewMessage.TravelChunkRevision(-32, -10, 9)));
        ClientViewMessage.TravelEnd end = new ClientViewMessage.TravelEnd(TOKEN, 3, 19, manifest);
        manifest.clear();
        assertEquals(1, end.chunks().size());
        assertThrows(UnsupportedOperationException.class, () -> end.chunks().clear());
    }

    @Test
    void manifestBoundFitsAFrameAndRejectsEmptyDuplicateAndOversizedCoordinates() throws ClientViewProtocolException {
        List<ClientViewMessage.TravelChunkRevision> chunks = new ArrayList<>(ClientViewProtocol.MAX_TRAVEL_CHUNKS);
        for (int index = 0; index < ClientViewProtocol.MAX_TRAVEL_CHUNKS; index++) {
            chunks.add(new ClientViewMessage.TravelChunkRevision(index - 544, -index, index + 1));
        }
        ClientViewMessage.TravelEnd end = new ClientViewMessage.TravelEnd(TOKEN, 3, 19, chunks);
        byte[] frame = encode(end);
        assertTrue(frame.length < ClientViewProtocol.MIN_MAX_FRAME_BYTES);
        assertEquals(end, decode(frame, true));
        assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelEnd(TOKEN, 3, 19, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelEnd(TOKEN, 3, 19,
            List.of(new ClientViewMessage.TravelChunkRevision(0, 0, 1), new ClientViewMessage.TravelChunkRevision(0, 0, 2))));
        chunks.add(new ClientViewMessage.TravelChunkRevision(2000, 2000, 1));
        assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelEnd(TOKEN, 3, 19, chunks));
        for (int count : new int[]{0, ClientViewProtocol.MAX_TRAVEL_CHUNKS + 1, 65535}) {
            byte[] invalid = frame.clone();
            buffer(invalid).putShort(ClientViewProtocol.S2C_HEADER_BYTES + 32, (short) count);
            assertThrows(ClientViewProtocolException.class, () -> decode(invalid, true));
        }
    }

    @Test
    void decoderRejectsInconsistentFragmentIndicesCountsSizesAndRevisions() throws ClientViewProtocolException {
        byte[] frame = encode(new ClientViewMessage.TravelChunk(TOKEN, 3, -32, -10, 9, 0, 1, 4, new byte[]{1, 2, 3, 4}));
        int start = ClientViewProtocol.S2C_HEADER_BYTES + 24;
        for (int revision : new int[]{0, -1}) {
            byte[] invalid = frame.clone();
            buffer(invalid).putInt(start + 8, revision);
            assertThrows(ClientViewProtocolException.class, () -> decode(invalid, true));
        }
        for (int offset : new int[]{start + 12, start + 14}) {
            byte[] invalid = frame.clone();
            buffer(invalid).putShort(offset, (short) 2);
            assertThrows(ClientViewProtocolException.class, () -> decode(invalid, true));
        }
        for (int offset : new int[]{start + 16, start + 20}) {
            for (int size : new int[]{0, -1, ClientViewProtocol.MAX_TRAVEL_CHUNK_BYTES + 1}) {
                byte[] invalid = frame.clone();
                buffer(invalid).putInt(offset, size);
                assertThrows(ClientViewProtocolException.class, () -> decode(invalid, true));
            }
        }
    }

    @Test
    void decoderRejectsNonpositiveGenerationAndBarrierRevision() throws ClientViewProtocolException {
        for (ClientViewFixtures.Vector vector : travelVectors()) {
            byte[] frame = encode(vector.message());
            int header = vector.clientbound() ? ClientViewProtocol.S2C_HEADER_BYTES : ClientViewProtocol.C2S_HEADER_BYTES;
            for (long generation : new long[]{0, -1, Long.MIN_VALUE}) {
                byte[] invalid = frame.clone();
                buffer(invalid).putLong(header + 16, generation);
                assertThrows(ClientViewProtocolException.class, () -> decode(invalid, vector.clientbound()));
            }
            if (vector.message() instanceof ClientViewMessage.TravelReady
                || vector.message() instanceof ClientViewMessage.TravelEnd
                || vector.message() instanceof ClientViewMessage.TravelCommit
                || vector.message() instanceof ClientViewMessage.TravelCross) {
                byte[] invalid = frame.clone();
                buffer(invalid).putLong(header + 24, 0);
                assertThrows(ClientViewProtocolException.class, () -> decode(invalid, vector.clientbound()));
            }
        }
    }

    @Test
    void preparationRequiresARealDestinationEnvironmentAndBoundedLifetime() {
        ClientViewMessage.TravelBegin begin = ClientViewFixtures.travelBegin();
        ClientViewMessage.TravelBegin sameWorld = new ClientViewMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
            begin.world().dimension(), begin.sourceGeometry(), begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(),
            begin.environment(), begin.expiresMillis());
        assertEquals(sameWorld.sourceWorld(), sameWorld.world().dimension());
        for (ClientPortalGeometry geometry : List.of(geometry(begin.sourceGeometry(), true, 0, List.of()),
            geometry(begin.sourceGeometry(), false, 7, List.of()),
            geometry(begin.sourceGeometry(), false, 0, List.of(begin.sourceGeometry())))) {
            assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
                begin.sourceWorld(), geometry, begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(),
                begin.environment(), begin.expiresMillis()));
        }
        assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
            begin.sourceWorld(), begin.sourceGeometry(), begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(), ClientViewFixtures.environment(), begin.expiresMillis()));
        for (int expiry : new int[]{0, -1, ClientViewProtocol.MAX_TRAVEL_EXPIRY_MILLIS + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
                begin.sourceWorld(), begin.sourceGeometry(), begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(), begin.environment(), expiry));
        }
        for (double coordinate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 30_000_001}) {
            assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelPose(coordinate, 80, 0, 0, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelPose(0, 80, 0, Float.NaN, 0));
    }

    @Test
    void everyTravelMessageRejectsTruncationTrailingBytesAndTheWrongDirection() throws ClientViewProtocolException {
        for (ClientViewFixtures.Vector vector : travelVectors()) {
            ClientViewMessage message = vector.message();
            byte[] frame = encode(message);
            for (int length = 0; length < frame.length; length++) {
                byte[] truncated = Arrays.copyOf(frame, length);
                assertThrows(ClientViewProtocolException.class, () -> decode(truncated, vector.clientbound()), vector.name());
            }
            assertThrows(ClientViewProtocolException.class,
                () -> decode(Arrays.copyOf(frame, frame.length + 1), vector.clientbound()), vector.name());
            if (message instanceof ClientViewMessage.TravelCancel) {
                assertEquals(message, ClientViewCodec.decodeC2S(ClientViewCodec.encodeC2S(message)));
            } else if (vector.clientbound()) {
                assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeC2S(message));
                assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeC2S(frame));
            } else {
                assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeS2C(message, 0, 0));
                assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(frame, ClientViewCapability.ALL));
            }
        }
    }

    @Test
    void crossingRejectsNonfiniteVectorsAndCommitRejectsNonfiniteVelocity() {
        ClientViewMessage.TravelPose pose = ClientViewFixtures.travelBegin().arrival();
        for (double coordinate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 30_000_001}) {
            GeometryVector invalid = new GeometryVector(coordinate, 0, 0);
            assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelCross(TOKEN, 3, 9, pose,
                invalid, new GeometryVector(0, 0, 0)));
            assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelCross(TOKEN, 3, 9, pose,
                new GeometryVector(0, 0, 0), invalid));
            if (!Double.isFinite(coordinate)) {
                assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelCommit(TOKEN, 3, 9,
                    "minecraft:overworld", "minecraft:overworld", pose, invalid));
            }
        }
    }

    private static ClientPortalGeometry geometry(ClientPortalGeometry value, boolean mirror, int parent,
                                                  List<ClientPortalGeometry> nested) {
        return new ClientPortalGeometry(value.originX(), value.originY(), value.originZ(), value.facing(), value.frontSide(),
            value.quarterTurns(), mirror, value.apertureWidth(), value.apertureHeight(), value.apertureMask(),
            value.nearPlanePadding(), value.aperturePadding(), value.frustumCullingRatio(), value.depthBlocks(),
            value.recursionDepth(), value.blackoutPolicy(), value.blackoutState(), value.maskAirPolicy(), value.lightingPolicy(),
            value.fidelityFlags(), value.kind(), parent, value.targetIdentity(), nested);
    }

    private static List<ClientViewFixtures.Vector> travelVectors() {
        List<ClientViewFixtures.Vector> result = new ArrayList<>(7);
        for (ClientViewFixtures.Vector vector : ClientViewFixtures.vectors()) {
            if (vector.name().startsWith("travel_")) {
                result.add(vector);
            }
        }
        return result;
    }

    private static byte[] encode(ClientViewMessage message) throws ClientViewProtocolException {
        return message.type().isClientbound() ? ClientViewCodec.encodeS2C(message, 7, ClientViewProtocol.FLAG_LAST)
            : ClientViewCodec.encodeC2S(message);
    }

    private static ClientViewMessage decode(byte[] frame, boolean clientbound) throws ClientViewProtocolException {
        return clientbound ? ClientViewCodec.decodeS2C(frame, ClientViewCapability.ALL).message() : ClientViewCodec.decodeC2S(frame);
    }

    private static ByteBuffer buffer(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
