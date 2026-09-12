package art.arcane.wormholes.network.convoy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.WireCodec;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.WireMessageType;
import art.arcane.wormholes.network.WireTraversive;

final class ConvoyManifestCodecTest {
    @Test
    void manifestRoundTripsThroughTheConvoyTransferPayload() throws IOException {
        UUID groupId = UUID.randomUUID();
        UUID portalId = UUID.randomUUID();
        UUID boatId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID horseId = UUID.randomUUID();
        WireTraversive traversive = traversive(1.0D);
        ConvoyManifest manifest = new ConvoyManifest(groupId, portalId, List.of(
            new ConvoyManifest.Member(boatId, bytes("boat"), null, null, traversive, false),
            new ConvoyManifest.Member(playerId, new byte[0], boatId, null, traversive(2.0D), true),
            new ConvoyManifest.Member(horseId, bytes("horse"), null, playerId, traversive(3.0D), false)));

        WireMessage.ConvoyTransfer decoded = assertInstanceOf(WireMessage.ConvoyTransfer.class, roundTrip(new WireMessage.ConvoyTransfer(manifest)));

        assertEquals(WireMessageType.CONVOY_TRANSFER, decoded.type());
        assertEquals((byte) 38, decoded.type().id());
        ConvoyManifest copy = decoded.manifest();
        assertEquals(groupId, copy.groupId());
        assertEquals(portalId, copy.destPortalId());
        assertEquals(3, copy.members().size());
        assertEquals(boatId, copy.members().get(0).entityId());
        assertArrayEquals(bytes("boat"), copy.members().get(0).snapshot());
        assertNull(copy.members().get(0).vehicleId());
        assertNull(copy.members().get(0).leashHolderId());
        assertFalse(copy.members().get(0).rider());
        assertEquals(boatId, copy.members().get(1).vehicleId());
        assertTrue(copy.members().get(1).rider());
        assertEquals(0, copy.members().get(1).snapshot().length);
        assertEquals(playerId, copy.members().get(2).leashHolderId());
        assertEquals(3.0D, copy.members().get(2).traversive().pointX());
        assertEquals(playerId, copy.playerId());
        assertEquals(playerId, copy.rider().entityId());
    }

    @Test
    void convoyAckRoundTripsWithReason() throws IOException {
        UUID groupId = UUID.randomUUID();
        WireMessage.ConvoyAck accepted = assertInstanceOf(WireMessage.ConvoyAck.class, roundTrip(new WireMessage.ConvoyAck(groupId, true, "admitted")));
        assertEquals(groupId, accepted.groupId());
        assertTrue(accepted.accepted());
        assertEquals("admitted", accepted.reason());
        assertEquals((byte) 39, accepted.type().id());
        WireMessage.ConvoyAck denied = assertInstanceOf(WireMessage.ConvoyAck.class, roundTrip(new WireMessage.ConvoyAck(groupId, false, "portal closed")));
        assertFalse(denied.accepted());
        assertEquals("portal closed", denied.reason());
    }

    @Test
    void manifestsWithoutARiderHaveNoPlayerAndOversizedManifestsAreRejected() {
        ConvoyManifest riderless = new ConvoyManifest(UUID.randomUUID(), UUID.randomUUID(), List.of(
            new ConvoyManifest.Member(UUID.randomUUID(), bytes("boat"), null, null, traversive(1.0D), false)));
        assertNull(riderless.rider());
        assertNull(riderless.playerId());

        List<ConvoyManifest.Member> tooMany = new ArrayList<ConvoyManifest.Member>();
        for (int index = 0; index <= ConvoyManifest.MAX_MEMBERS; index++) {
            tooMany.add(new ConvoyManifest.Member(UUID.randomUUID(), bytes("m" + index), null, null, traversive(index), false));
        }
        ConvoyManifest oversized = new ConvoyManifest(UUID.randomUUID(), UUID.randomUUID(), tooMany);
        assertThrows(IOException.class, () -> WireCodec.encodePayload(new WireMessage.ConvoyTransfer(oversized)));
    }

    private static WireMessage roundTrip(WireMessage message) throws IOException {
        return WireCodec.decodePayload(message.type(), WireCodec.encodePayload(message));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static WireTraversive traversive(double pointX) {
        return new WireTraversive("N", "E", "U", 10.0D, 64.0D, 20.0D, pointX, 64.5D, 20.0D, 0.0D, 0.0D, -0.4D, 0.0D, 0.0D, -1.0D, true);
    }
}
