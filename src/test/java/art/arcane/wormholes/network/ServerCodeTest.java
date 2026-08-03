package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCodeTest {
    @Test
    void codeRoundTripsAllFields() throws Exception {
        ServerCode original = new ServerCode("hub", "play.example.com", List.of("203.0.113.7", "192.168.1.50"), 8901, 25565, publicKey());
        String encoded = original.encode();
        assertTrue(encoded.startsWith(ServerCode.PREFIX));

        ServerCode decoded = ServerCode.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    void typicalCodeFitsInChat() throws Exception {
        ServerCode code = new ServerCode("survival-main", "play.somewhere-long.example.com", List.of("203.0.113.7", "192.168.1.50"), 8901, 25565, publicKey());
        assertTrue(code.encode().length() <= 250, "typical code should be chat-pasteable, was " + code.encode().length());
    }

    @Test
    void decodeToleratesSurroundingWhitespace() throws Exception {
        ServerCode original = new ServerCode("hub", "10.0.0.2", List.of(), 8901, 25565, publicKey());
        assertEquals(original, ServerCode.decode("  " + original.encode() + " \n"));
    }

    @Test
    void invalidCodesReturnNull() throws Exception {
        assertNull(ServerCode.decode(null));
        assertNull(ServerCode.decode(""));
        assertNull(ServerCode.decode("not a code"));
        assertNull(ServerCode.decode("WHS1.!!!!not-base64!!!!"));
        assertNull(ServerCode.decode(ServerCode.PREFIX));

        String valid = new ServerCode("hub", "10.0.0.2", List.of(), 8901, 25565, publicKey()).encode();
        assertNull(ServerCode.decode(valid.substring(0, valid.length() - 10)), "truncated code must not decode");
    }

    @Test
    void portalCodesDoNotDecodeAsServerCodes() throws Exception {
        String portalCode = new PortalCode("hub", "10.0.0.2", List.of(), 8901, 25565, publicKey(), java.util.UUID.randomUUID(), "Gate").encode();
        assertNull(ServerCode.decode(portalCode));
    }

    @Test
    void blankRequiredFieldsRejected() throws Exception {
        String publicKey = publicKey();
        String blankServer = new ServerCode("", "10.0.0.2", List.of(), 8901, 25565, publicKey).encode();
        assertNull(ServerCode.decode(blankServer));
        String blankHost = new ServerCode("hub", "", List.of(), 8901, 25565, publicKey).encode();
        assertNull(ServerCode.decode(blankHost));
        String blankPublicKey = new ServerCode("hub", "10.0.0.2", List.of(), 8901, 25565, "").encode();
        assertNull(ServerCode.decode(blankPublicKey));
    }

    private static String publicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = generator.generateKeyPair();
        return Handshake.encodePublicKey(keyPair.getPublic().getEncoded());
    }
}
