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

/**
 * A restore has to know whose bundle it is about to apply. Verifying the signature against the key in
 * the same manifest only proves the zip is self-consistent, so the key is matched against this
 * server's identity and its trusted peers.
 */
class BundleSignatureTest {
    @TempDir
    Path tempDir;

    @Test
    void aBundleSignedByThisServerIsTrustedAndOneSignedByATrustedPeerNamesThatPeer() throws Exception {
        KeyPair local = keys();
        KeyPair peer = keys();

        BundleSignature mine = BundleSignature.of(bundle(local), local.getPublic().getEncoded(), Map.of());
        assertEquals(BundleSignature.Verdict.LOCAL, mine.verdict());
        assertTrue(mine.trusted());
        assertTrue(mine.permitsRestore(false));

        BundleSignature theirs = BundleSignature.of(bundle(peer), local.getPublic().getEncoded(),
            Map.of("creative", peer.getPublic().getEncoded()));
        assertEquals(BundleSignature.Verdict.TRUSTED_PEER, theirs.verdict());
        assertEquals("creative", theirs.signer());
        assertEquals(Handshake.fingerprint(peer.getPublic().getEncoded()), theirs.fingerprint());
        assertTrue(theirs.permitsRestore(false));
    }

    @Test
    void aRewrittenBundleIsRefusedEvenWhenItCarriesItsOwnValidSignature() throws Exception {
        KeyPair local = keys();
        KeyPair attacker = keys();

        BundleSignature resigned = BundleSignature.of(bundle(attacker), local.getPublic().getEncoded(), Map.of());
        assertEquals(BundleSignature.Verdict.UNKNOWN_KEY, resigned.verdict(),
            "a manifest can name any key, so verifying against it proves nothing on its own");
        assertFalse(resigned.trusted());
        assertFalse(resigned.permitsRestore(false));
        assertTrue(resigned.permitsRestore(true), "an operator may still force an unknown signer");

        BundleSignature tampered = BundleSignature.of(tamperedBundle(local), local.getPublic().getEncoded(), Map.of());
        assertEquals(BundleSignature.Verdict.BROKEN, tampered.verdict());
        assertFalse(tampered.permitsRestore(false));
        assertFalse(tampered.permitsRestore(true), "a broken signature is never forceable");
    }

    @Test
    void anUnsignedBundleNeedsTheOperatorToSaySo() throws Exception {
        BundleSignature unsigned = BundleSignature.of(bundle(null), keys().getPublic().getEncoded(), Map.of());
        assertEquals(BundleSignature.Verdict.UNSIGNED, unsigned.verdict());
        assertEquals("unsigned", unsigned.fingerprint());
        assertFalse(unsigned.permitsRestore(false));
        assertTrue(unsigned.permitsRestore(true));
    }

    @Test
    void theServiceJudgesABundleAgainstTheIdentityAndTrustStoreOnDisk() throws Exception {
        Path dataFolder = tempDir.resolve("data");
        Path portals = dataFolder.resolve("portals");
        Files.createDirectories(portals);
        String portalId = "55555555-5555-5555-5555-555555555555";
        Files.writeString(portals.resolve(portalId + ".json"),
            "{\"id\":\"" + portalId + "\",\"name\":\"Hub\"}", StandardCharsets.UTF_8);

        BackupService service = new BackupService(dataFolder, () -> manifest("alpha"));
        BackupService.BackupEntry entry = service.exportTo(tempDir.resolve("unsigned.zip"), 1L);
        assertFalse(entry.signed(), "a data folder without an identity produces an unsigned bundle");

        BackupService.RestoreProposal proposal = service.propose(entry.file(), WorldKeyRemap.none());
        assertEquals(BundleSignature.Verdict.UNSIGNED, proposal.signature().verdict());
        assertEquals(1, proposal.plan().changeCount(), "the plan is still built so a dry run can report it");
    }

    private BackupBundle bundle(KeyPair signer) throws IOException {
        Path source = tempDir.resolve("source-" + System.nanoTime());
        Path portals = source.resolve("portals");
        Files.createDirectories(portals);
        Files.writeString(portals.resolve("one.json"), "{\"id\":\"one\"}", StandardCharsets.UTF_8);
        Path target = tempDir.resolve("bundle-" + System.nanoTime() + ".zip");
        BackupBundle.write(source, target, manifest("alpha"), signer == null ? null : signer(signer));
        return BackupBundle.read(target);
    }

    private BackupBundle tamperedBundle(KeyPair signer) throws IOException {
        BackupBundle original = bundle(signer);
        Path target = tempDir.resolve("tampered-" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> entry : original.entries().entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write("{\"id\":\"evil\"}".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(BackupBundle.MANIFEST_ENTRY));
            out.write(original.manifestBytes());
            out.closeEntry();
            out.putNextEntry(new ZipEntry(BackupBundle.SIGNATURE_ENTRY));
            out.write(original.signature());
            out.closeEntry();
        }
        return BackupBundle.read(target);
    }

    private static BackupManifest manifest(String serverName) {
        return new BackupManifest("2.0.5-26.2", 3, 8, 1L, Map.of(), serverName, "unsigned");
    }

    private static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static BundleSigner signer(KeyPair pair) {
        return new BundleSigner() {
            @Override
            public byte[] publicKey() {
                return pair.getPublic().getEncoded();
            }

            @Override
            public byte[] sign(byte[] payload) {
                return Handshake.sign(pair.getPrivate(), payload);
            }

            @Override
            public String fingerprint() {
                return Handshake.fingerprint(pair.getPublic().getEncoded());
            }
        };
    }
}
