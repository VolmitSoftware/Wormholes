package art.arcane.wormholes.ops.backup;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.network.Handshake;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A portable zip of the portal-bearing parts of the data folder plus a manifest that carries a
 * SHA-256 for every entry. The manifest is signed with the server identity, so rewriting any entry
 * invalidates the signature.
 */
public final class BackupBundle {
    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final String SIGNATURE_ENTRY = "signature";
    public static final List<String> SOURCES = List.of("portals", "doors", "atlas", "rules/templates", "mesh");
    private static final String PORTAL_PREFIX = "portals/";

    private final BackupManifest manifest;
    private final Map<String, byte[]> entries;
    private final byte[] manifestBytes;
    private final byte[] signature;
    private final byte[] signingKey;
    private final boolean signatureValid;

    private BackupBundle(BackupManifest manifest, Map<String, byte[]> entries, byte[] manifestBytes,
                         byte[] signature, byte[] signingKey, boolean signatureValid) {
        this.manifest = manifest;
        this.entries = entries;
        this.manifestBytes = manifestBytes;
        this.signature = signature;
        this.signingKey = signingKey;
        this.signatureValid = signatureValid;
    }

    public BackupManifest manifest() {
        return manifest;
    }

    /** Entry path to bytes, in write order; excludes the manifest and the signature. */
    public Map<String, byte[]> entries() {
        return entries;
    }

    public byte[] manifestBytes() {
        return manifestBytes;
    }

    public byte[] signature() {
        return signature;
    }

    /**
     * The public key the manifest names, which is who the bundle claims to be from. It proves nothing
     * on its own - the signature verifies against it - so a caller has to match it against a key it
     * already trusts; {@link BundleSignature} does that.
     */
    public byte[] signingKey() {
        return signingKey;
    }

    public boolean signed() {
        return signature.length > 0;
    }

    public boolean signatureValid() {
        return signatureValid;
    }

    public List<String> portalEntries() {
        List<String> portals = new ArrayList<>();
        for (String entry : entries.keySet()) {
            if (entry.startsWith(PORTAL_PREFIX) && entry.endsWith(".json")) {
                portals.add(entry);
            }
        }
        portals.sort(Comparator.naturalOrder());
        return portals;
    }

    public static BackupBundle write(Path dataFolder, Path target, BackupManifest manifest, BundleSigner signer)
        throws IOException {
        Map<String, byte[]> entries = collect(dataFolder);
        JSONObject manifestJson = manifest.toJson();
        JSONObject digests = new JSONObject();
        for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
            digests.put(entry.getKey(), digest(entry.getValue()));
        }
        manifestJson.put("entries", digests);
        if (signer != null) {
            manifestJson.put("publicKey", Base64.getEncoder().encodeToString(signer.publicKey()));
        }
        byte[] manifestBytes = manifestJson.toString().getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer == null ? new byte[0] : signer.sign(manifestBytes);

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                writeEntry(out, entry.getKey(), entry.getValue());
            }
            writeEntry(out, MANIFEST_ENTRY, manifestBytes);
            if (signature.length > 0) {
                writeEntry(out, SIGNATURE_ENTRY, signature);
            }
        }
        return new BackupBundle(BackupManifest.fromJson(manifestJson), entries, manifestBytes, signature,
            signer == null ? new byte[0] : signer.publicKey(), signature.length > 0);
    }

    public static BackupBundle read(Path bundle) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] manifestBytes = new byte[0];
        byte[] signature = new byte[0];
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(bundle))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] content = in.readAllBytes();
                if (MANIFEST_ENTRY.equals(entry.getName())) {
                    manifestBytes = content;
                } else if (SIGNATURE_ENTRY.equals(entry.getName())) {
                    signature = content;
                } else {
                    entries.put(entry.getName(), content);
                }
            }
        }
        if (manifestBytes.length == 0) {
            throw new IOException("Backup bundle has no " + MANIFEST_ENTRY + ": " + bundle);
        }
        JSONObject manifestJson = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        byte[] signingKey = signingKey(manifestJson);
        boolean valid = signature.length > 0
            && digestsMatch(manifestJson, entries)
            && signingKey.length > 0
            && Handshake.verify(signingKey, signature, manifestBytes);
        return new BackupBundle(BackupManifest.fromJson(manifestJson), entries, manifestBytes, signature, signingKey, valid);
    }

    private static boolean digestsMatch(JSONObject manifestJson, Map<String, byte[]> entries) {
        JSONObject digests = manifestJson.optJSONObject("entries");
        if (digests == null || digests.keySet().size() != entries.size()) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String expected = digests.optString(entry.getKey(), "");
            if (!digest(entry.getValue()).equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] signingKey(JSONObject manifestJson) {
        String publicKey = manifestJson.optString("publicKey", "");
        if (publicKey.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(publicKey);
        } catch (IllegalArgumentException unreadable) {
            return new byte[0];
        }
    }

    private static Map<String, byte[]> collect(Path dataFolder) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String source : SOURCES) {
            Path root = dataFolder.resolve(source);
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> files = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort(Comparator.comparing(Path::toString));
            for (Path file : files) {
                entries.put(entryName(dataFolder, file), Files.readAllBytes(file));
            }
        }
        return entries;
    }

    private static String entryName(Path dataFolder, Path file) {
        return dataFolder.relativize(file).toString().replace('\\', '/');
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content);
        out.closeEntry();
    }

    static String digest(byte[] content) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
