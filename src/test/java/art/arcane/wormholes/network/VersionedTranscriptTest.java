package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rolling upgrades: the handshake transcript is written at the negotiated protocol version, never at the local layout. */
class VersionedTranscriptTest {
    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static WireMessage.Hello hello(KeyPair keys) {
        return new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "26.2", "2.1.0", "alpha", "10.0.0.1", 8901,
            new GameEndpoint("alpha.example", 25570), new GameEndpoint("10.0.0.1", 25565), Handshake.newNonce(),
            keys.getPublic().getEncoded(), true, CompressionDictionary.ZERO_HASH, 0, WireCapability.localSet());
    }

    private static WireMessage.Challenge challenge(KeyPair keys) {
        return new WireMessage.Challenge("beta", "10.0.0.2", 8902, new GameEndpoint("beta.example", 25580),
            new GameEndpoint("10.0.0.2", 25566), Handshake.newNonce(), keys.getPublic().getEncoded(), new byte[0], true,
            CompressionDictionary.ZERO_HASH, 0, WireCapability.localSet());
    }

    @Test
    void helloFromAFuturePeerWithATrailingFieldIsVerifiedByTheProtocol21Transcript() throws Exception {
        KeyPair keys = keyPair();
        WireMessage.Hello future = hello(keys);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        future.write(out);
        out.writeUTF("field-added-in-protocol-22");
        out.flush();

        WireMessage.Hello decoded = (WireMessage.Hello) WireCodec.decodePayload(WireMessageType.HELLO, buffer.toByteArray());
        assertEquals("alpha", decoded.serverName());
        assertArrayEquals(future.nonce(), decoded.nonce());

        WireMessage.Challenge challenge = challenge(keys);
        byte[] signature = Handshake.signTranscript(keys.getPrivate(), future, challenge, Handshake.ROLE_ACCEPTOR, WireCodec.PROTOCOL_VERSION);
        assertTrue(Handshake.verifyTranscript(keys.getPublic().getEncoded(), signature, decoded, challenge, Handshake.ROLE_ACCEPTOR, WireCodec.PROTOCOL_VERSION));
    }

    @Test
    void transcriptBindsTheNegotiatedVersionAndTheDialerProbesDownToIt() throws Exception {
        KeyPair keys = keyPair();
        WireMessage.Hello hello = hello(keys);
        WireMessage.Challenge challenge = challenge(keys);
        byte[] signedAt21 = Handshake.signTranscript(keys.getPrivate(), hello, challenge, Handshake.ROLE_ACCEPTOR, WireCodec.PROTOCOL_VERSION);

        assertFalse(Handshake.verifyTranscript(keys.getPublic().getEncoded(), signedAt21, hello, challenge, Handshake.ROLE_ACCEPTOR, WireCodec.PROTOCOL_VERSION + 1));
        assertEquals(WireCodec.PROTOCOL_VERSION, Handshake.negotiateTranscriptVersion(keys.getPublic().getEncoded(), signedAt21, hello, challenge,
            Handshake.ROLE_ACCEPTOR, WireCodec.PROTOCOL_VERSION + 1));
        assertEquals(-1, Handshake.negotiateTranscriptVersion(keyPair().getPublic().getEncoded(), signedAt21, hello, challenge,
            Handshake.ROLE_ACCEPTOR, WireCodec.PROTOCOL_VERSION + 1));
        assertTrue(Handshake.verifyTranscript(keys.getPublic().getEncoded(), signedAt21, hello, challenge, Handshake.ROLE_ACCEPTOR));
    }

    @Test
    void newerPeerProtocolsAreCompatibleAndOlderOnesAreNot() {
        assertTrue(WireCodec.isCompatibleProtocol(WireCodec.PROTOCOL_VERSION));
        assertTrue(WireCodec.isCompatibleProtocol(WireCodec.PROTOCOL_VERSION + 1));
        assertFalse(WireCodec.isCompatibleProtocol(WireCodec.MIN_COMPATIBLE_PROTOCOL - 1));
        assertEquals(WireCodec.PROTOCOL_VERSION, WireCodec.negotiatedProtocol(WireCodec.PROTOCOL_VERSION + 3));
        assertEquals(WireCodec.MIN_COMPATIBLE_PROTOCOL, WireCodec.negotiatedProtocol(WireCodec.MIN_COMPATIBLE_PROTOCOL));
        assertTrue(MinecraftStatusBridge.isCompatibleFormat(MinecraftStatusBridge.MIN_COMPATIBLE_FORMAT));
        assertTrue(MinecraftStatusBridge.isCompatibleFormat(MinecraftStatusBridge.MIN_COMPATIBLE_FORMAT + 1));
        assertFalse(MinecraftStatusBridge.isCompatibleFormat(MinecraftStatusBridge.MIN_COMPATIBLE_FORMAT - 1));
    }
}
