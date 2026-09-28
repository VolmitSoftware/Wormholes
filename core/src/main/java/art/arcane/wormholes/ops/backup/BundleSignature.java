package art.arcane.wormholes.ops.backup;

import art.arcane.wormholes.network.Handshake;

import java.util.Map;

/**
 * Who signed a bundle, judged against keys this server already knows: its own identity and the peers
 * in the trust store. A bundle carries the public key in its own manifest, so verifying against that
 * alone only proves the zip is internally consistent - anyone can re-sign a rewritten manifest with
 * their own key. Matching the key against a known one is what names the sender.
 */
public record BundleSignature(Verdict verdict, String signer, String fingerprint) {
    public enum Verdict {
        /** Signed by this server's own identity. */
        LOCAL,
        /** Signed by a peer in trust/peers.properties. */
        TRUSTED_PEER,
        /** The signature verifies, but against a key this server has never trusted. */
        UNKNOWN_KEY,
        /** Signed, and the signature does not match the contents. */
        BROKEN,
        /** No signature at all. */
        UNSIGNED
    }

    public boolean trusted() {
        return verdict == Verdict.LOCAL || verdict == Verdict.TRUSTED_PEER;
    }

    /** Whether a restore may apply this bundle. A broken signature never may. */
    public boolean permitsRestore(boolean allowUnsigned) {
        return switch (verdict) {
            case LOCAL, TRUSTED_PEER -> true;
            case BROKEN -> false;
            case UNKNOWN_KEY, UNSIGNED -> allowUnsigned;
        };
    }

    public static BundleSignature of(BackupBundle bundle, byte[] localKey, Map<String, byte[]> trustedKeys) {
        if (bundle == null || !bundle.signed()) {
            return new BundleSignature(Verdict.UNSIGNED, "", "unsigned");
        }
        byte[] signingKey = bundle.signingKey();
        String fingerprint = signingKey.length == 0 ? "unsigned" : Handshake.fingerprint(signingKey);
        if (!bundle.signatureValid()) {
            return new BundleSignature(Verdict.BROKEN, bundle.manifest().serverName(), fingerprint);
        }
        if (localKey != null && localKey.length > 0 && Handshake.sameKey(localKey, signingKey)) {
            return new BundleSignature(Verdict.LOCAL, bundle.manifest().serverName(), fingerprint);
        }
        if (trustedKeys != null) {
            for (Map.Entry<String, byte[]> peer : trustedKeys.entrySet()) {
                if (Handshake.sameKey(peer.getValue(), signingKey)) {
                    return new BundleSignature(Verdict.TRUSTED_PEER, peer.getKey(), fingerprint);
                }
            }
        }
        return new BundleSignature(Verdict.UNKNOWN_KEY, bundle.manifest().serverName(), fingerprint);
    }
}
