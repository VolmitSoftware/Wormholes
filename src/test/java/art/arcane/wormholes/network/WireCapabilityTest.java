package art.arcane.wormholes.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import org.junit.jupiter.api.Test;

final class WireCapabilityTest {
    @Test
    void baselineBitIsAlwaysInTheLocalSetAndProtocolRangeIsExplicit() {
        assertTrue(WireCapability.PROTOCOL_21.in(WireCapability.localSet()));
        assertEquals(1L, WireCapability.PROTOCOL_21.mask());
        assertTrue(WireCodec.isCompatibleProtocol(WireCodec.PROTOCOL_VERSION));
        assertFalse(WireCodec.isCompatibleProtocol(WireCodec.MIN_COMPATIBLE_PROTOCOL - 1));
        assertTrue(WireCodec.isCompatibleProtocol(WireCodec.PROTOCOL_VERSION + 1));
    }

    @Test
    void capabilitiesRoundTripThroughHelloAndChallengePayloads() throws Exception {
        KeyPair keys = keyPair();
        long advertised = WireCapability.PROTOCOL_21.mask() | (1L << 40);
        WireMessage.Hello hello = new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "26.2", "test", "alpha", "10.0.0.1", 8901,
            new GameEndpoint("alpha.example", 25570), new GameEndpoint("10.0.0.1", 25565), Handshake.newNonce(),
            keys.getPublic().getEncoded(), true, CompressionDictionary.ZERO_HASH, 0, advertised);
        WireMessage.Challenge challenge = new WireMessage.Challenge("beta", "10.0.0.2", 8902,
            new GameEndpoint("beta.example", 25580), new GameEndpoint("10.0.0.2", 25566), Handshake.newNonce(),
            keys.getPublic().getEncoded(), new byte[0], true, CompressionDictionary.ZERO_HASH, 0, advertised);

        WireMessage.Hello decodedHello = WireMessage.Hello.read(new DataInputStream(new ByteArrayInputStream(WireCodec.encodePayload(hello))));
        WireMessage.Challenge decodedChallenge = WireMessage.Challenge.read(new DataInputStream(new ByteArrayInputStream(WireCodec.encodePayload(challenge))));

        assertEquals(advertised, decodedHello.capabilities());
        assertEquals(advertised, decodedChallenge.capabilities());
    }

    @Test
    void handshakeTranscriptBindsTheCapabilitySet() throws Exception {
        KeyPair signer = keyPair();
        WireMessage.Hello hello = new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "26.2", "test", "alpha", "10.0.0.1", 8901,
            new GameEndpoint("alpha.example", 25570), new GameEndpoint("10.0.0.1", 25565), Handshake.newNonce(),
            signer.getPublic().getEncoded(), true, CompressionDictionary.ZERO_HASH, 0, WireCapability.localSet());
        WireMessage.Challenge challenge = new WireMessage.Challenge("beta", "10.0.0.2", 8902,
            new GameEndpoint("beta.example", 25580), new GameEndpoint("10.0.0.2", 25566), Handshake.newNonce(),
            signer.getPublic().getEncoded(), new byte[0], true, CompressionDictionary.ZERO_HASH, 0, WireCapability.localSet());
        byte[] signature = Handshake.signTranscript(signer.getPrivate(), hello, challenge, Handshake.ROLE_ACCEPTOR);
        assertTrue(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, hello, challenge, Handshake.ROLE_ACCEPTOR));

        WireMessage.Hello tamperedHello = new WireMessage.Hello(hello.protocolVersion(), hello.mcVersion(), hello.pluginVersion(),
            hello.serverName(), hello.advertiseHost(), hello.wormholePort(), hello.gameEndpoint(), hello.privateGameEndpoint(),
            hello.nonce(), hello.publicKey(), hello.compressionSupported(), hello.currentDictHash(), hello.currentDictVersion(),
            hello.capabilities() | (1L << 63));
        assertFalse(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, tamperedHello, challenge, Handshake.ROLE_ACCEPTOR));

        WireMessage.Challenge tamperedChallenge = new WireMessage.Challenge(challenge.serverName(), challenge.advertiseHost(),
            challenge.wormholePort(), challenge.gameEndpoint(), challenge.privateGameEndpoint(), challenge.nonce(),
            challenge.publicKey(), challenge.signature(), challenge.compressionSupported(), challenge.currentDictHash(),
            challenge.currentDictVersion(), 0L);
        assertFalse(Handshake.verifyTranscript(signer.getPublic().getEncoded(), signature, hello, tamperedChallenge, Handshake.ROLE_ACCEPTOR));
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
