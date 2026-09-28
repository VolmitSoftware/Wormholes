package art.arcane.wormholes.ops.backup;

import art.arcane.wormholes.network.Handshake;
import art.arcane.wormholes.network.IdentityStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;

/** Signs a bundle with the server identity. Absent when the server has no identity on disk yet. */
public interface BundleSigner {
    byte[] publicKey();

    byte[] sign(byte[] payload);

    String fingerprint();

    /**
     * The identity under {@code <data>/identity}, or null when it does not exist yet. Backups never
     * create an identity as a side effect; an identity-less server produces unsigned bundles.
     */
    static BundleSigner forDataFolder(Path dataFolder) {
        if (dataFolder == null || !Files.isRegularFile(dataFolder.resolve("identity").resolve("server.identity"))) {
            return null;
        }
        IdentityStore store;
        try {
            store = IdentityStore.loadOrCreate(dataFolder);
        } catch (IOException unreadable) {
            return null;
        }
        byte[] publicKey = store.publicKeyBytes();
        PrivateKey privateKey = store.privateKey();
        return new BundleSigner() {
            @Override
            public byte[] publicKey() {
                return publicKey;
            }

            @Override
            public byte[] sign(byte[] payload) {
                return Handshake.sign(privateKey, payload);
            }

            @Override
            public String fingerprint() {
                return Handshake.fingerprint(publicKey);
            }
        };
    }
}
