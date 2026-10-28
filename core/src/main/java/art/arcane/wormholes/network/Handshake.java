package art.arcane.wormholes.network;

import art.arcane.volmlib.util.crypto.Ed25519;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

public final class Handshake {
    public static final int NONCE_LENGTH = 32;
    public static final int SIGNATURE_MAX_LENGTH = 512;
    public static final int PUBLIC_KEY_MAX_LENGTH = 512;
    public static final String ROLE_ACCEPTOR = "acceptor";
    public static final String ROLE_DIALER = "dialer";

    private Handshake() {
    }

    static byte[] signTranscript(PrivateKey key, WireMessage.Hello hello, WireMessage.Challenge challenge, String role) {
        return signTranscript(key, hello, challenge, role, WireCodec.PROTOCOL_VERSION);
    }

    static byte[] signTranscript(PrivateKey key, WireMessage.Hello hello, WireMessage.Challenge challenge, String role, int negotiatedVersion) {
        return sign(key, transcript(hello, challenge, role, negotiatedVersion));
    }

    static boolean verifyTranscript(byte[] key, byte[] signature, WireMessage.Hello hello,
                                    WireMessage.Challenge challenge, String role) {
        return verifyTranscript(key, signature, hello, challenge, role, WireCodec.PROTOCOL_VERSION);
    }

    static boolean verifyTranscript(byte[] key, byte[] signature, WireMessage.Hello hello,
                                    WireMessage.Challenge challenge, String role, int negotiatedVersion) {
        return verify(key, signature, transcript(hello, challenge, role, negotiatedVersion));
    }

    /**
     * Dialer-side negotiation: the acceptor signed at min(its version, ours) but the challenge does not
     * say which; try every layout from {@code fromVersion} down to the minimum. Returns the version that
     * verifies, or -1 when none does.
     */
    static int negotiateTranscriptVersion(byte[] key, byte[] signature, WireMessage.Hello hello,
                                          WireMessage.Challenge challenge, String role, int fromVersion) {
        for (int version = Math.min(fromVersion, WireCodec.PROTOCOL_VERSION); version >= WireCodec.MIN_COMPATIBLE_PROTOCOL; version--) {
            if (verifyTranscript(key, signature, hello, challenge, role, version)) {
                return version;
            }
        }
        return -1;
    }

    /** Role, negotiated version, then both handshake messages written at that version (challenge unsigned). */
    private static byte[] transcript(WireMessage.Hello hello, WireMessage.Challenge challenge, String role, int negotiatedVersion) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF(role);
            output.writeInt(negotiatedVersion);
            hello.writeTranscript(output, negotiatedVersion);
            WireMessage.Challenge unsigned = new WireMessage.Challenge(challenge.serverName(), challenge.advertiseHost(),
                challenge.wormholePort(), challenge.gameEndpoint(), challenge.privateGameEndpoint(), challenge.nonce(),
                challenge.publicKey(), new byte[0], challenge.compressionSupported(), challenge.currentDictHash(),
                challenge.currentDictVersion(), challenge.capabilities());
            unsigned.writeTranscript(output, negotiatedVersion);
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Could not encode peer handshake transcript", error);
        }
    }

    public static byte[] sign(PrivateKey privateKey, byte[] payload) {
        try {
            return Ed25519.sign(privateKey, payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing unavailable", e);
        }
    }

    public static byte[] sign(PrivateKey privateKey, byte[] prefix, byte[] payload) {
        return sign(privateKey, concat(prefix, payload));
    }

    public static boolean verify(byte[] publicKey, byte[] signatureBytes, byte[] payload) {
        if (publicKey == null || signatureBytes == null || payload == null) {
            return false;
        }
        try {
            return Ed25519.verify(Ed25519.decodePublicKey(publicKey), signatureBytes, payload);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public static boolean verify(PublicKey publicKey, byte[] signatureBytes, byte[] prefix, byte[] payload) {
        if (publicKey == null || signatureBytes == null || prefix == null || payload == null) {
            return false;
        }
        return Ed25519.verify(publicKey, signatureBytes, concat(prefix, payload));
    }

    public static byte[] decodePublicKeyText(String publicKey) {
        if (publicKey == null || publicKey.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(publicKey.trim());
            Ed25519.decodePublicKey(decoded);
            return decoded;
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return null;
        }
    }

    public static String fingerprint(byte[] publicKey) {
        return publicKey == null ? "unknown" : Ed25519.fingerprint(publicKey);
    }

    private static byte[] concat(byte[] prefix, byte[] payload) {
        byte[] joined = new byte[prefix.length + payload.length];
        System.arraycopy(prefix, 0, joined, 0, prefix.length);
        System.arraycopy(payload, 0, joined, prefix.length, payload.length);
        return joined;
    }
}
