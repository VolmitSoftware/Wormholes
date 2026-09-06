package art.arcane.wormholes.network;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class Handshake {
    public static final int NONCE_LENGTH = 32;
    public static final int SIGNATURE_MAX_LENGTH = 512;
    public static final int PUBLIC_KEY_MAX_LENGTH = 512;
    public static final String ROLE_ACCEPTOR = "acceptor";
    public static final String ROLE_DIALER = "dialer";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_ALGORITHM = "Ed25519";
    private static final String SIGNATURE_ALGORITHM = "Ed25519";

    private Handshake() {
    }

    public static byte[] newNonce() {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    static byte[] signTranscript(PrivateKey key, WireMessage.Hello hello, WireMessage.Challenge challenge, String role) {
        return sign(key, transcript(hello, challenge, role));
    }

    static boolean verifyTranscript(byte[] key, byte[] signature, WireMessage.Hello hello,
                                    WireMessage.Challenge challenge, String role) {
        return verify(key, signature, transcript(hello, challenge, role));
    }

    private static byte[] transcript(WireMessage.Hello hello, WireMessage.Challenge challenge, String role) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF(role);
            hello.write(output);
            WireMessage.Challenge unsigned = new WireMessage.Challenge(challenge.serverName(), challenge.advertiseHost(),
                challenge.wormholePort(), challenge.gameEndpoint(), challenge.privateGameEndpoint(), challenge.nonce(),
                challenge.publicKey(), new byte[0], challenge.compressionSupported(), challenge.currentDictHash(),
                challenge.currentDictVersion());
            unsigned.write(output);
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Could not encode peer handshake transcript", error);
        }
    }

    public static byte[] sign(PrivateKey privateKey, byte[] payload) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing unavailable", e);
        }
    }

    public static byte[] sign(PrivateKey privateKey, byte[] prefix, byte[] payload) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(prefix);
            signature.update(payload);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing unavailable", e);
        }
    }

    public static boolean verify(byte[] publicKey, byte[] signatureBytes, byte[] payload) {
        if (publicKey == null || signatureBytes == null || payload == null) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(decodePublicKey(publicKey));
            signature.update(payload);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean verify(PublicKey publicKey, byte[] signatureBytes, byte[] prefix, byte[] payload) {
        if (publicKey == null || signatureBytes == null || prefix == null || payload == null) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(prefix);
            signature.update(payload);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    public static PublicKey decodePublicKey(byte[] publicKey) throws Exception {
        if (publicKey == null || publicKey.length == 0 || publicKey.length > PUBLIC_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid public key length");
        }
        KeyFactory factory = KeyFactory.getInstance(KEY_ALGORITHM);
        return factory.generatePublic(new X509EncodedKeySpec(publicKey));
    }

    public static String encodePublicKey(byte[] publicKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey);
    }

    public static byte[] decodePublicKeyText(String publicKey) {
        if (publicKey == null || publicKey.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(publicKey.trim());
            decodePublicKey(decoded);
            return decoded;
        } catch (Exception e) {
            return null;
        }
    }

    public static String fingerprint(byte[] publicKey) {
        if (publicKey == null) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey);
            StringBuilder builder = new StringBuilder(23);
            for (int i = 0; i < 8; i++) {
                if (i > 0) {
                    builder.append(':');
                }
                String hex = Integer.toHexString(hash[i] & 0xFF);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static boolean sameKey(byte[] expected, byte[] actual) {
        return expected != null && actual != null && MessageDigest.isEqual(expected, actual);
    }

}
