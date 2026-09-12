package art.arcane.wormholes.ops.backup;

import art.arcane.wormholes.network.Handshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupBundleTest {
    @TempDir
    Path tempDir;

    @Test
    void aSignedBundleRoundTripsEveryDataFolderSourceAndVerifies() throws Exception {
        Path dataFolder = dataFolderWithPortals();
        Path target = tempDir.resolve("bundle.zip");

        BackupBundle written = BackupBundle.write(dataFolder, target, manifest(), signer());

        assertTrue(Files.isRegularFile(target));
        assertTrue(written.signed());
        assertTrue(written.signatureValid());

        BackupBundle read = BackupBundle.read(target);
        assertTrue(read.signatureValid());
        assertEquals("2.0.5-26.2", read.manifest().pluginVersion());
        assertEquals(3, read.manifest().configSchema());
        assertEquals("alpha", read.manifest().serverName());
        assertEquals(Map.of("world", "minecraft:overworld"), read.manifest().worldKeys());
        assertEquals(2, read.portalEntries().size());
        assertTrue(read.entries().containsKey("doors/state.json"));
        assertTrue(read.entries().containsKey("atlas/index.json"));
        assertTrue(read.entries().containsKey("rules/templates/toll.json"));
        assertFalse(read.entries().containsKey("wormholes.toml"), "only the listed sources travel");
        assertEquals("{\"id\":\"one\"}", new String(
            read.entries().get(read.portalEntries().get(0)), StandardCharsets.UTF_8));
    }

    @Test
    void aTamperedEntryFailsVerification() throws Exception {
        Path dataFolder = dataFolderWithPortals();
        Path target = tempDir.resolve("bundle.zip");
        BackupBundle.write(dataFolder, target, manifest(), signer());

        BackupBundle original = BackupBundle.read(target);
        Path tampered = tempDir.resolve("tampered.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tampered))) {
            for (Map.Entry<String, byte[]> entry : original.entries().entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getKey().startsWith("portals/")
                    ? "{\"id\":\"evil\"}".getBytes(StandardCharsets.UTF_8) : entry.getValue());
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(BackupBundle.MANIFEST_ENTRY));
            out.write(original.manifestBytes());
            out.closeEntry();
            out.putNextEntry(new ZipEntry(BackupBundle.SIGNATURE_ENTRY));
            out.write(original.signature());
            out.closeEntry();
        }

        BackupBundle read = BackupBundle.read(tampered);
        assertTrue(read.signed());
        assertFalse(read.signatureValid(), "a rewritten portal entry must not verify");
    }

    @Test
    void anUnsignedBundleIsReadableAndFlagged() throws Exception {
        Path dataFolder = dataFolderWithPortals();
        Path target = tempDir.resolve("unsigned.zip");

        BackupBundle written = BackupBundle.write(dataFolder, target, manifest(), null);
        assertFalse(written.signed());

        BackupBundle read = BackupBundle.read(target);
        assertFalse(read.signed());
        assertFalse(read.signatureValid());
        assertEquals(2, read.portalEntries().size());
    }

    private Path dataFolderWithPortals() throws IOException {
        Path dataFolder = tempDir.resolve("data");
        write(dataFolder.resolve("portals/0000/aaaa/one.json"), "{\"id\":\"one\"}");
        write(dataFolder.resolve("portals/0001/bbbb/two.json"), "{\"id\":\"two\"}");
        write(dataFolder.resolve("doors/state.json"), "{\"schema\":8}");
        write(dataFolder.resolve("doors/state.json.tickets/1.json"), "{}");
        write(dataFolder.resolve("atlas/index.json"), "{}");
        write(dataFolder.resolve("rules/templates/toll.json"), "{}");
        write(dataFolder.resolve("wormholes.toml"), "schema = 3");
        return dataFolder;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static BackupManifest manifest() {
        return new BackupManifest("2.0.5-26.2", 3, 8, 1_700_000_000_000L,
            Map.of("world", "minecraft:overworld"), "alpha", "aa:bb");
    }

    private static BundleSigner signer() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new BundleSigner() {
            @Override
            public byte[] publicKey() {
                return keyPair.getPublic().getEncoded();
            }

            @Override
            public byte[] sign(byte[] payload) {
                return Handshake.sign(keyPair.getPrivate(), payload);
            }

            @Override
            public String fingerprint() {
                return Handshake.fingerprint(keyPair.getPublic().getEncoded());
            }
        };
    }
}
