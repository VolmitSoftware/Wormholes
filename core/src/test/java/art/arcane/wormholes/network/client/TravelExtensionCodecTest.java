package art.arcane.wormholes.network.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;

final class TravelExtensionCodecTest {
    private static final UUID TOKEN = new UUID(12, 34);
    private static final TravelExtension TRAVEL = new TravelExtension(SeamlessTravelCodec.INSTANCE);

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
            byte[] frame = ClientViewFixtures.CODEC.encodeS2C(TravelExtension.PREPARED.wrap(chunk), 11, ViewStreamLimits.FLAG_LAST, true);
            assertTrue(frame.length < ViewStreamLimits.MIN_MAX_FRAME_BYTES);
            assertEquals(TravelExtension.PREPARED.wrap(chunk), ClientViewFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message());
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
        byte[] frame = encode(TravelExtension.PREPARED.wrap(end));
        assertTrue(frame.length < ViewStreamLimits.MIN_MAX_FRAME_BYTES);
        assertEquals(TravelExtension.PREPARED.wrap(end), decode(frame, true));
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
        byte[] frame = encode(TravelExtension.PREPARED.wrap(new TravelMessage.TravelChunk(TOKEN, 3, -32, -10, 9, 0, 1, 4, new byte[]{1, 2, 3, 4})));
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
            if (!identified(vector.travel())) {
                continue;
            }
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
                || vector.travel() instanceof TravelMessage.TravelCross
                || vector.travel() instanceof TravelMessage.TravelAccept) {
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
            begin.environment(), begin.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
        assertEquals(sameWorld.sourceWorld(), sameWorld.world().dimension());
        for (ApertureDescriptor geometry : List.of(geometry(begin.sourceGeometry(), true, 0, List.of()),
            geometry(begin.sourceGeometry(), false, 7, List.of()),
            geometry(begin.sourceGeometry(), false, 0, List.of(begin.sourceGeometry())))) {
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
                begin.sourceWorld(), geometry, begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(),
                begin.environment(), begin.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false));
        }
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
            begin.sourceWorld(), begin.sourceGeometry(), begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(), ClientViewFixtures.environment(), begin.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false));
        for (int expiry : new int[]{0, -1, TravelMessage.MAX_TRAVEL_EXPIRY_MILLIS + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
                begin.sourceWorld(), begin.sourceGeometry(), begin.destinationToSource(), begin.world(), begin.arrival(), begin.chunks(), begin.environment(), expiry, TravelMessage.ArrivalRules.FRAME, false, 0, false));
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
                assertEquals(message, ClientViewFixtures.CODEC.decodeC2S(ClientViewFixtures.CODEC.encodeC2S(message)));
            } else if (vector.clientbound()) {
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.encodeC2S(message));
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.decodeC2S(frame));
            } else {
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.encodeS2C(message, 0, 0));
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL));
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

    @Test
    void seamlessMessagesRoundTripInTheirDirection() throws ViewStreamProtocolException {
        List<TravelMessage> clientbound = List.of(remoteLevelOpen(4), new TravelMessage.RemoteLevelClose(4), routedPacket(4, 0, 1, 3),
            travelAccept(0));
        for (TravelMessage message : clientbound) {
            ViewStreamMessage wrapped = TravelExtension.PREPARED.wrap(message);
            byte[] frame = ClientViewFixtures.CODEC.encodeS2C(wrapped, 9, ViewStreamLimits.FLAG_LAST);
            assertEquals(message.id(), Byte.toUnsignedInt(frame[0]));
            assertEquals(wrapped, ClientViewFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message());
            assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.encodeC2S(wrapped));
        }
        ViewStreamMessage ack = TravelExtension.PREPARED.wrap(new TravelMessage.RemoteViewAck(4, 17, 8));
        byte[] payload = ClientViewFixtures.CODEC.encodeC2S(ack);
        assertEquals(TravelMessage.REMOTE_VIEW_ACK, Byte.toUnsignedInt(payload[0]));
        assertEquals(ack, ClientViewFixtures.CODEC.decodeC2S(payload));
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.encodeS2C(ack, 0, 0));
    }

    @Test
    void travelBeginCarriesArrivalRulesResidencyHandleAndSeamlessFlag() throws ViewStreamProtocolException {
        TravelMessage.TravelBegin base = ClientViewFixtures.travelBegin();
        TravelMessage.ArrivalRules rules = new TravelMessage.ArrivalRules(OrientationRule.MIRROR, true,
            new MomentumRule(MomentumRule.Mode.IMPULSE, 0.5D, 3.0D, new Vec3d(0.0D, 0.25D, -1.0D)));
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(base.token(), base.generation(), base.sourcePortal(), base.sourceWorld(),
            base.sourceGeometry(), base.destinationToSource(), base.world(), base.arrival(), base.chunks(), base.environment(), base.expiresMillis(),
            rules, true, 7, true);
        ViewStreamMessage wrapped = TravelExtension.PREPARED.wrap(begin);
        assertEquals(wrapped, decode(encode(wrapped), true));
        assertEquals(TravelMessage.ArrivalRules.FRAME, base.rules());
        assertFalse(base.resident());
        assertEquals(0, base.levelHandle());
        assertFalse(base.seamless());
    }

    @Test
    void travelCrossRoundTripsTheClaimedPoseAndEyeSegment() throws ViewStreamProtocolException {
        TravelMessage.TravelPose pose = ClientViewFixtures.travelBegin().arrival();
        TravelMessage.TravelCross cross = new TravelMessage.TravelCross(TOKEN, 3, 9, pose, new Vec3d(0, 66, 0), new Vec3d(0, 66, 0.25D));
        ViewStreamMessage wrapped = TravelExtension.PREPARED.wrap(cross);
        assertEquals(wrapped, decode(encode(wrapped), false));
    }

    @Test
    void levelHandlesOutsideTheirRangeAreRejected() throws ViewStreamProtocolException {
        for (int handle : new int[]{0, -1, 256}) {
            assertThrows(IllegalArgumentException.class, () -> remoteLevelOpen(handle));
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RemoteLevelClose(handle));
            assertThrows(IllegalArgumentException.class, () -> routedPacket(handle, 0, 1, 3));
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RemoteViewAck(handle, 0, 8));
        }
        for (int handle : new int[]{-1, 256}) {
            assertThrows(IllegalArgumentException.class, () -> travelAccept(handle));
        }
        assertEquals(255, travelAccept(255).levelHandle());
        TravelMessage.TravelBegin base = ClientViewFixtures.travelBegin();
        assertThrows(IllegalArgumentException.class, () -> withResidency(base, true, 0));
        assertThrows(IllegalArgumentException.class, () -> withResidency(base, false, 3));
        assertThrows(IllegalArgumentException.class, () -> withResidency(base, true, 256));
        byte[] close = encode(TravelExtension.PREPARED.wrap(new TravelMessage.RemoteLevelClose(4)));
        close[ViewStreamLimits.S2C_HEADER_BYTES] = 0;
        assertThrows(ViewStreamProtocolException.class, () -> decode(close, true));
    }

    @Test
    void routedPacketFragmentsMustDescribeTheirPacket() throws ViewStreamProtocolException {
        int total = TravelMessage.TRAVEL_FRAGMENT_BYTES + 5;
        TravelMessage.RoutedPacket first = new TravelMessage.RoutedPacket(4, 2, 0, 2, total, new byte[TravelMessage.TRAVEL_FRAGMENT_BYTES]);
        TravelMessage.RoutedPacket last = new TravelMessage.RoutedPacket(4, 2, 1, 2, total, new byte[5]);
        for (TravelMessage.RoutedPacket fragment : List.of(first, last)) {
            ViewStreamMessage wrapped = TravelExtension.PREPARED.wrap(fragment);
            assertEquals(wrapped, ClientViewFixtures.CODEC.decodeS2C(ClientViewFixtures.CODEC.encodeS2C(wrapped, 1, 0, true),
                ViewStreamCapability.ALL).message());
        }
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RoutedPacket(4, 2, 0, 1, total, new byte[TravelMessage.TRAVEL_FRAGMENT_BYTES]));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RoutedPacket(4, 2, 2, 2, total, new byte[5]));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RoutedPacket(4, 2, -1, 2, total, new byte[5]));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RoutedPacket(4, 2, 1, 2, total, new byte[6]));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RoutedPacket(4, 2, 0, 1, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RoutedPacket(4, -1, 0, 1, 3, new byte[3]));
        int oversized = TravelMessage.MAX_ROUTED_PACKET_BYTES + 1;
        int fragments = (oversized + TravelMessage.TRAVEL_FRAGMENT_BYTES - 1) / TravelMessage.TRAVEL_FRAGMENT_BYTES;
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RoutedPacket(4, 2, fragments - 1, fragments, oversized,
            new byte[oversized - (fragments - 1) * TravelMessage.TRAVEL_FRAGMENT_BYTES]));
        byte[] frame = encode(TravelExtension.PREPARED.wrap(routedPacket(4, 0, 1, 3)));
        int start = ViewStreamLimits.S2C_HEADER_BYTES + 1 + 4;
        byte[] index = frame.clone();
        buffer(index).putShort(start, (short) 1);
        assertThrows(ViewStreamProtocolException.class, () -> decode(index, true));
        byte[] count = frame.clone();
        buffer(count).putShort(start + 2, (short) 2);
        assertThrows(ViewStreamProtocolException.class, () -> decode(count, true));
        byte[] size = frame.clone();
        buffer(size).putInt(start + 8, TravelMessage.TRAVEL_FRAGMENT_BYTES + 1);
        assertThrows(ViewStreamProtocolException.class, () -> decode(size, true));
    }

    @Test
    void posesVelocitiesAndArrivalRulesMustBeFinite() throws ViewStreamProtocolException {
        Vec3d bad = new Vec3d(Double.NaN, 0, 0);
        TravelMessage.TravelPose pose = ClientViewFixtures.travelBegin().arrival();
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelAccept(TOKEN, 3, 9, pose, bad, 0, false, 40L));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelAccept(TOKEN, 3, 0, pose, new Vec3d(0, 0, 0), 0, false, 40L));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelAccept(TOKEN, 3, 9, pose, new Vec3d(0, 0, 0), 0, false, -1L));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.ArrivalRules(OrientationRule.FRAME, false,
            new MomentumRule(MomentumRule.Mode.IMPULSE, 1.0D, 2.0D, bad)));
        assertThrows(NullPointerException.class, () -> new TravelMessage.ArrivalRules(null, false, TravelMessage.ArrivalRules.FRAME.momentum()));
        byte[] accept = encode(TravelExtension.PREPARED.wrap(travelAccept(0)));
        byte[] invalid = accept.clone();
        buffer(invalid).putDouble(ViewStreamLimits.S2C_HEADER_BYTES + 32, Double.NaN);
        assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, true));
        TravelMessage.TravelBegin begin = ClientViewFixtures.travelBegin();
        byte[] frame = encode(TravelExtension.PREPARED.wrap(begin));
        int rules = frame.length - 46;
        byte[] orientation = frame.clone();
        orientation[rules] = (byte) OrientationRule.values().length;
        assertThrows(ViewStreamProtocolException.class, () -> decode(orientation, true));
        byte[] mode = frame.clone();
        mode[rules + 2] = (byte) MomentumRule.Mode.values().length;
        assertThrows(ViewStreamProtocolException.class, () -> decode(mode, true));
        byte[] factor = frame.clone();
        buffer(factor).putDouble(rules + 3, Double.POSITIVE_INFINITY);
        assertThrows(ViewStreamProtocolException.class, () -> decode(factor, true));
        byte[] speed = frame.clone();
        buffer(speed).putDouble(rules + 11, -1.0D);
        assertThrows(ViewStreamProtocolException.class, () -> decode(speed, true));
    }

    @Test
    void remoteLevelsNeedAMatchingIdentityEnvironmentAndABoundedWindow() {
        TravelMessage.TravelBegin begin = ClientViewFixtures.travelBegin();
        for (int radius : new int[]{0, TravelMessage.MAX_REMOTE_VIEW_RADIUS + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RemoteLevelOpen(4, begin.world(), begin.environment(), radius,
                new TravelMessage.TravelCoordinate(0, 0)));
        }
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RemoteLevelOpen(4, begin.world(), ClientViewFixtures.environment(), 8,
            new TravelMessage.TravelCoordinate(0, 0)));
        for (int hint : new int[]{0, TravelMessage.MAX_CHUNKS_PER_TICK_HINT + 1}) {
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RemoteViewAck(4, 0, hint));
        }
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RemoteViewAck(4, -1, 8));
    }

    @Test
    void unknownIdsInsideTheTravelRangeAreProtocolExceptions() throws ViewStreamProtocolException {
        byte[] down = encode(TravelExtension.PREPARED.wrap(new TravelMessage.RemoteLevelClose(4)));
        byte[] up = encode(TravelExtension.PREPARED.wrap(new TravelMessage.RemoteViewAck(4, 0, 8)));
        for (int id = TravelMessage.FIRST_ID; id <= TravelMessage.LAST_ID; id++) {
            byte[] s2c = down.clone();
            s2c[0] = (byte) id;
            byte[] c2s = up.clone();
            c2s[0] = (byte) id;
            if (!TRAVEL.clientbound(id)) {
                assertThrows(ViewStreamProtocolException.class, () -> decode(s2c, true), "S2C " + id);
            }
            if (!TRAVEL.serverbound(id)) {
                assertThrows(ViewStreamProtocolException.class, () -> decode(c2s, false), "C2S " + id);
            }
        }
        assertThrows(ViewStreamProtocolException.class, () -> TRAVEL.decode(56, null));
    }

    @Test
    void preparedTravelNeitherOffersNorDecodesSeamlessMessages() throws ViewStreamProtocolException {
        assertEquals(ViewStreamCapability.of(ViewStreamCapability.PREPARED_TRAVEL, ViewStreamCapability.PREPARED_TRAVEL_CACHE),
            TravelExtension.PREPARED.capabilities());
        assertEquals(TravelExtension.PREPARED.capabilities() | ViewStreamCapability.FX_EMITTERS.mask(), ClientViewExtensions.CODEC.capabilities());
        for (int id = TravelMessage.REMOTE_LEVEL_OPEN; id <= TravelMessage.REMOTE_VIEW_ACK; id++) {
            assertFalse(TravelExtension.PREPARED.clientbound(id), "S2C " + id);
            assertFalse(TravelExtension.PREPARED.serverbound(id), "C2S " + id);
        }
        ViewStreamMessage close = TravelExtension.PREPARED.wrap(new TravelMessage.RemoteLevelClose(4));
        byte[] frame = encode(close);
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.decodeS2C(frame, ViewStreamCapability.ALL));
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.encodeS2C(close, 0, 0));
        byte[] ack = encode(TravelExtension.PREPARED.wrap(new TravelMessage.RemoteViewAck(4, 0, 8)));
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.decodeC2S(ack));
        ViewStreamMessage begin = TravelExtension.PREPARED.wrap(ClientViewFixtures.travelBegin());
        assertEquals(begin, ClientViewExtensions.CODEC.decodeS2C(ClientViewExtensions.CODEC.encodeS2C(begin, 1, 0), ViewStreamCapability.ALL).message());
    }

    @Test
    void directionsFollowTheProtocol() {
        List<Integer> serverbound = new ArrayList<>();
        List<Integer> clientbound = new ArrayList<>();
        for (int id = TravelMessage.FIRST_ID; id <= TravelMessage.LAST_ID; id++) {
            if (TRAVEL.serverbound(id)) {
                serverbound.add(id);
            }
            if (TRAVEL.clientbound(id)) {
                clientbound.add(id);
            }
        }
        assertEquals(List.of(44, 46, 47, 49, 55), serverbound);
        assertEquals(List.of(41, 42, 43, 45, 46, 48, 51, 52, 53, 54), clientbound);
        assertEquals(41, TRAVEL.firstId());
        assertEquals(63, TRAVEL.lastId());
        assertEquals(ViewStreamCapability.of(ViewStreamCapability.PREPARED_TRAVEL, ViewStreamCapability.PREPARED_TRAVEL_CACHE,
            ViewStreamCapability.REMOTE_VIEW, ViewStreamCapability.SEAMLESS_TRAVEL), TRAVEL.capabilities());
    }

    private static boolean identified(TravelMessage message) {
        return !(message instanceof TravelMessage.RemoteLevelOpen || message instanceof TravelMessage.RemoteLevelClose
            || message instanceof TravelMessage.RoutedPacket || message instanceof TravelMessage.RemoteViewAck);
    }

    private static TravelMessage.RemoteLevelOpen remoteLevelOpen(int handle) {
        TravelMessage.TravelBegin begin = ClientViewFixtures.travelBegin();
        return new TravelMessage.RemoteLevelOpen(handle, begin.world(), begin.environment(), 8, new TravelMessage.TravelCoordinate(-32, -10));
    }

    private static TravelMessage.RoutedPacket routedPacket(int handle, int index, int count, int total) {
        return new TravelMessage.RoutedPacket(handle, 5, index, count, total, new byte[]{7, 8, 9});
    }

    private static TravelMessage.TravelAccept travelAccept(int handle) {
        return new TravelMessage.TravelAccept(TOKEN, 3, 9, ClientViewFixtures.travelBegin().arrival(), new Vec3d(0.25D, -0.5D, 1.0D), handle,
            handle != 0, 1200L);
    }

    private static TravelMessage.TravelBegin withResidency(TravelMessage.TravelBegin base, boolean resident, int handle) {
        return new TravelMessage.TravelBegin(base.token(), base.generation(), base.sourcePortal(), base.sourceWorld(), base.sourceGeometry(),
            base.destinationToSource(), base.world(), base.arrival(), base.chunks(), base.environment(), base.expiresMillis(), base.rules(),
            resident, handle, base.seamless());
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
        return ClientViewFixtures.CODEC.clientbound(message) ? ClientViewFixtures.CODEC.encodeS2C(message, 7, ViewStreamLimits.FLAG_LAST)
            : ClientViewFixtures.CODEC.encodeC2S(message);
    }

    private static ViewStreamMessage decode(byte[] frame, boolean clientbound) throws ViewStreamProtocolException {
        return clientbound ? ClientViewFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message() : ClientViewFixtures.CODEC.decodeC2S(frame);
    }

    private static ByteBuffer buffer(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
