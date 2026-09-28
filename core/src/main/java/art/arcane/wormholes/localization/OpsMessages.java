package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

/**
 * Message keys owned by the ops lane. Every id starts with "ops.". Add keys here and translate
 * them inside the "lane:ops" block of every languages/*.toml file.
 */
public final class OpsMessages {
    private static final MessageGroup GROUP = new MessageGroup("ops.");

    public static final TextKey CONSOLE_STARTED = GROUP.text("ops.console.started",
        "Metrics endpoint listening on {value}.");
    public static final TextKey CONSOLE_NO_TOKEN = GROUP.text("ops.console.no_token",
        "Metrics endpoint refused to start: set [ops.console] token.");
    public static final TextKey CONSOLE_FAILED = GROUP.text("ops.console.failed",
        "Metrics endpoint could not bind {value}: {reason}");

    public static final TextKey WEBMAP_PUBLISHED = GROUP.text("ops.webmap.published",
        "Published {count} portal markers to {name}.");

    public static final TextKey BACKUP_CREATED = GROUP.text("ops.backup.created",
        "&8[&6Wormholes&8] &aBackup &f{id}&a written to &f{path}&a.");
    public static final TextKey BACKUP_RESTORED = GROUP.text("ops.backup.restored",
        "&8[&6Wormholes&8] &aRestored &f{count}&a portals from &f{id}&a.");
    public static final TextKey BACKUP_DRY_RUN = GROUP.text("ops.backup.dry_run",
        "&8[&6Wormholes&8] &7Dry run: &f{count}&7 portals would change.");
    public static final TextKey BACKUP_LIST_ROW = GROUP.text("ops.backup.list_row",
        "&8[&6Wormholes&8] &f{id} &8| &f{count}&7 portals &8| &7{path}");
    public static final TextKey BACKUP_LIST_EMPTY = GROUP.text("ops.backup.list_empty",
        "&8[&6Wormholes&8] &7No backups under &f{path}&7.");
    public static final TextKey BACKUP_NOT_FOUND = GROUP.text("ops.backup.not_found",
        "&8[&6Wormholes&8] &cNo backup matches &f{id}&c.");
    public static final TextKey BACKUP_CONFIRM_REQUIRED = GROUP.text("ops.backup.confirm_required",
        "&8[&6Wormholes&8] &cRun this again with &fconfirm=true&c to apply it, or &fdry=true&c to see the plan.");
    public static final TextKey BACKUP_FAILED = GROUP.text("ops.backup.failed",
        "&8[&6Wormholes&8] &cBackup operation failed: &f{reason}");
    public static final TextKey BACKUP_SIGNATURE_BROKEN = GROUP.text("ops.backup.signature_broken",
        "&8[&6Wormholes&8] &cRefusing &f{id}&c: its signature does not match its contents.");
    public static final TextKey BACKUP_SIGNATURE_UNTRUSTED = GROUP.text("ops.backup.signature_untrusted",
        "&8[&6Wormholes&8] &cRefusing &f{id}&c: signed by &f{fingerprint}&c, which this server does not trust. Add &fallow-unsigned=true&c to apply it anyway.");
    public static final TextKey BACKUP_SIGNATURE_OK = GROUP.text("ops.backup.signature_ok",
        "&8[&6Wormholes&8] &7Signed by &f{name}&7 (&f{fingerprint}&7).");
    public static final TextKey BACKUP_RELOAD_REQUIRED = GROUP.text("ops.backup.reload_required",
        "&8[&6Wormholes&8] &7Portal files replaced. Run &f/wormholes reload&7 to load them.");

    public static final TextKey IMPORT_REPORT = GROUP.text("ops.import.report",
        "&8[&6Wormholes&8] &f{name}&7: &f{count}&7 portals imported, &f{value}&7 skipped.");
    public static final TextKey IMPORT_SKIPPED_ROW = GROUP.text("ops.import.skipped_row",
        "&8[&6Wormholes&8] &7{portal}&8: &f{reason}");
    public static final TextKey IMPORT_NOT_DETECTED = GROUP.text("ops.import.not_detected",
        "&8[&6Wormholes&8] &cNo {name} data found under &f{path}&c.");

    public static final TextKey PORTALS_ROW = GROUP.text("ops.portals.row",
        "&8[&6Wormholes&8] &f{portal} &8| &7{world} &8| &7{state} &8| &7{destination}");
    public static final TextKey PORTALS_PAGE = GROUP.text("ops.portals.page",
        "&8[&6Wormholes&8] &7Page &f{page}&7/&f{pages}");
    public static final TextKey PORTALS_EMPTY = GROUP.text("ops.portals.empty",
        "&8[&6Wormholes&8] &7No portals match those filters.");
    public static final TextKey PORTALS_NOT_FOUND = GROUP.text("ops.portals.not_found",
        "&8[&6Wormholes&8] &cNo portal matches &f{name}&c.");
    public static final TextKey PORTALS_AMBIGUOUS = GROUP.text("ops.portals.ambiguous",
        "&8[&6Wormholes&8] &c{count} portals match &f{name}&c; use the id.");
    public static final LinesKey PORTALS_INFO = GROUP.lines("ops.portals.info",
        "&8[&6Wormholes&8] &7Portal &f{portal} &8| &7{id}",
        "&8[&6Wormholes&8] &7World &f{world}&7 at &f{value}",
        "&8[&6Wormholes&8] &7State &f{state}&7, destination &f{destination}&7, owner &f{owner}");
    public static final TextKey PORTALS_TELEPORTED = GROUP.text("ops.portals.teleported",
        "&8[&6Wormholes&8] &aTeleported to &f{portal}&a.");
    public static final TextKey PORTALS_TELEPORT_UNSAFE = GROUP.text("ops.portals.teleport_unsafe",
        "&8[&6Wormholes&8] &eNo safe landing near &f{portal}&e; using the portal centre.");
    public static final TextKey PORTALS_RETARGETED = GROUP.text("ops.portals.retargeted",
        "&8[&6Wormholes&8] &a&f{portal}&a now points at &f{destination}&a.");
    public static final TextKey PORTALS_UNLINKED = GROUP.text("ops.portals.unlinked",
        "&8[&6Wormholes&8] &a&f{portal}&a is now unlinked.");
    public static final TextKey PORTALS_PRUNED = GROUP.text("ops.portals.pruned",
        "&8[&6Wormholes&8] &aPruned &f{count}&a orphaned links.");
    public static final TextKey PORTALS_PRUNE_DRY_RUN = GROUP.text("ops.portals.prune_dry_run",
        "&8[&6Wormholes&8] &7Dry run: &f{count}&7 orphaned links would be pruned.");
    public static final TextKey PORTALS_RENAMED_SERVER = GROUP.text("ops.portals.renamed_server",
        "&8[&6Wormholes&8] &aRewrote &f{count}&a gateway links from &f{name}&a to &f{server}&a.");

    public static final String BACKUP_HELP = "ops.command.help.backup";
    public static final String BACKUP_NOW_HELP = "ops.command.help.backup.now";
    public static final String BACKUP_LIST_HELP = "ops.command.help.backup.list";
    public static final String BACKUP_RESTORE_HELP = "ops.command.help.backup.restore";
    public static final String BACKUP_RESTORE_ID_HELP = "ops.command.help.backup.restore.id";
    public static final String BACKUP_RESTORE_DRY_HELP = "ops.command.help.backup.restore.dry";
    public static final String BACKUP_RESTORE_WORLD_MAP_HELP = "ops.command.help.backup.restore.world_map";
    public static final String BACKUP_RESTORE_OWNER_MAP_HELP = "ops.command.help.backup.restore.owner_map";
    public static final String BACKUP_RESTORE_CONFIRM_HELP = "ops.command.help.backup.restore.confirm";
    public static final String BACKUP_ALLOW_UNSIGNED_HELP = "ops.command.help.backup.allow_unsigned";
    public static final String BACKUP_EXPORT_HELP = "ops.command.help.backup.export";
    public static final String BACKUP_EXPORT_FILE_HELP = "ops.command.help.backup.export.file";
    public static final String BACKUP_IMPORT_HELP = "ops.command.help.backup.import";
    public static final String BACKUP_IMPORT_FILE_HELP = "ops.command.help.backup.import.file";
    public static final String BACKUP_IMPORT_DRY_HELP = "ops.command.help.backup.import.dry";
    public static final String BACKUP_IMPORT_WORLD_MAP_HELP = "ops.command.help.backup.import.world_map";
    public static final String BACKUP_IMPORT_OWNER_MAP_HELP = "ops.command.help.backup.import.owner_map";
    public static final String BACKUP_IMPORT_CONFIRM_HELP = "ops.command.help.backup.import.confirm";
    public static final String BACKUP_IMPORT_FROM_HELP = "ops.command.help.backup.import_from";
    public static final String BACKUP_IMPORT_FROM_SOURCE_HELP = "ops.command.help.backup.import_from.source";
    public static final String BACKUP_IMPORT_FROM_DRY_HELP = "ops.command.help.backup.import_from.dry";
    public static final String BACKUP_IMPORT_FROM_FRAME_HELP = "ops.command.help.backup.import_from.frame";

    public static final String PORTALS_HELP = "ops.command.help.portals";
    public static final String PORTALS_LIST_HELP = "ops.command.help.portals.list";
    public static final String PORTALS_LIST_WORLD_HELP = "ops.command.help.portals.list.world";
    public static final String PORTALS_LIST_TYPE_HELP = "ops.command.help.portals.list.type";
    public static final String PORTALS_LIST_OWNER_HELP = "ops.command.help.portals.list.owner";
    public static final String PORTALS_LIST_STATE_HELP = "ops.command.help.portals.list.state";
    public static final String PORTALS_LIST_PAGE_HELP = "ops.command.help.portals.list.page";
    public static final String PORTALS_FIND_HELP = "ops.command.help.portals.find";
    public static final String PORTALS_FIND_NAME_HELP = "ops.command.help.portals.find.name";
    public static final String PORTALS_INFO_HELP = "ops.command.help.portals.info";
    public static final String PORTALS_INFO_PORTAL_HELP = "ops.command.help.portals.info.portal";
    public static final String PORTALS_TP_HELP = "ops.command.help.portals.tp";
    public static final String PORTALS_TP_PORTAL_HELP = "ops.command.help.portals.tp.portal";
    public static final String PORTALS_RETARGET_HELP = "ops.command.help.portals.retarget";
    public static final String PORTALS_RETARGET_PORTAL_HELP = "ops.command.help.portals.retarget.portal";
    public static final String PORTALS_RETARGET_DESTINATION_HELP = "ops.command.help.portals.retarget.destination";
    public static final String PORTALS_UNLINK_HELP = "ops.command.help.portals.unlink";
    public static final String PORTALS_UNLINK_PORTAL_HELP = "ops.command.help.portals.unlink.portal";
    public static final String PORTALS_PRUNE_HELP = "ops.command.help.portals.prune";
    public static final String PORTALS_PRUNE_DRY_HELP = "ops.command.help.portals.prune.dry";
    public static final String PORTALS_PRUNE_CONFIRM_HELP = "ops.command.help.portals.prune.confirm";
    public static final String PORTALS_RENAME_SERVER_HELP = "ops.command.help.portals.rename_server";
    public static final String PORTALS_RENAME_SERVER_OLD_HELP = "ops.command.help.portals.rename_server.old";
    public static final String PORTALS_RENAME_SERVER_NEW_HELP = "ops.command.help.portals.rename_server.new";
    public static final String PORTALS_RENAME_SERVER_CONFIRM_HELP = "ops.command.help.portals.rename_server.confirm";

    static {
        GROUP.text(BACKUP_HELP, "Create, list, restore, and exchange portal backups");
        GROUP.text(BACKUP_NOW_HELP, "Write a backup of the portal data now");
        GROUP.text(BACKUP_LIST_HELP, "List the backups on disk");
        GROUP.text(BACKUP_RESTORE_HELP, "Restore portal files from a backup (dry=true shows the plan)");
        GROUP.text(BACKUP_RESTORE_ID_HELP, "Backup id from /wormholes admin backup list");
        GROUP.text(BACKUP_RESTORE_DRY_HELP, "Report what would change without writing anything");
        GROUP.text(BACKUP_RESTORE_WORLD_MAP_HELP, "World renames as old=new,old=new");
        GROUP.text(BACKUP_RESTORE_OWNER_MAP_HELP, "Owner renames as old-uuid=new-uuid,old-uuid=new-uuid");
        GROUP.text(BACKUP_RESTORE_CONFIRM_HELP, "Required to write files when dry=false");
        GROUP.text(BACKUP_EXPORT_HELP, "Write a bundle to a file you can move between servers");
        GROUP.text(BACKUP_EXPORT_FILE_HELP, "Destination file; blank writes into the backups folder");
        GROUP.text(BACKUP_IMPORT_HELP, "Import a bundle exported from another server");
        GROUP.text(BACKUP_IMPORT_FILE_HELP, "Bundle file to read");
        GROUP.text(BACKUP_IMPORT_DRY_HELP, "Report what would change without writing anything");
        GROUP.text(BACKUP_IMPORT_WORLD_MAP_HELP, "World renames as old=new,old=new");
        GROUP.text(BACKUP_IMPORT_OWNER_MAP_HELP, "Owner renames as old-uuid=new-uuid,old-uuid=new-uuid");
        GROUP.text(BACKUP_IMPORT_CONFIRM_HELP, "Required to write files when dry=false");
        GROUP.text(BACKUP_IMPORT_FROM_HELP, "Import portals from another portal plugin's files");
        GROUP.text(BACKUP_IMPORT_FROM_SOURCE_HELP, "stargate, advancedportals, multiverse, betterportals, or essentials");
        GROUP.text(BACKUP_IMPORT_FROM_DRY_HELP, "Report what would be created without creating anything");
        GROUP.text(BACKUP_IMPORT_FROM_FRAME_HELP, "Frame size as width,height for sources without a frame");

        GROUP.text(PORTALS_HELP, "List, inspect, and bulk-edit portals");
        GROUP.text(PORTALS_LIST_HELP, "List portals with optional filters");
        GROUP.text(PORTALS_LIST_WORLD_HELP, "Only portals in this world");
        GROUP.text(PORTALS_LIST_TYPE_HELP, "Only portals of this type: portal, wormhole, gateway, or rtp");
        GROUP.text(PORTALS_LIST_OWNER_HELP, "Only portals owned by this player name or uuid");
        GROUP.text(PORTALS_LIST_STATE_HELP, "Only portals in this state: open, closed, linked, or unlinked");
        GROUP.text(PORTALS_LIST_PAGE_HELP, "Page number");
        GROUP.text(PORTALS_FIND_HELP, "Find portals whose name contains the text");
        GROUP.text(PORTALS_FIND_NAME_HELP, "Text to search for");
        GROUP.text(PORTALS_INFO_HELP, "Show one portal's world, state, destination, and owner");
        GROUP.text(PORTALS_INFO_PORTAL_HELP, "Portal name or id");
        GROUP.text(PORTALS_TP_HELP, "Teleport to a portal, landing on safe ground");
        GROUP.text(PORTALS_TP_PORTAL_HELP, "Portal name or id");
        GROUP.text(PORTALS_RETARGET_HELP, "Point a portal at another local portal");
        GROUP.text(PORTALS_RETARGET_PORTAL_HELP, "Portal name or id to change");
        GROUP.text(PORTALS_RETARGET_DESTINATION_HELP, "Portal name or id to point at");
        GROUP.text(PORTALS_UNLINK_HELP, "Remove a portal's destination");
        GROUP.text(PORTALS_UNLINK_PORTAL_HELP, "Portal name or id");
        GROUP.text(PORTALS_PRUNE_HELP, "Remove links whose destination no longer exists");
        GROUP.text(PORTALS_PRUNE_DRY_HELP, "Report what would be pruned without changing anything");
        GROUP.text(PORTALS_PRUNE_CONFIRM_HELP, "Required to prune when dry=false");
        GROUP.text(PORTALS_RENAME_SERVER_HELP, "Rewrite cross-server links from one server name to another");
        GROUP.text(PORTALS_RENAME_SERVER_OLD_HELP, "Server name stored in the links today");
        GROUP.text(PORTALS_RENAME_SERVER_NEW_HELP, "Server name to store instead");
        GROUP.text(PORTALS_RENAME_SERVER_CONFIRM_HELP, "Required to rewrite the links");
    }

    private OpsMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
