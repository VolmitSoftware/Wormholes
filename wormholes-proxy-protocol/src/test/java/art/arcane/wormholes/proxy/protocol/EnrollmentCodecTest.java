package art.arcane.wormholes.proxy.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollmentCodecTest {
    private static final byte[] SECRET = "shared".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IMPOSTOR = "guessed".getBytes(StandardCharsets.UTF_8);

    @Test
    void everyFrameRoundTripsThroughLengthPrefixedUtf() throws IOException {
        UUID nonce = UUID.randomUUID();
        EnrollmentCodec.Enroll enroll = new EnrollmentCodec.Enroll("survival", "WHS2.abc", 0x1F01L, List.of("Hub", "Mine"), nonce);
        EnrollmentCodec.Enroll decodedEnroll = assertInstanceOf(EnrollmentCodec.Enroll.class,
            EnrollmentCodec.decode(EnrollmentCodec.encode(enroll, SECRET), SECRET));
        assertEquals(enroll, decodedEnroll);

        EnrollmentCodec.Roster roster = new EnrollmentCodec.Roster(nonce, List.of("WHS2.abc", "WHS2.def"));
        assertEquals(roster, EnrollmentCodec.decode(EnrollmentCodec.encode(roster, SECRET), SECRET));

        UUID transferId = UUID.randomUUID();
        EnrollmentCodec.Handoff handoff = new EnrollmentCodec.Handoff(transferId, "creative");
        assertEquals(handoff, EnrollmentCodec.decode(EnrollmentCodec.encode(handoff, SECRET), SECRET));

        EnrollmentCodec.Receipt receipt = new EnrollmentCodec.Receipt(transferId, false, "server offline");
        assertEquals(receipt, EnrollmentCodec.decode(EnrollmentCodec.encode(receipt, SECRET), SECRET));
        assertEquals("wormholes:proxy", EnrollmentCodec.CHANNEL);
    }

    @Test
    void aFrameSignedWithAnotherSecretOrNoneAtAllIsRefused() {
        byte[] signed = EnrollmentCodec.encode(new EnrollmentCodec.Roster(UUID.randomUUID(), List.of("WHS2.abc")), IMPOSTOR);
        assertThrows(IOException.class, () -> EnrollmentCodec.decode(signed, SECRET));

        byte[] tampered = EnrollmentCodec.encode(new EnrollmentCodec.Handoff(UUID.randomUUID(), "creative"), SECRET);
        tampered[tampered.length - 1] ^= 0x7F;
        assertThrows(IOException.class, () -> EnrollmentCodec.decode(tampered, SECRET));

        byte[] body = EnrollmentCodec.encode(new EnrollmentCodec.Handoff(UUID.randomUUID(), "creative"), SECRET);
        byte[] untagged = new byte[body.length - EnrollmentCodec.TAG_BYTES];
        System.arraycopy(body, 0, untagged, 0, untagged.length);
        assertThrows(IOException.class, () -> EnrollmentCodec.decode(untagged, SECRET));

        assertThrows(IllegalArgumentException.class, () -> EnrollmentCodec.decode(body, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> EnrollmentCodec.encode(new EnrollmentCodec.Receipt(UUID.randomUUID(), true, ""), null));
    }

    @Test
    void malformedFramesAreRejected() {
        assertThrows(IOException.class, () -> EnrollmentCodec.decode(new byte[0], SECRET));
        assertThrows(IOException.class, () -> EnrollmentCodec.decode(new byte[] {0, 0, 0, 9, 1}, SECRET));
        byte[] handoff = EnrollmentCodec.encode(new EnrollmentCodec.Handoff(UUID.randomUUID(), "x"), SECRET);
        handoff[4] = 99;
        assertThrows(IOException.class, () -> EnrollmentCodec.decode(handoff, SECRET));
        byte[] truncated = new byte[handoff.length - 3];
        System.arraycopy(EnrollmentCodec.encode(new EnrollmentCodec.Handoff(UUID.randomUUID(), "x"), SECRET), 0, truncated, 0, truncated.length);
        assertThrows(IOException.class, () -> EnrollmentCodec.decode(truncated, SECRET));
        assertTrue(EnrollmentCodec.encode(new EnrollmentCodec.Roster(UUID.randomUUID(), List.of()), SECRET).length >= 5);
    }

    @Test
    void theProxySecretFileIsGeneratedOnceAndReused(@TempDir Path directory) throws IOException {
        String first = ProxySecret.loadOrCreate(directory);
        assertTrue(first.length() >= 32);
        assertEquals(first, ProxySecret.loadOrCreate(directory));
        assertTrue(Files.isRegularFile(directory.resolve(ProxySecret.FILE)));

        Files.writeString(directory.resolve(ProxySecret.FILE), "   ");
        assertNotEquals(first, ProxySecret.loadOrCreate(directory));
        assertNull(ProxySecret.of("  "));
        assertNull(ProxySecret.of(null));
        assertNotNull(ProxySecret.of(" padded "));
    }
}
