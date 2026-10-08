package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.config.toml.OpsConfig;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.door.DimensionalDoorRepository;
import art.arcane.wormholes.door.DoorStoreSnapshot;
import art.arcane.wormholes.localization.OpsMessages;
import art.arcane.wormholes.ops.backup.BackupManifest;
import art.arcane.wormholes.ops.backup.BackupService;
import art.arcane.wormholes.ops.backup.BundleSignature;
import art.arcane.wormholes.ops.backup.BundleSigner;
import art.arcane.wormholes.ops.backup.WorldKeyRemap;
import art.arcane.wormholes.ops.importers.PortalImporter;
import art.arcane.wormholes.ops.importers.PortalImporters;
import art.arcane.wormholes.ops.importers.PortalImportReport;
import art.arcane.wormholes.config.WormholesSettings;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class MinecraftBackups implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final List<String> DATA_RESET_FOLDERS = List.of("identity", "routes", "trust", "portals", "atlas", "rules",
        "mesh", "backups", "convoy");
    private static final List<String> DOOR_RESET_FOLDERS = List.of("doors", "pockets");
    private final WormholesModRuntime runtime;
    private ExecutorService worker;
    private long nextBackup;
    private long generation;
    private boolean mutating;

    MinecraftBackups(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    LiteralArgumentBuilder<CommandSourceStack> commands() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("backup")
            .requires(source -> runtime.access().permission(source, "wormholes.admin.backup"))
            .then(Commands.literal("now").executes(context -> create(context.getSource(), null)))
            .then(Commands.literal("list").executes(context -> list(context.getSource())))
            .then(Commands.literal("export").executes(context -> create(context.getSource(), null))
                .then(Commands.argument("file", StringArgumentType.string()).executes(context ->
                    create(context.getSource(), Path.of(StringArgumentType.getString(context, "file"))))));
        for (String action : List.of("restore", "import")) {
            root.then(Commands.literal(action).then(Commands.argument("bundle", StringArgumentType.string())
                .executes(context -> restore(context, action, ""))
                .then(Commands.argument("options", StringArgumentType.greedyString()).executes(context ->
                    restore(context, action, StringArgumentType.getString(context, "options"))))));
        }
        root.then(Commands.literal("import-from").then(Commands.argument("source", StringArgumentType.word())
            .suggests((context, builder) -> SharedSuggestionProvider.suggest(PortalImporters.ids(), builder))
            .executes(context -> importFrom(context, ""))
            .then(Commands.argument("options", StringArgumentType.greedyString()).executes(context ->
                importFrom(context, StringArgumentType.getString(context, "options"))))));
        return root;
    }

    void tick() {
        OpsConfig.BackupConfig configuration = runtime.configuration().settings().getOps().backup;
        long now = System.currentTimeMillis();
        if (nextBackup == 0L) {
            nextBackup = now + Math.max(OpsConfig.BackupConfig.MIN_INTERVAL_MINUTES, configuration.intervalMinutes) * 60_000L;
        }
        if (configuration.enabled && !mutating && now >= nextBackup) {
            nextBackup = now + Math.max(OpsConfig.BackupConfig.MIN_INTERVAL_MINUTES, configuration.intervalMinutes) * 60_000L;
            BackupService service = service();
            int retain = Math.max(1, configuration.retain);
            work(() -> {
                service.now();
                service.rotate(retain);
                return true;
            }).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.error("Could not create scheduled Wormholes backup", failure);
                }
            });
        }
    }

    int reset(CommandSourceStack source) {
        if (mutating || !runtime.doors().resetAllowed()) {
            source.sendFailure(Component.literal("Cannot reset Wormholes during another data operation or while players occupy or traverse pockets."));
            return 0;
        }
        long retiredSlots = runtime.doors().state().snapshot().nextPocketSlot();
        MinecraftStorePaths stores = runtime.stores();
        replaceData(source, () -> {
            Files.deleteIfExists(stores.config().resolve(WormholesSettings.CONFIG_FILE_NAME));
            for (String folder : DATA_RESET_FOLDERS) {
                deleteTree(stores.data().resolve(folder));
            }
            for (String folder : DOOR_RESET_FOLDERS) {
                deleteTree(stores.doors().resolve(folder));
            }
            DimensionalDoorRepository.under(stores.doors(), MinecraftJsonDocuments.INSTANCE).save(new DoorStoreSnapshot(
                DoorStoreSnapshot.CURRENT_SCHEMA, retiredSlots, List.of(), List.of(), List.of(), List.of(), List.of()));
            return 0;
        }, ignored -> source.sendSuccess(() -> Component.literal("Wormholes data, configuration, trust, and network identity reset."), true));
        return 1;
    }

    private int importFrom(CommandContext<CommandSourceStack> context, String optionsText) {
        CommandSourceStack source = context.getSource();
        ImportOptions options;
        PortalImporter importer;
        try {
            options = ImportOptions.parse(optionsText);
            importer = PortalImporters.byId(StringArgumentType.getString(context, "source"), options.width(), options.height());
            if (importer == null) {
                throw new IllegalArgumentException("Unknown import source");
            }
        } catch (IllegalArgumentException malformed) {
            failed(source, malformed);
            return 0;
        }
        long submitted = generation;
        MinecraftServer server = runtime.server();
        Path root = server.getServerDirectory();
        UUID owner = source.getPlayer() == null ? null : source.getPlayer().getUUID();
        MinecraftPortalImporter factory = new MinecraftPortalImporter(new MinecraftPortalImporter.Options(runtime, owner,
            () -> submitted == generation && runtime.running() && !mutating));
        CompletableFuture<PortalImportReport> result = new CompletableFuture<>();
        Thread.ofVirtual().name("Wormholes-import").start(() -> {
            try {
                result.complete(importer.detect(root) ? importer.importFrom(root, options.dry(), factory) : null);
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        result.whenCompleteAsync((report, failure) -> {
            if (submitted != generation || !runtime.running()) {
                return;
            }
            if (failure != null) {
                failed(source, failure);
            } else if (report == null) {
                send(source, OpsMessages.IMPORT_NOT_DETECTED, Map.of("name", importer.id(), "path", root.toString()));
            } else {
                if (report.dryRun()) {
                    source.sendSuccess(() -> Component.literal("Dry run; no portals were created."), false);
                }
                send(source, OpsMessages.IMPORT_REPORT, Map.of("name", importer.id(), "count", report.createdCount(), "value", report.skippedCount()));
                for (PortalImportReport.Skip skip : report.skipped()) {
                    send(source, OpsMessages.IMPORT_SKIPPED_ROW, Map.of("portal", skip.name(), "reason", skip.reason()));
                }
                for (String destination : report.unlinked()) {
                    source.sendSuccess(() -> Component.literal("Unresolved imported destination: " + destination), false);
                }
                for (String note : report.notes()) {
                    source.sendSuccess(() -> Component.literal(note), false);
                }
            }
        }, server);
        return 1;
    }

    private int create(CommandSourceStack source, Path export) {
        BackupService service = service();
        long submitted = generation;
        int retain = Math.max(1, runtime.configuration().settings().getOps().backup.retain);
        work(() -> {
            BackupService.BackupEntry entry = export == null ? service.now() : service.exportTo(export, System.currentTimeMillis());
            if (export == null) {
                service.rotate(retain);
            }
            return entry;
        }).whenCompleteAsync((entry, failure) -> {
            if (generation != submitted || !runtime.running()) {
                return;
            }
            if (failure != null) {
                failed(source, failure);
            } else {
                send(source, OpsMessages.BACKUP_CREATED, Map.of("id", entry.id(), "path", entry.file().toString()));
            }
        }, runtime.server());
        return 1;
    }

    private int list(CommandSourceStack source) {
        BackupService service = service();
        long submitted = generation;
        work(service::list).whenCompleteAsync((entries, failure) -> {
            if (generation != submitted || !runtime.running()) {
                return;
            }
            if (failure != null) {
                failed(source, failure);
                return;
            }
            if (entries.isEmpty()) {
                send(source, OpsMessages.BACKUP_LIST_EMPTY, Map.of("path", service.folder().toString()));
            }
            for (BackupService.BackupEntry entry : entries) {
                send(source, OpsMessages.BACKUP_LIST_ROW, Map.of("id", entry.id(), "count", entry.portalCount(), "path", entry.file().toString()));
            }
        }, runtime.server());
        return 1;
    }

    private int restore(CommandContext<CommandSourceStack> context, String action, String optionsText) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "bundle");
        RestoreOptions options;
        try {
            options = RestoreOptions.parse(optionsText);
        } catch (IllegalArgumentException malformed) {
            failed(source, malformed);
            return 0;
        }
        BackupService service = service();
        long submitted = generation;
        work(() -> {
            Path bundle = action.equals("import") ? Path.of(id) : service.resolve(id)
                .orElseThrow(() -> new IOException("No backup matches " + id));
            return service.propose(bundle, options.remap());
        }).whenCompleteAsync((proposal, failure) -> {
            if (generation != submitted || !runtime.running()) {
                return;
            }
            if (failure != null) {
                failed(source, failure);
                return;
            }
            BundleSignature signature = proposal.signature();
            if (signature.verdict() == BundleSignature.Verdict.BROKEN) {
                send(source, OpsMessages.BACKUP_SIGNATURE_BROKEN, Map.of("id", id));
                return;
            }
            if (!signature.permitsRestore(options.allowUnsigned())) {
                send(source, OpsMessages.BACKUP_SIGNATURE_UNTRUSTED, Map.of("id", id, "fingerprint", signature.fingerprint()));
                return;
            }
            if (options.dry()) {
                send(source, OpsMessages.BACKUP_DRY_RUN, Map.of("count", proposal.plan().changeCount()));
            } else if (!options.confirm()) {
                send(source, OpsMessages.BACKUP_CONFIRM_REQUIRED, Map.of());
            } else if (mutating) {
                failed(source, new IllegalStateException("Another data operation is running"));
            } else {
                replaceData(source, () -> service.apply(proposal.plan()), count ->
                    send(source, OpsMessages.BACKUP_RESTORED, Map.of("id", id, "count", count)));
            }
        }, runtime.server());
        return 1;
    }

    private <T> void replaceData(CommandSourceStack source, IoTask<T> task, Consumer<T> completed) {
        MinecraftServer server = runtime.server();
        mutating = true;
        runtime.stop();
        CompletableFuture.supplyAsync(() -> execute(task)).whenCompleteAsync((result, failure) -> {
            try {
                runtime.start(server);
                if (failure == null) {
                    completed.accept(result);
                } else {
                    failed(source, failure);
                }
            } catch (RuntimeException restartFailure) {
                LOGGER.error("Could not restart Wormholes after data operation", restartFailure);
                source.sendFailure(Component.literal("Wormholes could not restart after the data operation; see the server log."));
            } finally {
                mutating = false;
            }
        }, server);
    }

    private BackupService service() {
        Path folder = runtime.stores().data();
        Map<String, String> worlds = new LinkedHashMap<>();
        for (ServerLevel level : runtime.server().getAllLevels()) {
            String key = level.dimension().identifier().toString();
            worlds.put(key, key);
        }
        String serverName = runtime.network().manager() == null ? "" : runtime.network().manager().getLocalName();
        return new BackupService(folder, () -> {
            BundleSigner signer = BundleSigner.forDataFolder(folder);
            return new BackupManifest(MinecraftNetworkService.version(), WormholesConfigFile.CURRENT_SCHEMA,
                DoorStoreSnapshot.CURRENT_SCHEMA, System.currentTimeMillis(), worlds, serverName,
                signer == null ? "unsigned" : signer.fingerprint());
        });
    }

    private <T> CompletableFuture<T> work(IoTask<T> task) {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("Wormholes-backups").factory());
        }
        return runtime.portals().flushWrites().thenApplyAsync(ignored -> execute(task), worker);
    }

    private static <T> T execute(IoTask<T> task) {
        try {
            return task.run();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void send(CommandSourceStack source, TextKey key, Map<String, ?> values) {
        source.sendSuccess(() -> runtime.localization().text(source.getPlayer(), key, values), false);
    }

    private void failed(CommandSourceStack source, Throwable failure) {
        LOGGER.error("Wormholes backup operation failed", failure);
        send(source, OpsMessages.BACKUP_FAILED, Map.of("reason", String.valueOf(failure.getMessage())));
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void close() {
        generation++;
        if (worker != null) {
            worker.close();
            worker = null;
        }
        nextBackup = 0L;
    }

    record ImportOptions(boolean dry, int width, int height) {
        static ImportOptions parse(String text) {
            boolean dry = true;
            int[] frame = new int[]{0, 0};
            Map<String, String> values = new HashMap<>();
            if (!text.isBlank()) {
                for (String token : text.trim().split("\\s+")) {
                    String[] pair = token.split("=", 2);
                    if (pair.length != 2 || !List.of("dry", "frame").contains(pair[0])
                        || values.putIfAbsent(pair[0], pair[1]) != null) {
                        throw new IllegalArgumentException("Import options are dry=true|false and frame=width,height");
                    }
                }
                dry = RestoreOptions.flag(values, "dry", true);
                frame = PortalImporters.parseFrame(values.get("frame"));
            }
            return new ImportOptions(dry, frame[0], frame[1]);
        }
    }

    record RestoreOptions(boolean dry, boolean confirm, boolean allowUnsigned, WorldKeyRemap remap) {
        static RestoreOptions parse(String text) {
            Map<String, String> values = new HashMap<>();
            if (!text.isBlank()) {
                for (String token : text.trim().split("\\s+")) {
                    int split = token.indexOf('=');
                    if (split < 1 || split == token.length() - 1) {
                        throw new IllegalArgumentException("Backup options must be key=value");
                    }
                    String key = token.substring(0, split);
                    if (!List.of("dry", "confirm", "allow-unsigned", "world-map", "owner-map").contains(key)
                        || values.putIfAbsent(key, token.substring(split + 1)) != null) {
                        throw new IllegalArgumentException("Unknown or repeated backup option: " + key);
                    }
                }
            }
            return new RestoreOptions(flag(values, "dry", true), flag(values, "confirm", false),
                flag(values, "allow-unsigned", false), WorldKeyRemap.parse(values.get("world-map"), values.get("owner-map")));
        }

        private static boolean flag(Map<String, String> values, String key, boolean fallback) {
            String value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (!value.equals("true") && !value.equals("false")) {
                throw new IllegalArgumentException(key + " must be true or false");
            }
            return Boolean.parseBoolean(value);
        }
    }

    @FunctionalInterface
    private interface IoTask<T> {
        T run() throws IOException;
    }
}
