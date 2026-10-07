package art.arcane.wormholes.network.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.aperture.ApertureDescriptor;

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
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;

final class TravelExtensionCodecTest {
    private static final UUID TOKEN = new UUID(12, 34);

    @Test
    void allTravelMessagesRoundTripWithWorldAndManifestIdentity() throws ViewStreamProtocolException {
        for (ClientViewFixtures.Vector vector : travelVectors()) {
            byte[] frame = encode(vector.message());
            assertEquals(vector.message(), decode(frame, vector.clientbound()), vector.name());
            assertEquals(vector.message().id(), Byte.toUnsignedInt(frame[0]));
        }
    }

    @Test
    void chunkFragmentsPreserveTheFullBoundaryAndFinalPartialPayload() throws ViewStreamProtocolException {
        int total = TravelMessage.TRAVEL_FRAGMENT_BYTES + 17;
        byte[] first = new byte[TravelMessage.TRAVEL_FRAGMENT_BYTES];
        for (int index = 0; index < first.length; index++) {
            first[index] = (byte) index;
        }
        byte[] last = new byte[17];
        Arrays.fill(last, (byte) 0xFF);
        for (TravelMessage.TravelChunk chunk : List.of(
            new TravelMessage.TravelChunk(TOKEN, 3, -32, -10, 9, 0, 2, total, first),
            new TravelMessage.TravelChunk(TOKEN, 3, -32, -10, 9, 1, 2, total, last))) {
            byte[] frame = ClientViewExtensions.CODEC.encodeS2C(TravelExtension.INSTANCE.wrap(chunk), 11, ViewStreamLimits.FLAG_LAST, true);
            assertTrue(frame.length < ViewStreamLimits.MIN_MAX_FRAME_BYTES);
            assertEquals(TravelExtension.INSTANCE.wrap(chunk), ClientViewExtensions.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message());
        }
    }

    @Test
    void payloadAndManifestCopiesCannotBeChangedAfterConstruction() {
        byte[] bytes = new byte[]{1, 2, 3};
        TravelMessage.TravelChunk chunk = new TravelMessage.TravelChunk(TOKEN, 3, 0, 0, 1, 0, 1, 3, bytes);
        bytes[0] = 9;
        byte[] returned = chunk.payload();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, chunk.payload());
        List<TravelMessage.TravelChunkRevision> manifest = new ArrayList<>(
            List.of(new TravelMessage.TravelChunkRevision(-32, -10, 9)));
        TravelMessage.TravelEnd end = new TravelMessage.TravelEnd(TOKEN, 3, 19, manifest);
        manifest.clear();
        assertEquals(1, end.chunks().size());
        assertThrows(UnsupportedOperationException.class, () -> end.chunks().clear());
    }

    @Test
    void manifestBoundFitsAFrameAndRejectsEmptyDuplicateAndOversizedCoordinates() throws ViewStreamProtocolException {
        List<TravelMessage.TravelChunkRevision> chunks = new ArrayList<>(TravelMessage.MAX_TRAVEL_CHUNKS);
        for (int index = 0; index < TravelMessage.MAX_TRAVEL_CHUNKS; index++) {
            chunks.add(new TravelMessage.TravelChunkRevision(index - 544, -index, index + 1));
        }
        TravelMessage.TravelEnd end = new TravelMessage.TravelEnd(TOKEN, 3, 19, chunks);
        byte[] frame = encode(TravelExtension.INSTANCE.wrap(end));
        assertTrue(frame.length < ViewStreamLimits.MIN_MAX_FRAME_BYTES);
        assertEquals(TravelExtension.INSTANCE.wrap(end), decode(frame, true));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelEnd(TOKEN, 3, 19, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelEnd(TOKEN, 3, 19,
            List.of(new TravelMessage.TravelChunkRevision(0, 0, 1), new TravelMessage.TravelChunkRevision(0, 0, 2))));
        chunks.add(new TravelMessage.TravelChunkRevision(2000, 2000, 1));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelEnd(TOKEN, 3, 19, chunks));
        for (int count : new int[]{0, TravelMessage.MAX_TRAVEL_CHUNKS + 1, 65535}) {
            byte[] invalid = frame.clone();
            buffer(invalid).putShort(ViewStreamLimits.S2C_HEADER_BYTES + 32, (short) count);
            assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, true));
        }
    }

    @Test
    void decoderRejectsInconsistentFragmentIndicesCountsSizesAndRevisions() throws ViewStreamProtocolException {
        byte[] frame = encode(TravelExtension.INSTANCE.wrap(new TravelMessage.TravelChunk(TOKEN, 3, -32, -10, 9, 0, 1, 4, new byte[]{1, 2, 3, 4})));
        int start = ViewStreamLimits.S2C_HEADER_BYTES + 24;
        for (int revision : new int[]{0, -1}) {
            byte[] invalid = frame.clone();
            buffer(invalid).putInt(start + 8, revision);
            assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, true));
        }
        for (int offset : new int[]{start + 12, start + 14}) {
            byte[] invalid = frame.clone();
            buffer(invalid).putShort(offset, (short) 2);
            assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, true));
        }
        for (int offset : new int[]{start + 16, start + 20}) {
            for (int size : new int[]{0, -1, TravelMessage.MAX_TRAVEL_CHUNK_BYTES + 1}) {
                byte[] invalid = frame.clone();
                buffer(invalid).putInt(offset, size);
                assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, true));
            }
        }
    }

    @Test
    void decoderRejectsNonpositiveGenerationAndBarrierRevision() throws ViewStreamProtocolException {
        for (ClientViewFixtures.Vector vector : travelVectors()) {
            byte[] frame = encode(vector.message());
            int header = vector.clientbound() ? ViewStreamLimits.S2C_HEADER_BYTES : ViewStreamLimits.C2S_HEADER_BYTES;
            for (long generation : new long[]{0, -1, Long.MIN_VALUE}) {
                byte[] invalid = frame.clone();
                buffer(invalid).putLong(header + 16, generation);
                assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, vector.clientbound()));
            }
            if (vector.travel() instanceof TravelMessage.TravelReady
                || vector.travel() instanceof TravelMessage.TravelEnd
                || vector.travel() instanceof TravelMessage.TravelCommit
                || vector.travel() instanceof TravelMessage.TravelCross) {
                byte[] invalid = frame.clone();
                buffer(invalid).putLong(header + 24, 0);
                assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, vector.clientbound()));
            }
        }
    }

    @Test
    void preparationRequiresARealDestinationEnvironmentAndBoundedLifetime() {
        TravelMessage.TravelBegin begin = ClientViewFixtures.travelBegin();
        TravelMessage.TravelBegin sameWorld = new TravelMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
            begin.world().dimension(), begin.sourceGeometry(), begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(),
            begin.environment(), begin.expiresMillis());
        assertEquals(sameWorld.sourceWorld(), sameWorld.world().dimension());
        for (ApertureDescriptor geometry : List.of(geometry(begin.sourceGeometry(), true, 0, List.of()),
            geometry(begin.sourceGeometry(), false, 7, List.of()),
            geometry(begin.sourceGeometry(), false, 0, List.of(begin.sourceGeometry())))) {
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
                begin.sourceWorld(), geometry, begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(),
                begin.environment(), begin.expiresMillis()));
        }
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
            begin.sourceWorld(), begin.sourceGeometry(), begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(), ClientViewFixtures.environment(), begin.expiresMillis()));
        for (int expiry : new int[]{0, -1, TravelMessage.MAX_TRAVEL_EXPIRY_MILLIS + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
                begin.sourceWorld(), begin.sourceGeometry(), begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(), begin.environment(), expiry));
        }
        for (double coordinate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 30_000_001}) {
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelPose(coordinate, 80, 0, 0, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelPose(0, 80, 0, Float.NaN, 0));
    }

    @Test
    void everyTravelMessageRejectsTruncationTrailingBytesAndTheWrongDirection() throws ViewStreamProtocolException {
        for (ClientViewFixtures.Vector vector : travelVectors()) {
            ViewStreamMessage message = vector.message();
            byte[] frame = encode(message);
            for (int length = 0; length < frame.length; length++) {
                byte[] truncated = Arrays.copyOf(frame, length);
                assertThrows(ViewStreamProtocolException.class, () -> decode(truncated, vector.clientbound()), vector.name());
            }
            assertThrows(ViewStreamProtocolException.class,
                () -> decode(Arrays.copyOf(frame, frame.length + 1), vector.clientbound()), vector.name());
            if (vector.travel() instanceof TravelMessage.TravelCancel) {
                assertEquals(message, ClientViewExtensions.CODEC.decodeC2S(ClientViewExtensions.CODEC.encodeC2S(message)));
            } else if (vector.clientbound()) {
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.encodeC2S(message));
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.decodeC2S(frame));
            } else {
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.encodeS2C(message, 0, 0));
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.decodeS2C(frame, ViewStreamCapability.ALL));
            }
        }
    }

    @Test
    void crossingRejectsNonfiniteVectorsAndCommitRejectsNonfiniteVelocity() {
        TravelMessage.TravelPose pose = ClientViewFixtures.travelBegin().arrival();
        for (double coordinate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 30_000_001}) {
            Vec3d invalid = new Vec3d(coordinate, 0, 0);
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelCross(TOKEN, 3, 9, pose,
                invalid, new Vec3d(0, 0, 0)));
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelCross(TOKEN, 3, 9, pose,
                new Vec3d(0, 0, 0), invalid));
            if (!Double.isFinite(coordinate)) {
                assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelCommit(TOKEN, 3, 9,
                    "minecraft:overworld", "minecraft:overworld", pose, invalid));
            }
        }
    }

    private static ApertureDescriptor geometry(ApertureDescriptor value, boolean mirror, int parent,
                                                  List<ApertureDescriptor> nested) {
        return new ApertureDescriptor(value.originX(), value.originY(), value.originZ(), value.facing(), value.frontSide(),
            value.quarterTurns(), mirror, value.apertureWidth(), value.apertureHeight(), value.apertureMask(),
            value.nearPlanePadding(), value.aperturePadding(), value.frustumCullingRatio(), value.depthBlocks(),
            value.recursionDepth(), value.blackoutPolicy(), value.blackoutState(), value.maskAirPolicy(), value.lightingPolicy(),
            value.fidelityFlags(), value.kind(), value.planeOffset(), parent, value.targetIdentity(), nested);
    }

    private static List<ClientViewFixtures.Vector> travelVectors() {
        return ClientViewFixtures.travelVectors();
    }

    private static byte[] encode(ViewStreamMessage message) throws ViewStreamProtocolException {
        return ClientViewExtensions.CODEC.clientbound(message) ? ClientViewExtensions.CODEC.encodeS2C(message, 7, ViewStreamLimits.FLAG_LAST)
            : ClientViewExtensions.CODEC.encodeC2S(message);
    }

    private static ViewStreamMessage decode(byte[] frame, boolean clientbound) throws ViewStreamProtocolException {
        return clientbound ? ClientViewExtensions.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message() : ClientViewExtensions.CODEC.decodeC2S(frame);
    }

    private static ByteBuffer buffer(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
