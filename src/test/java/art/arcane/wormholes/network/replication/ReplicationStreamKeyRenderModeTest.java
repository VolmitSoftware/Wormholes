package art.arcane.wormholes.network.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.WireCodec;
import art.arcane.wormholes.portal.ProjectionRenderMode;

public final class ReplicationStreamKeyRenderModeTest {
    @Test
    public void plannarOpticRoundTripsAcrossProtocolSeventeen() throws Exception {
        ReplicationStreamKey expected = new ReplicationStreamKey(
            UUID.fromString("02bea2f7-d38a-4c91-9b63-d7ff73138abd"),
            UUID.fromString("bdd02033-e8e6-4c4f-9343-8004815eb642"),
            9817234L,
            ProjectionRenderMode.PLANNAR_OPTIC);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        expected.writeTo(new DataOutputStream(bytes));

        ReplicationStreamKey decoded = ReplicationStreamKey.read(
            new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertEquals(17, WireCodec.PROTOCOL_VERSION);
        assertEquals(expected, decoded);
    }
}
