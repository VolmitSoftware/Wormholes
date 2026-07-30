package art.arcane.wormholes.network;

import art.arcane.wormholes.util.VIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Set;

public final class IdentityStore {
    private static final String KEY_ALGORITHM = "Ed25519";
    private static final String IDENTITY_FILE = "server.identity";
    private static final String PRIVATE_FILE = "server.key";
    private static final String PUBLIC_FILE = "server.pub";
    private static final int IDENTITY_MAGIC = 0x57484944;
    private static final int IDENTITY_VERSION = 1;
    private static final int MAX_KEY_BYTES = 65_536;
    private static final byte[] KEY_PAIR_PROBE = new byte[]{87, 111, 114, 109, 104, 111, 108, 101, 115};

    private final Path directory;
    private final KeyPair keyPair;

    private IdentityStore(Path directory, KeyPair keyPair) {
        this.directory = directory;
        this.keyPair = keyPair;
    }

    public static IdentityStore loadOrCreate(Path dataDirectory) throws IOException {
        Path identityDirectory = dataDirectory.resolve("identity");
        Path identityPath = identityDirectory.resolve(IDENTITY_FILE);
        Path privatePath = identityDirectory.resolve(PRIVATE_FILE);
        Path publicPath = identityDirectory.resolve(PUBLIC_FILE);
        Files.createDirectories(identityDirectory);
        KeyPair keyPair;
        if (Files.isRegularFile(identityPath)) {
            keyPair = readIdentity(identityPath);
            writeLegacyPair(privatePath, publicPath, keyPair);
            restrictPrivateKey(identityPath);
            return new IdentityStore(identityDirectory, keyPair);
        }
        if (Files.isRegularFile(privatePath) && Files.isRegularFile(publicPath)) {
            keyPair = readKeyPair(privatePath, publicPath);
        } else {
            keyPair = generateKeyPair();
        }
        writeIdentity(identityPath, keyPair);
        writeLegacyPair(privatePath, publicPath, keyPair);
        return new IdentityStore(identityDirectory, keyPair);
    }

    public byte[] publicKeyBytes() {
        return keyPair.getPublic().getEncoded();
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    public Path directory() {
        return directory;
    }

    private static KeyPair readKeyPair(Path privatePath, Path publicPath) throws IOException {
        try {
            byte[] privateBytes = Files.readAllBytes(privatePath);
            byte[] publicBytes = Files.readAllBytes(publicPath);
            return decodeKeyPair(privateBytes, publicBytes);
        } catch (Exception e) {
            throw new IOException("Could not load Wormholes identity key pair", e);
        }
    }

    private static KeyPair readIdentity(Path identityPath) throws IOException {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(identityPath)));
            if (input.readInt() != IDENTITY_MAGIC || input.readInt() != IDENTITY_VERSION) {
                throw new IOException("Unsupported Wormholes identity store format");
            }
            byte[] privateBytes = readKeyBytes(input);
            byte[] publicBytes = readKeyBytes(input);
            if (input.available() != 0) {
                throw new IOException("Unexpected trailing data in Wormholes identity store");
            }
            return decodeKeyPair(privateBytes, publicBytes);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not load Wormholes identity store", e);
        }
    }

    private static byte[] readKeyBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_KEY_BYTES) {
            throw new IOException("Invalid Wormholes identity key length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Truncated Wormholes identity key");
        }
        return bytes;
    }

    private static KeyPair decodeKeyPair(byte[] privateBytes, byte[] publicBytes) throws Exception {
        KeyFactory factory = KeyFactory.getInstance(KEY_ALGORITHM);
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicBytes));
        KeyPair keyPair = new KeyPair(publicKey, privateKey);
        validateKeyPair(keyPair);
        return keyPair;
    }

    private static void validateKeyPair(KeyPair keyPair) throws Exception {
        Signature signer = Signature.getInstance(KEY_ALGORITHM);
        signer.initSign(keyPair.getPrivate());
        signer.update(KEY_PAIR_PROBE);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance(KEY_ALGORITHM);
        verifier.initVerify(keyPair.getPublic());
        verifier.update(KEY_PAIR_PROBE);
        if (!verifier.verify(signature)) {
            throw new IOException("Wormholes identity private and public keys do not match");
        }
    }

    private static void writeIdentity(Path identityPath, KeyPair keyPair) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            byte[] privateBytes = keyPair.getPrivate().getEncoded();
            byte[] publicBytes = keyPair.getPublic().getEncoded();
            output.writeInt(IDENTITY_MAGIC);
            output.writeInt(IDENTITY_VERSION);
            output.writeInt(privateBytes.length);
            output.write(privateBytes);
            output.writeInt(publicBytes.length);
            output.write(publicBytes);
        }
        VIO.writeAllBytes(identityPath.toFile(), buffer.toByteArray());
        restrictPrivateKey(identityPath);
    }

    private static void writeLegacyPair(Path privatePath, Path publicPath, KeyPair keyPair) throws IOException {
        byte[] privateBytes = keyPair.getPrivate().getEncoded();
        byte[] publicBytes = keyPair.getPublic().getEncoded();
        if (!sameFileBytes(privatePath, privateBytes)) {
            VIO.writeAllBytes(privatePath.toFile(), privateBytes);
        }
        if (!sameFileBytes(publicPath, publicBytes)) {
            VIO.writeAllBytes(publicPath.toFile(), publicBytes);
        }
        restrictPrivateKey(privatePath);
    }

    private static boolean sameFileBytes(Path path, byte[] expected) throws IOException {
        return Files.isRegularFile(path) && Arrays.equals(Files.readAllBytes(path), expected);
    }

    private static KeyPair generateKeyPair() throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IOException("Could not generate Wormholes identity key pair", e);
        }
    }

    private static void restrictPrivateKey(Path privatePath) throws IOException {
        try {
            Files.setPosixFilePermissions(privatePath, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
        }
    }
}
