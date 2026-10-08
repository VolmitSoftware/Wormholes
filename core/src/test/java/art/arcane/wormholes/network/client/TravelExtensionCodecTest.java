package art.arcane.wormholes.network.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.shape.ShapeDescriptor;

final class TravelExtensionCodecTest {
    private static final UUID TOKEN = new UUID(12, 34);
    private static final TravelExtension TRAVEL = TravelExtension.INSTANCE;

    @Test
    void allTravelMessagesRoundTripWithWorldAndManifestIdentity() throws ViewStreamProtocolException {
        for (ClientViewFixtures.Vector vector : travelVectors()) {
            byte[] frame = encode(vector.message());
            assertEquals(vector.message(), decode(frame, vector.clientbound()), vector.name());
            assertEquals(vector.message().id(), Byte.toUnsignedInt(frame[0]));
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
            if (vector.travel() instanceof TravelMessage.TravelCross
                || vector.travel() instanceof TravelMessage.TravelAccept) {
                byte[] invalid = frame.clone();
                buffer(invalid).putLong(header + 24, 0);
                assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, vector.clientbound()));
            }
        }
    }

    @Test
    void preparationRequiresARealDestinationEnvironment() {
        TravelMessage.TravelBegin begin = ClientViewFixtures.travelBegin();
        TravelMessage.TravelBegin sameWorld = new TravelMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
            begin.world().dimension(), begin.sourceGeometry(), begin.destinationToSource(), 1.0F, begin.world(), begin.arrival(), begin.environment(),
            TravelMessage.ArrivalRules.FRAME, false, 0);
        assertEquals(sameWorld.sourceWorld(), sameWorld.world().dimension());
        for (ApertureDescriptor geometry : List.of(geometry(begin.sourceGeometry(), true, 0, List.of()),
            geometry(begin.sourceGeometry(), false, 7, List.of()),
            geometry(begin.sourceGeometry(), false, 0, List.of(begin.sourceGeometry())))) {
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
                begin.sourceWorld(), geometry, begin.destinationToSource(), 1.0F, begin.world(), begin.arrival(), begin.environment(),
                TravelMessage.ArrivalRules.FRAME, false, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelBegin(TOKEN, 3, begin.sourcePortal(),
            begin.sourceWorld(), begin.sourceGeometry(), begin.destinationToSource(), 1.0F, begin.world(), begin.arrival(), ClientViewFixtures.environment(),
            TravelMessage.ArrivalRules.FRAME, false, 0));
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
            if (vector.clientbound()) {
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.encodeC2S(message));
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.decodeC2S(frame));
            } else {
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.encodeS2C(message, 0, 0));
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL));
            }
        }
    }

    @Test
    void crossingRejectsNonfiniteVectors() {
        TravelMessage.TravelPose pose = ClientViewFixtures.travelBegin().arrival();
        for (double coordinate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 30_000_001}) {
            Vec3d invalid = new Vec3d(coordinate, 0, 0);
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelCross(TOKEN, 3, 9, pose,
                invalid, new Vec3d(0, 0, 0)));
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelCross(TOKEN, 3, 9, pose,
                new Vec3d(0, 0, 0), invalid));
        }
    }

    @Test
    void seamlessMessagesRoundTripInTheirDirection() throws ViewStreamProtocolException {
        List<TravelMessage> clientbound = List.of(remoteLevelOpen(4), new TravelMessage.RemoteLevelClose(4), routedPacket(4, 0, 1, 3),
            travelAccept(0), entityCrossed(0), entityCrossed(4));
        for (TravelMessage message : clientbound) {
            ViewStreamMessage wrapped = TravelExtension.INSTANCE.wrap(message);
            byte[] frame = ClientViewFixtures.CODEC.encodeS2C(wrapped, 9, ViewStreamLimits.FLAG_LAST);
            assertEquals(message.id(), Byte.toUnsignedInt(frame[0]));
            assertEquals(wrapped, ClientViewFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message());
            assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.encodeC2S(wrapped));
        }
        ViewStreamMessage ack = TravelExtension.INSTANCE.wrap(new TravelMessage.RemoteViewAck(4, 17, 8));
        byte[] payload = ClientViewFixtures.CODEC.encodeC2S(ack);
        assertEquals(TravelMessage.REMOTE_VIEW_ACK, Byte.toUnsignedInt(payload[0]));
        assertEquals(ack, ClientViewFixtures.CODEC.decodeC2S(payload));
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.encodeS2C(ack, 0, 0));
        ViewStreamMessage reopen = TravelExtension.INSTANCE.wrap(new TravelMessage.RemoteLevelReopen(4));
        byte[] request = ClientViewFixtures.CODEC.encodeC2S(reopen);
        assertEquals(TravelMessage.REMOTE_LEVEL_REOPEN, Byte.toUnsignedInt(request[0]));
        assertEquals(reopen, ClientViewFixtures.CODEC.decodeC2S(request));
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewFixtures.CODEC.encodeS2C(reopen, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RemoteLevelReopen(0));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.RemoteLevelReopen(TravelMessage.MAX_LEVEL_HANDLE + 1));
    }

    @Test
    void entityCrossingsCarryTheirLevelTransformAndFiniteVelocity() {
        TravelMessage.EntityCrossed crossed = entityCrossed(7);
        assertEquals(TravelMessage.ENTITY_CROSSED, crossed.id());
        assertEquals(7, crossed.levelHandle());
        assertEquals(OpticTransform.translation(0.0D, 20.0D, 0.0D), crossed.toward());
        assertThrows(IllegalArgumentException.class, () -> entityCrossed(-1));
        assertThrows(IllegalArgumentException.class, () -> entityCrossed(TravelMessage.MAX_LEVEL_HANDLE + 1));
        assertEquals(Face.U, crossed.planeNormal());
        assertThrows(NullPointerException.class, () -> new TravelMessage.EntityCrossed(0, 42, null, new Vec3d(0, 0, 0), Face.U, new Vec3d(0, 0, 0)));
        assertThrows(NullPointerException.class, () -> new TravelMessage.EntityCrossed(0, 42, OpticTransform.IDENTITY, new Vec3d(0, 0, 0), null,
            new Vec3d(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.EntityCrossed(0, 42, OpticTransform.IDENTITY, new Vec3d(0, 0, 0),
            Face.U, new Vec3d(Double.NaN, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new TravelMessage.EntityCrossed(0, 42, OpticTransform.IDENTITY,
            new Vec3d(0, Double.POSITIVE_INFINITY, 0), Face.U, new Vec3d(0, 0, 0)));
    }

    @Test
    void entityCrossingPlaneNormalsOutsideTheFaceRangeAreProtocolExceptions() throws ViewStreamProtocolException {
        byte[] frame = encode(TravelExtension.INSTANCE.wrap(entityCrossed(4)));
        frame[ViewStreamLimits.S2C_HEADER_BYTES + 1 + Integer.BYTES + OpticTransform.ENCODED_BYTES + 3 * Double.BYTES] = (byte) Face.values().length;
        assertThrows(ViewStreamProtocolException.class, () -> decode(frame, true));
    }

    @Test
    void travelBeginCarriesArrivalRulesResidencyAndHandle() throws ViewStreamProtocolException {
        TravelMessage.TravelBegin base = ClientViewFixtures.travelBegin();
        TravelMessage.ArrivalRules rules = new TravelMessage.ArrivalRules(OrientationRule.MIRROR, true,
            new MomentumRule(MomentumRule.Mode.IMPULSE, 0.5D, 3.0D, new Vec3d(0.0D, 0.25D, -1.0D)), ScaleRule.OFF);
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(base.token(), base.generation(), base.sourcePortal(), base.sourceWorld(),
            base.sourceGeometry(), base.destinationToSource(), 1.0F, base.world(), base.arrival(), base.environment(), rules, true, 7);
        ViewStreamMessage wrapped = TravelExtension.INSTANCE.wrap(begin);
        assertEquals(wrapped, decode(encode(wrapped), true));
        assertEquals(TravelMessage.ArrivalRules.FRAME, base.rules());
        assertFalse(base.resident());
        assertEquals(0, base.levelHandle());
    }

    @Test
    void travelBeginRoundTripsTheTravelScaleAndScaleRule() throws ViewStreamProtocolException {
        TravelMessage.TravelBegin base = ClientViewFixtures.travelBegin();
        TravelMessage.ArrivalRules rules = new TravelMessage.ArrivalRules(OrientationRule.FRAME, false,
            TravelMessage.ArrivalRules.FRAME.momentum(), ScaleRule.ratio(0.5D, 3.0D));
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(base.token(), base.generation(), base.sourcePortal(), base.sourceWorld(),
            base.sourceGeometry(), base.destinationToSource(), 3.0F, base.world(), base.arrival(), base.environment().withScale(0.25F),
            rules, true, 7);
        ViewStreamMessage wrapped = TravelExtension.INSTANCE.wrap(begin);
        TravelMessage.TravelBegin decoded = (TravelMessage.TravelBegin) ((ViewStreamMessage.Extension) decode(encode(wrapped), true)).payload();

        assertEquals(begin, decoded);
        assertEquals(3.0F, decoded.scale(), 0.0F);
        assertEquals(0.25F, decoded.environment().scale(), 0.0F);
        assertEquals(ScaleRule.ratio(0.5D, 3.0D), decoded.rules().scale());
        assertEquals(ScaleRule.OFF, base.rules().scale());
        assertEquals(1.0F, base.scale(), 0.0F);
    }

    @Test
    void travelScalesMustBeFiniteAndPositive() {
        TravelMessage.TravelBegin base = ClientViewFixtures.travelBegin();
        for (float scale : new float[] {0.0F, -1.0F, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelBegin(base.token(), base.generation(), base.sourcePortal(),
                base.sourceWorld(), base.sourceGeometry(), base.destinationToSource(), scale, base.world(), base.arrival(), base.environment(), base.rules(),
                false, 0));
        }
    }

    @Test
    void theWireTransformRebuildsAScaledMappingAboutItsOwnCentre() {
        Frame north = Frame.canonical(Face.N);
        Frame east = Frame.canonical(Face.E);
        Similarity toward = Similarity.between(north, new Vec3d(635.5D, 65.5D, -4681.0D), east, new Vec3d(-511.0D, 81.5D, -159.5D), 3.0D);
        OpticTransform wire = TravelMessage.TravelBegin.destinationToSource(toward);
        TravelMessage.TravelBegin base = ClientViewFixtures.travelBegin();
        TravelMessage.TravelBegin begin = new TravelMessage.TravelBegin(base.token(), base.generation(), base.sourcePortal(), base.sourceWorld(),
            base.sourceGeometry(), OpticTransform.decode(wire.normalized().encode()), 3.0F, base.world(), base.arrival(), base.environment(),
            base.rules(), false, 0);
        for (Vec3d point : List.of(new Vec3d(635.5D, 65.5D, -4681.0D), new Vec3d(636.25D, 64.0D, -4680.5D), new Vec3d(0.0D, 0.0D, 0.0D))) {
            Vec3d expected = toward.point(point);
            Vec3d actual = begin.sourceToDestination().point(point);
            assertEquals(expected.x(), actual.x(), 1.0E-9D);
            assertEquals(expected.y(), actual.y(), 1.0E-9D);
            assertEquals(expected.z(), actual.z(), 1.0E-9D);
        }
    }

    @Test
    void scaleRulesOutsideTheModeRangeOrInvertedAreProtocolExceptions() throws ViewStreamProtocolException {
        TravelMessage.TravelBegin base = ClientViewFixtures.residentBegin();
        byte[] frame = encode(TravelExtension.INSTANCE.wrap(base));
        int rules = frame.length - 2 - 2 * Float.BYTES - 1;
        byte[] badMode = frame.clone();
        badMode[rules] = (byte) ScaleRule.Mode.values().length;
        assertThrows(ViewStreamProtocolException.class, () -> decode(badMode, true));
        byte[] inverted = frame.clone();
        buffer(inverted).putFloat(rules + 1, 9.0F);
        assertThrows(ViewStreamProtocolException.class, () -> decode(inverted, true));
    }

    @Test
    void travelCrossRoundTripsTheClaimedPoseAndEyeSegment() throws ViewStreamProtocolException {
        TravelMessage.TravelPose pose = ClientViewFixtures.travelBegin().arrival();
        TravelMessage.TravelCross cross = new TravelMessage.TravelCross(TOKEN, 3, 9, pose, new Vec3d(0, 66, 0), new Vec3d(0, 66, 0.25D));
        ViewStreamMessage wrapped = TravelExtension.INSTANCE.wrap(cross);
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
        byte[] close = encode(TravelExtension.INSTANCE.wrap(new TravelMessage.RemoteLevelClose(4)));
        close[ViewStreamLimits.S2C_HEADER_BYTES] = 0;
        assertThrows(ViewStreamProtocolException.class, () -> decode(close, true));
    }

    @Test
    void routedPacketFragmentsMustDescribeTheirPacket() throws ViewStreamProtocolException {
        int total = TravelMessage.TRAVEL_FRAGMENT_BYTES + 5;
        TravelMessage.RoutedPacket first = new TravelMessage.RoutedPacket(4, 2, 0, 2, total, new byte[TravelMessage.TRAVEL_FRAGMENT_BYTES]);
        TravelMessage.RoutedPacket last = new TravelMessage.RoutedPacket(4, 2, 1, 2, total, new byte[5]);
        for (TravelMessage.RoutedPacket fragment : List.of(first, last)) {
            ViewStreamMessage wrapped = TravelExtension.INSTANCE.wrap(fragment);
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
        byte[] frame = encode(TravelExtension.INSTANCE.wrap(routedPacket(4, 0, 1, 3)));
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
            new MomentumRule(MomentumRule.Mode.IMPULSE, 1.0D, 2.0D, bad), ScaleRule.OFF));
        assertThrows(NullPointerException.class, () -> new TravelMessage.ArrivalRules(null, false, TravelMessage.ArrivalRules.FRAME.momentum(), ScaleRule.OFF));
        byte[] accept = encode(TravelExtension.INSTANCE.wrap(travelAccept(0)));
        byte[] invalid = accept.clone();
        buffer(invalid).putDouble(ViewStreamLimits.S2C_HEADER_BYTES + 32, Double.NaN);
        assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, true));
        TravelMessage.TravelBegin begin = ClientViewFixtures.travelBegin();
        byte[] frame = encode(TravelExtension.INSTANCE.wrap(begin));
        int rules = frame.length - 54;
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
        byte[] down = encode(TravelExtension.INSTANCE.wrap(new TravelMessage.RemoteLevelClose(4)));
        byte[] up = encode(TravelExtension.INSTANCE.wrap(new TravelMessage.RemoteViewAck(4, 0, 8)));
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
        assertThrows(ViewStreamProtocolException.class, () -> TRAVEL.decode(58, null));
    }

    @Test
    void theBukkitCodecCarriesNoTravel() throws ViewStreamProtocolException {
        assertEquals(ClientViewExtensions.FX_EMITTERS, ClientViewExtensions.CODEC.capabilities());
        ViewStreamMessage close = TravelExtension.INSTANCE.wrap(new TravelMessage.RemoteLevelClose(4));
        byte[] frame = encode(close);
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.decodeS2C(frame, ViewStreamCapability.ALL));
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.encodeS2C(close, 0, 0));
        byte[] cross = encode(TravelExtension.INSTANCE.wrap(new TravelMessage.TravelCross(TOKEN, 3, 9, ClientViewFixtures.travelBegin().arrival(),
            new Vec3d(0, 66, 0), new Vec3d(0, 66, 0.25D))));
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.decodeC2S(cross));
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
        assertEquals(List.of(47, 55, 56), serverbound);
        assertEquals(List.of(41, 46, 51, 52, 53, 54, 57), clientbound);
        assertEquals(41, TRAVEL.firstId());
        assertEquals(63, TRAVEL.lastId());
        assertEquals(ClientViewExtensions.REMOTE_VIEW | ClientViewExtensions.SEAMLESS_TRAVEL, TRAVEL.capabilities());
    }

    private static boolean identified(TravelMessage message) {
        return !(message instanceof TravelMessage.RemoteLevelOpen || message instanceof TravelMessage.RemoteLevelClose
            || message instanceof TravelMessage.RoutedPacket || message instanceof TravelMessage.RemoteViewAck
            || message instanceof TravelMessage.RemoteLevelReopen || message instanceof TravelMessage.EntityCrossed);
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

    private static TravelMessage.EntityCrossed entityCrossed(int handle) {
        return new TravelMessage.EntityCrossed(handle, 42, OpticTransform.translation(0.0D, 20.0D, 0.0D), new Vec3d(1.5D, 80.5D, 1.5D), Face.U,
            new Vec3d(0.0D, -3.5D, 0.25D));
    }

    private static TravelMessage.TravelBegin withResidency(TravelMessage.TravelBegin base, boolean resident, int handle) {
        return new TravelMessage.TravelBegin(base.token(), base.generation(), base.sourcePortal(), base.sourceWorld(), base.sourceGeometry(),
            base.destinationToSource(), 1.0F, base.world(), base.arrival(), base.environment(), base.rules(), resident, handle);
    }

    private static ApertureDescriptor geometry(ApertureDescriptor value, boolean mirror, int parent,
                                                  List<ApertureDescriptor> nested) {
        return new ApertureDescriptor(value.originX(), value.originY(), value.originZ(), value.facing(), value.frontSide(),
            value.quarterTurns(), mirror, value.apertureWidth(), value.apertureHeight(), value.apertureMask(), ShapeDescriptor.FULL,
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
