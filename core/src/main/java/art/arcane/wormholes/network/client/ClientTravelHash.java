package art.arcane.wormholes.network.client;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class ClientTravelHash {
    private ClientTravelHash() {
    }

    public static byte[] of(byte[] payload) {
        Objects.requireNonNull(payload);
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
