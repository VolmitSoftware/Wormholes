package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.OpsMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.ops.backup.BackupService;
import art.arcane.wormholes.ops.backup.BundleSignature;
import art.arcane.wormholes.ops.backup.RestorePlan;
import art.arcane.wormholes.ops.backup.WorldKeyRemap;
import art.arcane.wormholes.ops.importers.BukkitPortalFactoryBridge;
import art.arcane.wormholes.ops.importers.PortalImportReport;
import art.arcane.wormholes.ops.importers.PortalImporter;
import art.arcane.wormholes.ops.importers.PortalImporters;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Portal backups: write, list, restore, and move bundles between servers.
 *
 * <p>Restores and imports are dry runs by default. Writing files needs {@code dry=false confirm=true},
 * and the operator reloads afterwards so the managers pick the files up.</p>
 */
@Director(name = "backup", descriptionKey = OpsMessages.BACKUP_HELP,
        description = "Create, list, restore, and exchange portal backups")
public class CommandBackup {
    private static final String PERMISSION = "wormholes.admin.backup";
    private static final int FREEZE_SECONDS = 30;
    private static final DateTimeFormatter EXPORT_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneOffset.UTC);

    @Director(name = "now", descriptionKey = OpsMessages.BACKUP_NOW_HELP,
            description = "Write a backup of the portal data now")
    public void now(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!allowed(sender)) {
            return;
        }
        async(() -> {
            try {
                BackupService service = service();
                BackupService.BackupEntry entry = service.now();
                service.rotate(retain());
                send(sender, OpsMessages.BACKUP_CREATED, WormholesLocalization.args(
                        MessageArgument.untrusted("id", entry.id()),
                        MessageArgument.untrusted("path", entry.file().toString())));
            } catch (IOException failure) {
                failed(sender, failure);
            }
        });
    }

    @Director(name = "list", descriptionKey = OpsMessages.BACKUP_LIST_HELP, description = "List the backups on disk")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!allowed(sender)) {
            return;
        }
        async(() -> {
            try {
                BackupService service = service();
                List<BackupService.BackupEntry> entries = service.list();
                if (entries.isEmpty()) {
                    send(sender, OpsMessages.BACKUP_LIST_EMPTY, WormholesLocalization.args(
                            MessageArgument.untrusted("path", service.folder().toString())));
                    return;
                }
                for (BackupService.BackupEntry entry : entries) {
                    send(sender, OpsMessages.BACKUP_LIST_ROW, WormholesLocalization.args(
                            MessageArgument.untrusted("id", entry.id()),
                            MessageArgument.untrusted("count", Integer.valueOf(entry.portalCount())),
                            MessageArgument.untrusted("path", entry.file().toString())));
                }
            } catch (IOException failure) {
                failed(sender, failure);
            }
        });
    }

    @Director(name = "restore", descriptionKey = OpsMessages.BACKUP_RESTORE_HELP,
            description = "Restore portal files from a backup (dry=true shows the plan)")
    public void restore(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "id", descriptionKey = OpsMessages.BACKUP_RESTORE_ID_HELP,
                                description = "Backup id from /wormholes admin backup list") String id,
                        @Param(name = "dry", descriptionKey = OpsMessages.BACKUP_RESTORE_DRY_HELP,
                                description = "Report what would change without writing anything",
                                defaultValue = "true") boolean dry,
                        @Param(name = "world-map", descriptionKey = OpsMessages.BACKUP_RESTORE_WORLD_MAP_HELP,
                                description = "World renames as old=new,old=new", defaultValue = "") String worldMap,
                        @Param(name = "owner-map", descriptionKey = OpsMessages.BACKUP_RESTORE_OWNER_MAP_HELP,
                                description = "Owner renames as old-uuid=new-uuid,old-uuid=new-uuid",
                                defaultValue = "") String ownerMap,
                        @Param(name = "confirm", descriptionKey = OpsMessages.BACKUP_RESTORE_CONFIRM_HELP,
                                description = "Required to write files when dry=false",
                                defaultValue = "false") boolean confirm,
                        @Param(name = "allow-unsigned", descriptionKey = OpsMessages.BACKUP_ALLOW_UNSIGNED_HELP,
                                description = "Apply a bundle that no trusted key signed",
                                defaultValue = "false") boolean allowUnsigned) {
        if (!allowed(sender)) {
            return;
        }
        async(() -> {
            BackupService service = service();
            Optional<Path> bundle = service.resolve(id);
            if (bundle.isEmpty()) {
                send(sender, OpsMessages.BACKUP_NOT_FOUND, WormholesLocalization.args(
                        MessageArgument.untrusted("id", id)));
                return;
            }
            applyBundle(sender, service, bundle.get(), id, dry, confirm, allowUnsigned, worldMap, ownerMap);
        });
    }

    @Director(name = "export", descriptionKey = OpsMessages.BACKUP_EXPORT_HELP,
            description = "Write a bundle to a file you can move between servers")
    public void export(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "file", descriptionKey = OpsMessages.BACKUP_EXPORT_FILE_HELP,
                               description = "Destination file; blank writes into the backups folder",
                               defaultValue = "") String file) {
        if (!allowed(sender)) {
            return;
        }
        async(() -> {
            try {
                BackupService service = service();
                long now = System.currentTimeMillis();
                Path target = file == null || file.isBlank()
                        ? service.folder().resolve("wormholes-export-" + EXPORT_STAMP.format(Instant.ofEpochMilli(now)) + ".zip")
                        : Path.of(file);
                BackupService.BackupEntry entry = service.exportTo(target, now);
                send(sender, OpsMessages.BACKUP_CREATED, WormholesLocalization.args(
                        MessageArgument.untrusted("id", entry.id()),
                        MessageArgument.untrusted("path", entry.file().toString())));
            } catch (IOException | RuntimeException failure) {
                failed(sender, failure);
            }
        });
    }

    @Director(name = "import", descriptionKey = OpsMessages.BACKUP_IMPORT_HELP,
            description = "Import a bundle exported from another server")
    public void importBundle(@Param(name = "sender", contextual = true) CommandSender sender,
                             @Param(name = "file", descriptionKey = OpsMessages.BACKUP_IMPORT_FILE_HELP,
                                     description = "Bundle file to read") String file,
                             @Param(name = "dry", descriptionKey = OpsMessages.BACKUP_IMPORT_DRY_HELP,
                                     description = "Report what would change without writing anything",
                                     defaultValue = "true") boolean dry,
                             @Param(name = "world-map", descriptionKey = OpsMessages.BACKUP_IMPORT_WORLD_MAP_HELP,
                                     description = "World renames as old=new,old=new", defaultValue = "") String worldMap,
                             @Param(name = "owner-map", descriptionKey = OpsMessages.BACKUP_IMPORT_OWNER_MAP_HELP,
                                     description = "Owner renames as old-uuid=new-uuid,old-uuid=new-uuid",
                                     defaultValue = "") String ownerMap,
                             @Param(name = "confirm", descriptionKey = OpsMessages.BACKUP_IMPORT_CONFIRM_HELP,
                                     description = "Required to write files when dry=false",
                                     defaultValue = "false") boolean confirm,
                             @Param(name = "allow-unsigned", descriptionKey = OpsMessages.BACKUP_ALLOW_UNSIGNED_HELP,
                                     description = "Apply a bundle that no trusted key signed",
                                     defaultValue = "false") boolean allowUnsigned) {
        if (!allowed(sender)) {
            return;
        }
        async(() -> applyBundle(sender, service(), Path.of(file), file, dry, confirm, allowUnsigned, worldMap, ownerMap));
    }

    @Director(name = "import-from", descriptionKey = OpsMessages.BACKUP_IMPORT_FROM_HELP,
            description = "Import portals from another portal plugin's files")
    public void importFrom(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "source", descriptionKey = OpsMessages.BACKUP_IMPORT_FROM_SOURCE_HELP,
                                   description = "stargate, advancedportals, multiverse, betterportals, or essentials")
                           String source,
                           @Param(name = "dry", descriptionKey = OpsMessages.BACKUP_IMPORT_FROM_DRY_HELP,
                                   description = "Report what would be created without creating anything",
                                   defaultValue = "true") boolean dry,
                           @Param(name = "frame", descriptionKey = OpsMessages.BACKUP_IMPORT_FROM_FRAME_HELP,
                                   description = "Frame size as width,height for sources without a frame",
                                   defaultValue = "") String frame) {
        if (!allowed(sender)) {
            return;
        }
        int[] frameSize;
        try {
            frameSize = PortalImporters.parseFrame(frame);
        } catch (IllegalArgumentException malformed) {
            failed(sender, malformed);
            return;
        }
        PortalImporter importer = PortalImporters.byId(source, frameSize[0], frameSize[1]);
        if (importer == null) {
            failed(sender, new IllegalArgumentException("Unknown import source " + source));
            return;
        }
        Path serverRoot = Path.of(".").toAbsolutePath().normalize();
        if (!importer.detect(serverRoot)) {
            send(sender, OpsMessages.IMPORT_NOT_DETECTED, WormholesLocalization.args(
                    MessageArgument.untrusted("name", importer.id()),
                    MessageArgument.untrusted("path", serverRoot.toString())));
            return;
        }
        UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
        async(() -> {
            PortalImportReport report = importer.importFrom(serverRoot, dry, new BukkitPortalFactoryBridge(owner));
            send(sender, OpsMessages.IMPORT_REPORT, WormholesLocalization.args(
                    MessageArgument.untrusted("name", importer.id()),
                    MessageArgument.untrusted("count", Integer.valueOf(report.createdCount())),
                    MessageArgument.untrusted("value", Integer.valueOf(report.skippedCount()))));
            for (PortalImportReport.Skip skip : report.skipped()) {
                send(sender, OpsMessages.IMPORT_SKIPPED_ROW, WormholesLocalization.args(
                        MessageArgument.untrusted("portal", skip.name()),
                        MessageArgument.untrusted("reason", skip.reason())));
            }
            for (String note : report.notes()) {
                Wormholes.i("[ops] import " + importer.id() + ": " + note);
            }
        });
    }

    private void applyBundle(CommandSender sender, BackupService service, Path bundle, String id,
                             boolean dry, boolean confirm, boolean allowUnsigned, String worldMap, String ownerMap) {
        WorldKeyRemap remap;
        try {
            remap = WorldKeyRemap.parse(worldMap, ownerMap);
        } catch (IllegalArgumentException malformed) {
            failed(sender, malformed);
            return;
        }
        BackupService.RestoreProposal proposal;
        try {
            proposal = service.propose(bundle, remap);
        } catch (IOException | RuntimeException failure) {
            failed(sender, failure);
            return;
        }
        RestorePlan plan = proposal.plan();
        BundleSignature signature = proposal.signature();
        send(sender, OpsMessages.BACKUP_SIGNATURE_OK, WormholesLocalization.args(
                MessageArgument.untrusted("name", signature.trusted() ? signature.signer() : id),
                MessageArgument.untrusted("fingerprint", signature.fingerprint())));
        if (signature.verdict() == BundleSignature.Verdict.BROKEN) {
            send(sender, OpsMessages.BACKUP_SIGNATURE_BROKEN, WormholesLocalization.args(
                    MessageArgument.untrusted("id", id)));
            return;
        }
        if (!signature.permitsRestore(allowUnsigned)) {
            send(sender, OpsMessages.BACKUP_SIGNATURE_UNTRUSTED, WormholesLocalization.args(
                    MessageArgument.untrusted("id", id),
                    MessageArgument.untrusted("fingerprint", signature.fingerprint())));
            return;
        }
        if (dry) {
            send(sender, OpsMessages.BACKUP_DRY_RUN, WormholesLocalization.args(
                    MessageArgument.untrusted("count", Integer.valueOf(plan.changeCount()))));
            return;
        }
        if (!confirm) {
            send(sender, OpsMessages.BACKUP_CONFIRM_REQUIRED);
            return;
        }
        freezeProjections();
        try {
            int written = service.apply(plan);
            send(sender, OpsMessages.BACKUP_RESTORED, WormholesLocalization.args(
                    MessageArgument.untrusted("count", Integer.valueOf(written)),
                    MessageArgument.untrusted("id", id)));
            send(sender, OpsMessages.BACKUP_RELOAD_REQUIRED);
        } catch (IOException failure) {
            failed(sender, failure);
        }
    }

    private static void freezeProjections() {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null) {
            return;
        }
        FoliaScheduler.runGlobal(plugin, () -> {
            ProjectionManager projections = Wormholes.projectionManager;
            if (projections != null) {
                projections.freezeProjections(FREEZE_SECONDS * 1000L);
            }
        });
    }

    private static BackupService service() {
        return BackupService.forRuntime(Wormholes.instance.getDataFolder().toPath());
    }

    private static int retain() {
        return Wormholes.settings == null ? 24 : Math.max(1, Wormholes.settings.getOps().backup.retain);
    }

    private static void async(Runnable work) {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null || !FoliaScheduler.runAsync(plugin, work)) {
            work.run();
        }
    }

    private static boolean allowed(CommandSender sender) {
        if (sender.hasPermission(PERMISSION)) {
            return true;
        }
        send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
        return false;
    }

    private static void failed(CommandSender sender, Throwable failure) {
        String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        send(sender, OpsMessages.BACKUP_FAILED, WormholesLocalization.args(
                MessageArgument.untrusted("reason", reason)));
    }

    private static void send(CommandSender sender, TextKey key) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, key));
    }

    private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, key, arguments));
    }
}
