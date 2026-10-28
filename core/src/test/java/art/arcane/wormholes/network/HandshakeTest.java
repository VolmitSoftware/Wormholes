package art.arcane.wormholes.network;

import art.arcane.volmlib.util.crypto.Ed25519;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakeTest {
    @Test
    void transcriptBindsEndpointsVersionsCompressionAndRole() throws Exception {
        KeyPair signer = keyPair();
        KeyPair peer = keyPair();
        WireMessage.Hello hello = new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "26.2", "test", "alpha",
            "10.0.0.1", 8901, new GameEndpoint("alpha.example", 25570), new GameEndpoint("10.0.0.1", 25565),
            Ed25519.newNonce(), peer.getPublic().getEncoded(), true, CompressionDictionary.ZERO_HASH, 0, WireCapability.localSet());
        WireMessage.Challenge challenge = new WireMessage.Challenge("beta", "10.0.0.2", 8902,
            new GameEndpoint("beta.example", 25580), new GameEndpoint("10.0.0.2", 25566),
            Ed25519.newNonce(), signer.getPublic().getEncoded(), new byte[0], true, CompressionDictionary.ZERO_HASH, 0, WireCapability.localSet());
        byte[] signature = Handshake.signTranscript(signer.getPrivate(), hello, challenge, Handshake.ROLE_ACCEPTOR);
        assertTrue(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, hello, challenge, Handshake.ROLE_ACCEPTOR));
        assertFalse(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, hello, challenge, Handshake.ROLE_DIALER));
        assertFalse(Handshake.verifyTranscript(peer.getPublic().getEncoded(), signature, hello, challenge, Handshake.ROLE_ACCEPTOR));

        WireMessage.Challenge wrongPort = new WireMessage.Challenge(challenge.serverName(), challenge.advertiseHost(),
            challenge.wormholePort(), new GameEndpoint("beta.example", 25581), challenge.privateGameEndpoint(),
            challenge.nonce(), challenge.publicKey(), challenge.signature(), challenge.compressionSupported(),
            challenge.currentDictHash(), challenge.currentDictVersion(), WireCapability.localSet());
        assertFalse(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, hello, wrongPort, Handshake.ROLE_ACCEPTOR));

        WireMessage.Hello wrongPrivate = new WireMessage.Hello(hello.protocolVersion(), hello.mcVersion(), hello.pluginVersion(),
            hello.serverName(), hello.advertiseHost(), hello.wormholePort(), hello.gameEndpoint(), new GameEndpoint("10.0.0.9", 25565),
            hello.nonce(), hello.publicKey(), hello.compressionSupported(), hello.currentDictHash(), hello.currentDictVersion(), WireCapability.localSet());
        assertFalse(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, wrongPrivate, challenge, Handshake.ROLE_ACCEPTOR));

        WireMessage.Hello wrongVersion = new WireMessage.Hello(hello.protocolVersion(), "1.21.11", hello.pluginVersion(),
            hello.serverName(), hello.advertiseHost(), hello.wormholePort(), hello.gameEndpoint(), hello.privateGameEndpoint(),
            hello.nonce(), hello.publicKey(), false, hello.currentDictHash(), hello.currentDictVersion(), WireCapability.localSet());
        assertFalse(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, wrongVersion, challenge, Handshake.ROLE_ACCEPTOR));
    }

    @Test
    void publicKeyTextRoundTrips() throws Exception {
        KeyPair keyPair = keyPair();
        String encoded = Ed25519.encodePublicKeyText(keyPair.getPublic().getEncoded());
        byte[] decoded = Handshake.decodePublicKeyText(encoded);
        assertNotNull(decoded);
        assertTrue(Ed25519.sameKey(keyPair.getPublic().getEncoded(), decoded));
    }

    @Test
    void noncesAreUniqueAndCorrectLength() {
        byte[] first = Ed25519.newNonce();
        byte[] second = Ed25519.newNonce();
        assertEquals(Handshake.NONCE_LENGTH, first.length);
        assertFalse(Ed25519.sameKey(first, second));
    }

    @Test
    void prefixedSignaturesMatchStreamedEd25519Signatures() throws Exception {
        KeyPair keys = keyPair();
        byte[] prefix = new byte[]{1, 2, 3};
        byte[] payload = new byte[]{4, 5, 6, 7};
        Signature streamed = Signature.getInstance("Ed25519");
        streamed.initSign(keys.getPrivate());
        streamed.update(prefix);
        streamed.update(payload);
        byte[] reference = streamed.sign();

        assertArrayEquals(reference, Handshake.sign(keys.getPrivate(), prefix, payload));
        assertTrue(Handshake.verify(keys.getPublic(), reference, prefix, payload));
        assertFalse(Handshake.verify(keys.getPublic(), reference, payload, prefix));
    }

    @Test
    void byteKeyVerificationRejectsInvalidKeysAndSignatures() throws Exception {
        KeyPair keys = keyPair();
        byte[] payload = new byte[]{9, 8, 7};
        byte[] signature = Handshake.sign(keys.getPrivate(), payload);

        assertTrue(Handshake.verify(keys.getPublic().getEncoded(), signature, payload));
        assertFalse(Handshake.verify(new byte[]{1, 2, 3}, signature, payload));
        assertFalse(Handshake.verify(keys.getPublic().getEncoded(), new byte[]{1, 2, 3}, payload));
        assertFalse(Handshake.verify(null, signature, payload));
    }

    @Test
    void fingerprintIsTheFirstEightSha256BytesOfTheEncodedKey() throws Exception {
        byte[] encoded = keyPair().getPublic().getEncoded();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(encoded);
        StringBuilder expected = new StringBuilder();
        for (int index = 0; index < 8; index++) {
            if (index > 0) {
                expected.append(':');
            }
            expected.append(String.format("%02x", hash[index] & 0xFF));
        }

        assertEquals(expected.toString(), Handshake.fingerprint(encoded));
        assertEquals("unknown", Handshake.fingerprint(null));
    }

    @Test
    void invalidPublicKeyTextDecodesToNull() {
        assertNull(Handshake.decodePublicKeyText(null));
        assertNull(Handshake.decodePublicKeyText(" "));
        assertNull(Handshake.decodePublicKeyText("not a key!"));
        assertNull(Handshake.decodePublicKeyText(Ed25519.encodePublicKeyText(new byte[]{1, 2, 3})));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }
}
