package art.arcane.wormholes.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class PeerTrustStore {
    private static final String TRUST_FILE = "peers.properties";

    private final Path file;
    private final Map<String, byte[]> trustedKeys = new ConcurrentHashMap<>();
    private final Map<String, PublicKey> decodedTrustedKeys = new ConcurrentHashMap<>();

    private PeerTrustStore(Path file) {
        this.file = file;
    }

    public static PeerTrustStore loadOrCreate(Path dataDirectory) throws IOException {
        Path trustDirectory = dataDirectory.resolve("trust");
        Files.createDirectories(trustDirectory);
        PeerTrustStore store = new PeerTrustStore(trustDirectory.resolve(TRUST_FILE));
        store.load();
        return store;
    }

    public byte[] get(String serverName) {
        return trustedKeys.get(serverName);
    }

    public PublicKey getPublicKey(String serverName) {
        if (!trustedKeys.containsKey(serverName)) {
            return null;
        }
        return decodedTrustedKeys.computeIfAbsent(serverName, ignored -> {
            try {
                return Handshake.decodePublicKey(trustedKeys.get(serverName));
            } catch (Exception e) {
                return null;
            }
        });
    }

    public boolean isTrusted(String serverName, byte[] publicKey) {
        byte[] trusted = trustedKeys.get(serverName);
        return trusted != null && Handshake.sameKey(trusted, publicKey);
    }

    public boolean trust(String serverName, byte[] publicKey) {
        if (serverName == null || serverName.isBlank() || publicKey == null || publicKey.length == 0) {
            return false;
        }
        byte[] previous = trustedKeys.putIfAbsent(serverName, publicKey.clone());
        if (previous != null) {
            return Handshake.sameKey(previous, publicKey);
        }
        decodedTrustedKeys.remove(serverName);
        persist();
        return true;
    }

    public void trustOrReplace(String serverName, byte[] publicKey) {
        if (serverName == null || serverName.isBlank() || publicKey == null || publicKey.length == 0) {
            return;
        }
        byte[] previous = trustedKeys.put(serverName, publicKey.clone());
        if (previous != null && Handshake.sameKey(previous, publicKey)) {
            return;
        }
        decodedTrustedKeys.remove(serverName);
        persist();
    }

    private void load() throws IOException {
        trustedKeys.clear();
        decodedTrustedKeys.clear();
        if (!Files.isRegularFile(file)) {
            persist();
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        for (String name : properties.stringPropertyNames()) {
            byte[] publicKey = Handshake.decodePublicKeyText(properties.getProperty(name));
            if (publicKey != null) {
                trustedKeys.put(name, publicKey);
            }
        }
    }

    private synchronized void persist() {
        Properties properties = new Properties();
        for (Map.Entry<String, byte[]> entry : trustedKeys.entrySet()) {
            properties.setProperty(entry.getKey(), Handshake.encodePublicKey(entry.getValue()));
        }
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream output = Files.newOutputStream(file)) {
                properties.store(output, "Wormholes trusted peer public keys");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Wormholes peer trust store", e);
        }
    }
}
