package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakeTest {
    @Test
    void transcriptBindsEndpointsVersionsCompressionAndRole() throws Exception {
        KeyPair signer = keyPair();
        KeyPair peer = keyPair();
        WireMessage.Hello hello = new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "26.2", "test", "alpha",
            "10.0.0.1", 8901, new GameEndpoint("alpha.example", 25570), new GameEndpoint("10.0.0.1", 25565),
            Handshake.newNonce(), peer.getPublic().getEncoded(), true, CompressionDictionary.ZERO_HASH, 0);
        WireMessage.Challenge challenge = new WireMessage.Challenge("beta", "10.0.0.2", 8902,
            new GameEndpoint("beta.example", 25580), new GameEndpoint("10.0.0.2", 25566),
            Handshake.newNonce(), signer.getPublic().getEncoded(), new byte[0], true, CompressionDictionary.ZERO_HASH, 0);
        byte[] signature = Handshake.signTranscript(signer.getPrivate(), hello, challenge, Handshake.ROLE_ACCEPTOR);
        assertTrue(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, hello, challenge, Handshake.ROLE_ACCEPTOR));
        assertFalse(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, hello, challenge, Handshake.ROLE_DIALER));
        assertFalse(Handshake.verifyTranscript(peer.getPublic().getEncoded(), signature, hello, challenge, Handshake.ROLE_ACCEPTOR));

        WireMessage.Challenge wrongPort = new WireMessage.Challenge(challenge.serverName(), challenge.advertiseHost(),
            challenge.wormholePort(), new GameEndpoint("beta.example", 25581), challenge.privateGameEndpoint(),
            challenge.nonce(), challenge.publicKey(), challenge.signature(), challenge.compressionSupported(),
            challenge.currentDictHash(), challenge.currentDictVersion());
        assertFalse(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, hello, wrongPort, Handshake.ROLE_ACCEPTOR));

        WireMessage.Hello wrongPrivate = new WireMessage.Hello(hello.protocolVersion(), hello.mcVersion(), hello.pluginVersion(),
            hello.serverName(), hello.advertiseHost(), hello.wormholePort(), hello.gameEndpoint(), new GameEndpoint("10.0.0.9", 25565),
            hello.nonce(), hello.publicKey(), hello.compressionSupported(), hello.currentDictHash(), hello.currentDictVersion());
        assertFalse(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, wrongPrivate, challenge, Handshake.ROLE_ACCEPTOR));

        WireMessage.Hello wrongVersion = new WireMessage.Hello(hello.protocolVersion(), "1.21.11", hello.pluginVersion(),
            hello.serverName(), hello.advertiseHost(), hello.wormholePort(), hello.gameEndpoint(), hello.privateGameEndpoint(),
            hello.nonce(), hello.publicKey(), false, hello.currentDictHash(), hello.currentDictVersion());
        assertFalse(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, wrongVersion, challenge, Handshake.ROLE_ACCEPTOR));
    }

    @Test
    void publicKeyTextRoundTrips() throws Exception {
        KeyPair keyPair = keyPair();
        String encoded = Handshake.encodePublicKey(keyPair.getPublic().getEncoded());
        byte[] decoded = Handshake.decodePublicKeyText(encoded);
        assertNotNull(decoded);
        assertTrue(Handshake.sameKey(keyPair.getPublic().getEncoded(), decoded));
    }

    @Test
    void noncesAreUniqueAndCorrectLength() {
        byte[] first = Handshake.newNonce();
        byte[] second = Handshake.newNonce();
        assertEquals(Handshake.NONCE_LENGTH, first.length);
        assertFalse(Handshake.sameKey(first, second));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        return generator.generateKeyPair();
    }
}
