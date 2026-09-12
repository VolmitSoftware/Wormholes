package art.arcane.wormholes.ops.backup;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.door.DoorStoreSnapshot;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.IdentityStore;
import art.arcane.wormholes.network.PeerTrustStore;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Writes, lists, rotates, exports, and plans restores for portal backups under {@code <data>/backups}. */
public final class BackupService {
    public static final String FOLDER = "backups";
    private static final String PREFIX = "wormholes-";
    private static final String SUFFIX = ".zip";
    private static final DateTimeFormatter ID_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneOffset.UTC);

    /** One bundle on disk. */
    public record BackupEntry(String id, Path file, long createdAtMillis, int portalCount, boolean signed) {
    }

    /** What a restore would write, and who signed the bundle it came from. */
    public record RestoreProposal(RestorePlan plan, BundleSignature signature) {
    }

    private final Path dataFolder;
    private final Supplier<BackupManifest> manifests;

    public BackupService(Path dataFolder, Supplier<BackupManifest> manifests) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
    }

    public static BackupService forRuntime(Path dataFolder) {
        return new BackupService(dataFolder, () -> runtimeManifest(dataFolder));
    }

    public Path folder() {
        return dataFolder.resolve(FOLDER);
    }

    public BackupEntry now() throws IOException {
        return now(System.currentTimeMillis());
    }

    public BackupEntry now(long nowMillis) throws IOException {
        Files.createDirectories(folder());
        String id = PREFIX + ID_FORMAT.format(Instant.ofEpochMilli(nowMillis));
        return exportTo(folder().resolve(id + SUFFIX), nowMillis);
    }

    /** Writes a bundle of the current data folder at an arbitrary path. */
    public BackupEntry exportTo(Path target, long nowMillis) throws IOException {
        BackupBundle bundle = BackupBundle.write(dataFolder, target, manifests.get(),
            BundleSigner.forDataFolder(dataFolder));
        String name = target.getFileName().toString();
        String id = name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name;
        return new BackupEntry(id, target, nowMillis, bundle.portalEntries().size(), bundle.signed());
    }

    /** Newest first. */
    public List<BackupEntry> list() throws IOException {
        Path folder = folder();
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<BackupEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(BackupService::isBundle).toList()) {
                entries.add(describe(file));
            }
        }
        entries.sort(Comparator.comparing(BackupEntry::id).reversed());
        return List.copyOf(entries);
    }

    public Optional<Path> resolve(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String name = id.endsWith(SUFFIX) ? id : id + SUFFIX;
        Path file = folder().resolve(name);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** Deletes the oldest bundles beyond {@code retain} and returns how many went. */
    public int rotate(int retain) throws IOException {
        List<BackupEntry> entries = list();
        int keep = Math.max(1, retain);
        int removed = 0;
        for (int index = keep; index < entries.size(); index++) {
            Files.deleteIfExists(entries.get(index).file());
            removed++;
        }
        return removed;
    }

    /** Reads a bundle once and reports both what it would change and whose key signed it. */
    public RestoreProposal propose(Path bundle, WorldKeyRemap remap) throws IOException {
        BackupBundle read = BackupBundle.read(bundle);
        return new RestoreProposal(RestorePlan.of(read, dataFolder, remap), signature(read));
    }

    /** Judges a bundle against this server's identity and its trusted peers; neither is created here. */
    public BundleSignature signature(BackupBundle bundle) {
        return BundleSignature.of(bundle, localPublicKey(), trustedPeerKeys());
    }

    public int apply(RestorePlan plan) throws IOException {
        return plan.apply(dataFolder);
    }

    private byte[] localPublicKey() {
        BundleSigner signer = BundleSigner.forDataFolder(dataFolder);
        return signer == null ? new byte[0] : signer.publicKey();
    }

    private Map<String, byte[]> trustedPeerKeys() {
        if (!Files.isRegularFile(dataFolder.resolve("trust").resolve(PeerTrustStore.FILE))) {
            return Map.of();
        }
        try {
            return PeerTrustStore.loadOrCreate(dataFolder).all();
        } catch (IOException unreadable) {
            return Map.of();
        }
    }

    private BackupEntry describe(Path file) throws IOException {
        BackupBundle bundle = BackupBundle.read(file);
        String name = file.getFileName().toString();
        return new BackupEntry(name.substring(0, name.length() - SUFFIX.length()), file,
            bundle.manifest().createdAtMillis(), bundle.portalEntries().size(), bundle.signed());
    }

    private static boolean isBundle(Path file) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file) && name.startsWith(PREFIX) && name.endsWith(SUFFIX);
    }

    private static BackupManifest runtimeManifest(Path dataFolder) {
        BundleSigner signer = BundleSigner.forDataFolder(dataFolder);
        NetworkManager network = Wormholes.networkManager;
        Wormholes plugin = Wormholes.instance;
        Map<String, String> worldKeys = new LinkedHashMap<>();
        if (plugin != null) {
            for (World world : plugin.getServer().getWorlds()) {
                worldKeys.put(world.getName(), world.getKey().toString());
            }
        }
        return new BackupManifest(
            plugin == null ? "unknown" : plugin.getDescription().getVersion(),
            WormholesConfigFile.CURRENT_SCHEMA,
            DoorStoreSnapshot.CURRENT_SCHEMA,
            System.currentTimeMillis(),
            worldKeys,
            network == null ? "" : network.getLocalName(),
            signer == null ? "unsigned" : signer.fingerprint());
    }
}
