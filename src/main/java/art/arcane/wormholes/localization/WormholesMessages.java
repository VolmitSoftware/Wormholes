package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.director.DirectorMessages;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WormholesMessages {
    public static final String ENGLISH_LOCALE = VolmitLocales.ENGLISH;

    private static final List<MessageKey> KEYS = new ArrayList<>();

    public static final TextKey VERSION_DESCRIPTION = text("command.help.version", "Show the installed plugin version");
    public static final TextKey DEBUG_DUMP_DESCRIPTION = text("command.help.debug_dump", "Create and optionally upload a diagnostic report");
    public static final TextKey DEBUG_DUMP_UPLOAD = text("command.help.debug_dump_upload", "Upload the report to mclo.gs");
    public static final TextKey HELP_LANGUAGE = text("command.help.language", "Choose your language or the server default");
    public static final TextKey COMMAND_ROOT_DESCRIPTION = text("command.help.root", "Wormholes command root");
    public static final TextKey COMMAND_WAND_DESCRIPTION = text("command.help.wand", "Give yourself the portal wand and a wormhole rune");
    public static final TextKey COMMAND_WAND_RUNE_DESCRIPTION = text("command.help.wand.rune", "Include a wormhole rune (rune=false gives only the wand)");
    public static final TextKey COMMAND_DOOR_DESCRIPTION = text("command.help.door", "Give a survival Dimensional Door item");
    public static final TextKey COMMAND_DOOR_TYPE_DESCRIPTION = text("command.help.door.type", "pair | personal | public | pair_trapdoor | personal_trapdoor | public_trapdoor");
    public static final TextKey COMMAND_RELOAD_DESCRIPTION = text("command.help.reload", "Reload Wormholes configuration and language files");
    public static final TextKey COMMAND_DEBUG_GROUP = text("command.help.debug", "Wormholes diagnostic tools");
    public static final TextKey COMMAND_DEBUG_DESCRIPTION = text("command.help.debug_toggle", "Toggle verbose console logs and one-second telemetry");
    public static final TextKey COMMAND_STATS_DESCRIPTION = text("command.help.stats", "Print the live stats-snapshot file path, optionally force a refresh with now=true");
    public static final TextKey COMMAND_STATS_NOW_DESCRIPTION = text("command.help.stats.now", "Force-rebuild the snapshot synchronously");
    public static final TextKey COMMAND_INFO_DESCRIPTION = text("command.help.info", "Show portal building instructions");
    public static final TextKey COMMAND_POCKET_DESCRIPTION = text("command.help.pocket", "Inspect and reshape pocket dimensions");
    public static final TextKey COMMAND_POCKET_INFO_DESCRIPTION = text("command.help.pocket.info", "Show the size, materials, and bounds of the pocket you are standing in");
    public static final TextKey COMMAND_POCKET_RESIZE_DESCRIPTION = text("command.help.pocket.resize", "Rebuild the pocket you are standing in at a new size or material");
    public static final TextKey COMMAND_POCKET_RESIZE_SIZE_DESCRIPTION = text("command.help.pocket.resize.size", "New room edge in blocks, or 0 to keep the current size");
    public static final TextKey COMMAND_POCKET_RESIZE_MATERIAL_DESCRIPTION = text("command.help.pocket.resize.material", "New wall, floor, and ceiling block, or keep");
    public static final TextKey COMMAND_POCKET_RESIZE_DOOR_DESCRIPTION = text("command.help.pocket.resize.door", "New exit door block, or keep");
    public static final TextKey COMMAND_POCKET_RESIZE_CONFIRM_DESCRIPTION = text("command.help.pocket.resize.confirm", "Required when the change would destroy or move anything");
    public static final TextKey COMMAND_POCKET_RESIZE_ALL_DESCRIPTION = text("command.help.pocket.resizeall", "Rebuild every existing pocket at a new size or material");
    public static final TextKey COMMAND_ADMIN_DESCRIPTION = text("command.help.admin", "Destructive Wormholes maintenance commands");
    public static final TextKey COMMAND_DELETE_PORTALS_DESCRIPTION = text("command.help.admin.delete_portals", "Delete every local portal and saved portal link");
    public static final TextKey COMMAND_DELETE_EVERYTHING_DESCRIPTION = text("command.help.admin.delete_everything", "Reset Wormholes data, config, trust, identity, and network state");
    public static final TextKey COMMAND_FREEZE_DESCRIPTION = text("command.help.admin.freeze", "Freeze all portal projections in place for a number of seconds (seconds=0 resumes)");
    public static final TextKey COMMAND_FREEZE_SECONDS_DESCRIPTION = text("command.help.admin.freeze.seconds", "How long to hold the frozen frame (5-300, 0 resumes now)");
    public static final TextKey COMMAND_FLUSH_DESCRIPTION = text("command.help.admin.flush", "Revert every observer's projected blocks to ground truth and rebuild them");
    public static final TextKey COMMAND_NETWORK_DESCRIPTION = text("command.help.network", "Cross-server wormhole network");
    public static final TextKey COMMAND_NETWORK_IMPORT_DESCRIPTION = text("command.help.network.import", "Same as /wh server import; accepts a server or portal code");
    public static final TextKey COMMAND_NETWORK_CODE_DESCRIPTION = text("command.help.network.import.code", "Server or portal code from the other server's export");
    public static final TextKey COMMAND_NETWORK_STATUS_DESCRIPTION = text("command.help.network.status", "Show peer connection status");
    public static final TextKey COMMAND_NETWORK_DOCTOR_DESCRIPTION = text("command.help.network.doctor", "Explain why network peers are not connecting");
    public static final TextKey COMMAND_SERVER_DESCRIPTION = text("command.help.server", "Cross-server travel: connect to, list, and exchange linked servers");
    public static final TextKey COMMAND_SERVER_CONNECT_DESCRIPTION = text("command.help.server.connect", "Transfer yourself to a linked server (shorthand: /wh server \\<name>)");
    public static final TextKey COMMAND_SERVER_CONNECT_NAME_DESCRIPTION = text("command.help.server.connect.name", "Linked server name (see /wh server list)");
    public static final TextKey COMMAND_SERVER_EXPORT_DESCRIPTION = text("command.help.server.export", "Export this server as a code other servers can import");
    public static final TextKey COMMAND_SERVER_IMPORT_DESCRIPTION = text("command.help.server.import", "Import a server (WHS2.) or portal (WHP6.) code exported by another server");
    public static final TextKey COMMAND_SERVER_IMPORT_CODE_DESCRIPTION = text("command.help.server.import.code", "Code from the other server's export");
    public static final TextKey COMMAND_SERVER_LIST_DESCRIPTION = text("command.help.server.list", "List linked servers and their connection state");
    public static final TextKey COMMAND_SERVER_REMOVE_DESCRIPTION = text("command.help.server.remove", "Forget a linked server (deletes its route and trusted key)");
    public static final TextKey COMMAND_SERVER_REMOVE_NAME_DESCRIPTION = text("command.help.server.remove.name", "Linked server name to forget");

    public static final TextKey COMMAND_NO_PERMISSION = text("command.error.no_permission", "&8[&6Wormholes&8] &cYou do not have permission.");
    public static final TextKey COMMAND_NO_PERMISSION_USE = text("command.error.no_permission_use", "&8[&6Wormholes&8] &cYou do not have permission to use that command.");
    public static final TextKey COMMAND_ONLY_PLAYERS = text("command.error.only_players", "&8[&6Wormholes&8] &cOnly players can receive items.");
    public static final TextKey COMMAND_USAGE_HELP = text("command.error.usage", "&7Usage: &f/wormholes help");
    public static final LinesKey COMMAND_PUBLIC_HELP = lines("command.public_help",
            "&8[&6Wormholes&8] &7Portal help: &f/wormholes info",
            "&8[&6Wormholes&8] &7Use the Portal Wand on a portal to open its destination, view, travel, and access controls.");
    public static final TextKey COMMAND_GRANTED_WAND = text("command.wand.granted_wand", "&8[&6Wormholes&8] &aPortal Wand granted.");
    public static final LinesKey COMMAND_GRANTED_STARTER = lines("command.wand.granted_starter",
            "&8[&6Wormholes&8] &aPortal Wand and 1 Wormhole Rune granted.",
            "&8[&6Wormholes&8] &7Build TWO wormhole-rune shapes (any connected shape on one flat surface), link them, and stand within {range} blocks to see the projection.",
            "&8[&6Wormholes&8] &7Run &f/wormholes info&7 for the full step-by-step.");
    public static final TextKey COMMAND_DOORS_UNAVAILABLE = text("command.door.unavailable", "&8[&6Wormholes&8] &cDimensional Doors are unavailable.");
    public static final TextKey COMMAND_UNKNOWN_DOOR = text("command.door.unknown_type", "&8[&6Wormholes&8] &cUnknown door type. Use pair, personal, public, pair_trapdoor, personal_trapdoor, or public_trapdoor.");
    public static final TextKey COMMAND_EMPTY_DOOR = text("command.door.empty_type", "Door type cannot be empty");
    public static final TextKey COMMAND_GRANTED_DOOR = text("command.door.granted", "&8[&6Wormholes&8] &aGranted a &f{type}&a dimensional door item.");
    public static final TextKey COMMAND_POCKET_NOT_INSIDE = text("command.pocket.not_inside", "&8[&6Wormholes&8] &cStand inside a pocket dimension to run this.");
    public static final LinesKey COMMAND_POCKET_INFO = lines("command.pocket.info",
            "&8[&6Wormholes&8] &7Pocket &f{space}",
            "&8[&6Wormholes&8] &7Size &f{size}&7 blocks, walls &f{material}&7, exit door &f{door}",
            "&8[&6Wormholes&8] &7Bounds &f{minimum}&7 to &f{maximum}");
    public static final TextKey COMMAND_POCKET_INVALID_SIZE = text("command.pocket.invalid_size", "&8[&6Wormholes&8] &cPocket size must be between {minimum} and {maximum} blocks.");
    public static final TextKey COMMAND_POCKET_INVALID_SHELL_MATERIAL = text("command.pocket.invalid_shell_material", "&8[&6Wormholes&8] &c{material} cannot be a pocket wall. Use a solid block that does not fall.");
    public static final TextKey COMMAND_POCKET_INVALID_DOOR_MATERIAL = text("command.pocket.invalid_door_material", "&8[&6Wormholes&8] &c{material} cannot be a pocket exit door. Use a door that opens by hand; iron doors are rejected.");
    public static final TextKey COMMAND_POCKET_UNCHANGED = text("command.pocket.unchanged", "&8[&6Wormholes&8] &eThat pocket already has this size and these materials.");
    public static final TextKey COMMAND_POCKET_CONTAINERS_NOT_EMPTY = text("command.pocket.containers_not_empty", "&8[&6Wormholes&8] &cPocket resize stopped: &f{containers}&c non-empty containers would be destroyed. Empty them before retrying; &fconfirm=true&c cannot override this safety check.");
    public static final LinesKey COMMAND_POCKET_CONFIRM_REQUIRED = lines("command.pocket.confirm_required",
            "&8[&6Wormholes&8] &cRebuilding at {size} blocks would destroy &f{blocks}&c placed blocks and move &f{entities}&c entities.",
            "&8[&6Wormholes&8] &7Blocks outside the new walls are destroyed; displaced entities are moved to the entry.",
            "&8[&6Wormholes&8] &7Run the same command with &fconfirm=true&7 to go ahead.");
    public static final TextKey COMMAND_POCKET_RESIZED = text("command.pocket.resized", "&8[&6Wormholes&8] &aPocket rebuilt at &f{size}&a blocks (was &f{previous}&a), walls &f{material}&a, exit door &f{door}.");
    public static final TextKey COMMAND_POCKET_FAILED = text("command.pocket.failed", "&8[&6Wormholes&8] &cThe pocket could not be rebuilt. Check the console.");
    public static final TextKey COMMAND_POCKET_WORLD_UNAVAILABLE = text("command.pocket.world_unavailable", "&8[&6Wormholes&8] &cThe pocket dimension is not loaded.");
    public static final TextKey COMMAND_POCKET_DOES_NOT_FIT = text("command.pocket.does_not_fit", "&8[&6Wormholes&8] &cA {size} block room does not fit the pocket dimension build height.");
    public static final TextKey COMMAND_POCKET_BULK_STARTED = text("command.pocket.bulk_started", "&8[&6Wormholes&8] &7Rebuilding &f{count}&7 pockets.");
    public static final TextKey COMMAND_POCKET_BULK_FINISHED = text("command.pocket.bulk_finished", "&8[&6Wormholes&8] &aRebuilt &f{resized}&a, skipped &f{skipped}&a, failed &f{failed}.");
    public static final TextKey COMMAND_RELOADED = text("command.reload.applied", "&8[&6Wormholes&8] &aWormholes configuration and language files reloaded.");
    public static final TextKey COMMAND_RELOADED_LANGUAGE_RETAINED = text("command.reload.language_retained", "&8[&6Wormholes&8] &eConfiguration reloaded, but the language file was rejected. The last valid language remains active; check the console.");
    public static final TextKey COMMAND_RELOAD_FAILED = text("command.reload.failed", "&8[&6Wormholes&8] &cConfiguration reload failed; the edit remains pending and will be retried. Check the console.");
    public static final TextKey COMMAND_STATS_UNAVAILABLE = text("command.stats.unavailable", "&8[&6Wormholes&8] &cStats snapshot writer is unavailable.");
    public static final TextKey COMMAND_STATS_REFRESHED = text("command.stats.refreshed", "&8[&6Wormholes&8] &aSnapshot refreshed.");
    public static final LinesKey COMMAND_STATS_PATH = lines("command.stats.path",
            "&8[&6Wormholes&8] &7Snapshot file: &f{path}",
            "&8[&6Wormholes&8] &8Tail this file to share live network/view state. The file is overwritten in place each interval.");
    public static final LinesKey COMMAND_INFO = lines("command.info",
            "&8[&6Wormholes&8] &7&lHow to build a Wormhole&r",
            "&81. &7Get a Portal Wand and Wormhole Runes from your server or an administrator.",
            "&82. &7Place the runes in any connected shape on one flat surface.",
            "&7   Any connected shape works: rectangles, lines (3x1), single blocks, L-shapes, crosses.",
            "&7   The runes must sit flat on one axis-aligned wall, floor, or ceiling.",
            "&83. &7Hold the Portal Wand and &fleft-click any rune block&7 to form the portal.",
            "&84. &7Build a SECOND portal somewhere else (any distance, any world).",
            "&85. &7Open the portal menu with the wand while looking at the portal, or sneak with an empty main hand and right-click a portal block (owner or admin).",
            "&7   Choose &fDestination&7 and select the other portal. Repeat from the other side.",
            "&7   Orientation and access controls are grouped into their own simple menus.",
            "&86. &7Stand within {range} blocks of either portal (current global activation range) — the destination world will project through the frame and walking in teleports you.",
            "&7Administrators can create supplies with &f/wormholes wand");
    public static final PluralKey COMMAND_DELETED_PORTALS = plural("command.admin.deleted_portals", "count", Map.of(
            "one", "&8[&6Wormholes&8] &aDeleted &f{count}&a portal and cleared local portal links.",
            "other", "&8[&6Wormholes&8] &aDeleted &f{count}&a portals and cleared local portal links."
    ));
    public static final PluralKey COMMAND_RESET_EVERYTHING = plural("command.admin.reset_everything", "count", Map.of(
            "one", "&8[&6Wormholes&8] &aWormholes reset to default state. Deleted &f{count}&a portal, closed network connections, and regenerated default config files.",
            "other", "&8[&6Wormholes&8] &aWormholes reset to default state. Deleted &f{count}&a portals, closed network connections, and regenerated default config files."
    ));
    public static final TextKey COMMAND_DELETE_FAILED = text("command.admin.delete_failed", "&8[&6Wormholes&8] &cFailed to delete all portals. Check console for the full stacktrace.");
    public static final TextKey COMMAND_DELETE_SCHEDULE_FAILED = text("command.admin.delete_schedule_failed", "&8[&6Wormholes&8] &cCould not schedule the portal reset.");
    public static final TextKey COMMAND_RESET_FAILED = text("command.admin.reset_failed", "&8[&6Wormholes&8] &cFailed to reset Wormholes. Check console for the full stacktrace.");
    public static final TextKey COMMAND_RESET_SCHEDULE_FAILED = text("command.admin.reset_schedule_failed", "&8[&6Wormholes&8] &cCould not schedule the Wormholes reset.");
    public static final TextKey COMMAND_PROJECTION_FROZEN = text("command.admin.projection_frozen", "&8[&6Wormholes&8] &aFroze all portal projections in place for &f{seconds}&a seconds. They resume automatically.");
    public static final TextKey COMMAND_PROJECTION_RESUMED = text("command.admin.projection_resumed", "&8[&6Wormholes&8] &aPortal projections resumed.");
    public static final PluralKey COMMAND_PROJECTION_FLUSHED = plural("command.admin.projection_flushed", "count", Map.of(
            "one", "&8[&6Wormholes&8] &aFlushed &f{count}&a projection observer; clients are rebuilding from ground truth.",
            "other", "&8[&6Wormholes&8] &aFlushed &f{count}&a projection observers; clients are rebuilding from ground truth."
    ));
    public static final TextKey COMMAND_PROJECTION_FAILED = text("command.admin.projection_failed", "&8[&6Wormholes&8] &cFailed to update portal projections. Check console for the full stacktrace.");
    public static final TextKey COMMAND_PROJECTION_SCHEDULE_FAILED = text("command.admin.projection_schedule_failed", "&8[&6Wormholes&8] &cCould not schedule the projection update.");

    public static final TextKey NETWORK_NOT_INITIALIZED = text("network.not_initialized", "&8[&6Wormholes&8] &cNetworking is not initialized.");
    public static final TextKey NETWORK_DISABLED = text("network.status.disabled", "&8[&6Wormholes&8] &7Networking is &cdisabled&7 (plugins/Wormholes/wormholes.toml).");
    public static final TextKey NETWORK_NOT_RUNNING = text("network.status.not_running", "&8[&6Wormholes&8] &cNetworking is enabled but not running. Check the identity store and network port.");
    public static final TextKey NETWORK_LISTENING = text("network.status.listening", "&8[&6Wormholes&8] &7This server: &f{server}&7 listening on &f{address}");
    public static final TextKey NETWORK_OUTBOUND_ONLY = text("network.status.outbound_only", "&8[&6Wormholes&8] &7This server: &f{server}&7 outbound-only Boat mode");
    public static final TextKey NETWORK_PUBLIC_KEY = text("network.status.public_key", "&8[&6Wormholes&8] &8Public key: {fingerprint}");
    public static final TextKey NETWORK_NO_ROUTES = text("network.status.no_routes", "&8[&6Wormholes&8] &7No peer routes linked yet.");
    public static final TextKey NETWORK_PEER = text("network.status.peer", "&8[&6Wormholes&8] &f{server}&7 {state} &7{address}{rtt}");
    public static final TextKey NETWORK_LAST_ATTEMPT = text("network.status.last_attempt", "&8[&6Wormholes&8] &8  last attempt: {error}");
    public static final TextKey NETWORK_DOCTOR_CLEAR = text("network.doctor.clear", "&8[&6Wormholes&8] &aNo network setup issues detected.");
    public static final TextKey NETWORK_DOCTOR_HEADER = text("network.doctor.header", "&8[&6Wormholes&8] &eNetwork doctor:");
    public static final TextKey NETWORK_DOCTOR_LINE = text("network.doctor.line", "&8[&6Wormholes&8] &7- {diagnostic}");
    public static final TextKey NETWORK_BUILDING_CODE = text("network.code.building", "&8Building portal code...");
    public static final TextKey NETWORK_COPY_CODE = text("network.code.copy", "&6&l[Copy portal code: {portal}]&r");
    public static final TextKey NETWORK_COPY_CODE_HOVER = text("network.code.copy_hover", "&7Click to copy. Paste it on the other server:\nportal menu > Import, or /wh server import \\<code>");
    public static final TextKey NETWORK_CODE_FINGERPRINT = text("network.code.fingerprint", "&8Contains this server's address and public key fingerprint {fingerprint}.");
    public static final TextKey NETWORK_CODE_TOO_LONG = text("network.code.too_long", "&eThis code is too long to paste into chat - use /wh server import \\<code> on the other server instead.");
    public static final TextKey NETWORK_CODE_INVALID = text("network.code.invalid", "&cInvalid portal code. Codes start with {prefix} - if pasted into chat it may have been truncated; try /wh server import \\<code>. Codes from older plugin versions must be re-exported.");
    public static final TextKey NETWORK_CODE_SAME_SERVER = text("network.code.same_server", "&cThat code is from this server.");
    public static final TextKey NETWORK_CODE_SAME_IDENTITY = text("network.code.same_identity", "&cThat code resolved to this server identity ({server}). Re-export from the other server after both servers restart with their own Wormholes identity.");
    public static final TextKey NETWORK_LINKED = text("network.code.linked", "&aLinked &f{portal}&7 -> &f{destination}&a on &f{server}&a. It opens once the servers connect.");
    public static final TextKey NETWORK_ROUTE_SAVED = text("network.code.route_saved", "&aSaved route to {server} with public key {fingerprint}. '{portal}' will appear in gateway Link menus once connected.");
    public static final TextKey NETWORK_CHECK_STATUS = text("network.code.check_status", "&8Check /wh network status for the connection state.");
    public static final TextKey NETWORK_USING_ADDRESS = text("network.code.using_address", "&7Using {address} in this portal code; the public address auto-detects and self-corrects over the signed handshake if it changes.");

    public static final TextKey SERVER_COPY_CODE = text("server.code.copy", "&6&l[Copy server code: {server}]&r");
    public static final TextKey SERVER_COPY_CODE_HOVER = text("server.code.copy_hover", "&7Click to copy. Paste it on the other server:\n/wh server import \\<code>");
    public static final TextKey SERVER_CODE_RAW = text("server.code.raw", "&7Server code: &f{code}");
    public static final TextKey SERVER_SAVED = text("server.code.saved", "&aSaved server &f{server}&a with public key {fingerprint}. Connect with &f/wh server {server}&a.");
    public static final TextKey SERVER_CONNECTING = text("server.connect.sending", "&8[&6Wormholes&8] &aSending you to &f{server}&a...");
    public static final TextKey SERVER_NOT_READY = text("server.connect.not_ready", "&8[&6Wormholes&8] &c{server} is not reachable right now. Check /wh network status.");
    public static final TextKey SERVER_CONNECT_FAILED = text("server.connect.failed", "&8[&6Wormholes&8] &cCould not transfer you to {server}. Check the console and /wh network status.");
    public static final TextKey SERVER_ONLY_PLAYERS = text("server.connect.only_players", "&8[&6Wormholes&8] &cOnly players can connect to another server.");
    public static final TextKey SERVER_UNKNOWN = text("server.unknown", "&8[&6Wormholes&8] &cUnknown server '&f{server}&c'. Import its code first or see /wh server list.");
    public static final TextKey SERVER_LIST_HEADER = text("server.list.header", "&8[&6Wormholes&8] &7Linked servers:");
    public static final TextKey SERVER_LIST_EMPTY = text("server.list.empty", "&8[&6Wormholes&8] &7No servers linked yet. Use &f/wh server import \\<code>&7.");
    public static final TextKey SERVER_LIST_ENTRY = text("server.list.entry", "&8[&6Wormholes&8] &f{server}&7 {state} &8{address}");
    public static final TextKey SERVER_REMOVED = text("server.removed", "&8[&6Wormholes&8] &aRemoved server &f{server}&a and its trusted key.");
    public static final TextKey SERVER_NAME_EMPTY = text("server.name_empty", "Server name cannot be empty");

    public static final LinesKey ITEM_PORTAL_WAND = lines("item.portal_wand", "&6&lPortal Wand&r");
    public static final LinesKey ITEM_PORTAL_RUNE = lines("item.portal_rune", "&6&lPortal Rune&r");
    public static final LinesKey ITEM_WORMHOLE_RUNE = lines("item.wormhole_rune", "&6&lWormhole Rune&r");
    public static final LinesKey ITEM_ENTANGLED_PAIR = lines("item.door.entangled_pair",
            "&6Entangled Door Pair",
            "&7Contains two automatically linked Wormhole Doors.",
            "&7Use it to unpack endpoints A and B.");
    public static final LinesKey ITEM_PAIRED_DOOR = lines("item.door.paired",
            "&6Wormhole Door {endpoint}",
            "&7Automatically linked to endpoint {other}.",
            "&7Open the door and physically cross its threshold.");
    public static final LinesKey ITEM_PERSONAL_DOOR = lines("item.door.personal",
            "&bPersonal Dimension Door",
            "&7Each traveler enters their own persistent dimension.",
            "&7The same traveler always reaches the same place.");
    public static final LinesKey ITEM_PUBLIC_DOOR = lines("item.door.public",
            "&6Public Dimension Door",
            "&7Every traveler enters this door's shared dimension.",
            "&7Breaking and moving it preserves the shared destination.");
    public static final LinesKey ITEM_RETURN_DOOR = lines("item.door.return",
            "&aDimensional Exit Door",
            "&7Returns travelers from this pocket dimension.",
            "&7This door is bound to its pocket.");
    public static final LinesKey ITEM_DOOR_SKIN = lines("item.door.skin",
            "&6Dimensional Door Skin",
            "&7Combine a dimensional door with a player-operable door.");
    public static final LinesKey ITEM_ENTANGLED_PAIR_RECIPE = lines("item.door.entangled_pair_recipe",
            "&6Entangled Door Pair",
            "&7Contains two automatically linked Wormhole Doors.");
    public static final LinesKey ITEM_PERSONAL_DOOR_RECIPE = lines("item.door.personal_recipe",
            "&bPersonal Dimension Door",
            "&7Each traveler enters their own persistent dimension.");
    public static final LinesKey ITEM_PUBLIC_DOOR_RECIPE = lines("item.door.public_recipe",
            "&6Public Dimension Door",
            "&7Every traveler enters this door's shared dimension.");
    public static final LinesKey ITEM_ENTANGLED_TRAPDOOR_PAIR = lines("item.door.entangled_trapdoor_pair",
            "&6Entangled Trapdoor Pair",
            "&7Contains two automatically linked Wormhole Trapdoors.",
            "&7Use it to unpack endpoints A and B.");
    public static final LinesKey ITEM_PAIRED_TRAPDOOR = lines("item.door.paired_trapdoor",
            "&6Wormhole Trapdoor {endpoint}",
            "&7Automatically linked to endpoint {other}.",
            "&7Open the trapdoor and drop through it.");
    public static final LinesKey ITEM_PERSONAL_TRAPDOOR = lines("item.door.personal_trapdoor",
            "&bPersonal Dimension Trapdoor",
            "&7Each traveler enters their own persistent dimension.",
            "&7The same traveler always reaches the same place.");
    public static final LinesKey ITEM_PUBLIC_TRAPDOOR = lines("item.door.public_trapdoor",
            "&6Public Dimension Trapdoor",
            "&7Every traveler enters this trapdoor's shared dimension.",
            "&7Breaking and moving it preserves the shared destination.");
    public static final LinesKey ITEM_TRAPDOOR_SKIN = lines("item.door.trapdoor_skin",
            "&6Dimensional Trapdoor Skin",
            "&7Combine a dimensional trapdoor with a hand-openable trapdoor.");
    public static final LinesKey ITEM_ENTANGLED_TRAPDOOR_PAIR_RECIPE = lines("item.door.entangled_trapdoor_pair_recipe",
            "&6Entangled Trapdoor Pair",
            "&7Contains two automatically linked Wormhole Trapdoors.");
    public static final LinesKey ITEM_PERSONAL_TRAPDOOR_RECIPE = lines("item.door.personal_trapdoor_recipe",
            "&bPersonal Dimension Trapdoor",
            "&7Each traveler enters their own persistent dimension.");
    public static final LinesKey ITEM_PUBLIC_TRAPDOOR_RECIPE = lines("item.door.public_trapdoor_recipe",
            "&6Public Dimension Trapdoor",
            "&7Every traveler enters this trapdoor's shared dimension.");

    public static final TextKey PORTAL_RTP_RUNE_UNSUPPORTED = text("portal.form.rtp_unsupported", "&cRandom teleport portals cannot be formed from runes.");
    public static final TextKey PORTAL_FORMING = text("portal.form.forming", "&bForming portal... {type} runes must connect on one flat wall, floor, or ceiling.");
    public static final TextKey PORTAL_MUST_BE_FLAT = text("portal.form.must_be_flat", "&cPortal must lie flat on one wall, floor, or ceiling.");
    public static final TextKey PORTAL_FORM_INTERRUPTED = text("portal.form.interrupted", "&cPortal formation was interrupted; the reserved runes were restored.");
    public static final TextKey PORTAL_RUNE_PLACED = text("portal.form.rune_placed", "&bRune placed. Build any connected shape on one flat surface, then left-click any rune with the Portal Wand.");
    public static final TextKey PORTAL_OPENED = text("portal.form.opened", "&aPortal opened. Hold the wand and CLICK the portal to configure.");
    public static final TextKey PORTAL_COOLDOWN = text("portal.travel.cooldown", "&6Portal cooling down");
    public static final TextKey PORTAL_ACCESS_DENIED = text("portal.travel.access_denied", "&cPortal access denied");
    public static final TextKey PORTAL_COST_INSUFFICIENT = text("portal.travel.cost_insufficient", "&cYou need {quantity}x {item} to use this portal.");
    public static final TextKey PORTAL_COST_VAULT_INSUFFICIENT = text("portal.travel.cost_vault_insufficient", "&cYou need {amount} to use this portal.");
    public static final TextKey PORTAL_COST_VAULT_UNAVAILABLE = text("portal.travel.cost_vault_unavailable", "&cThis portal's economy cost is unavailable.");
    public static final TextKey PORTAL_COST_TRANSACTION_FAILED = text("portal.travel.cost_transaction_failed", "&cThe portal could not process your travel cost.");
    public static final TextKey PORTAL_DESTINATION_UNAVAILABLE = text("portal.travel.destination_unavailable", "&cPortal destination unavailable");
    public static final TextKey PORTAL_ARRIVAL_DENIED = text("portal.travel.arrival_denied", "&cThe destination portal refused your arrival.");
    public static final TextKey PORTAL_ARRIVAL_RETURNED = text("portal.travel.arrival_returned", "&cThe destination portal refused your arrival; returning you to {server}");
    public static final TextKey DOOR_TRANSIT_SHUTDOWN = text("door.transit.shutdown", "Dimensional Doors shut down before the transit completed.");
    public static final TextKey PORTAL_EDIT_DENIED = text("portal.edit.denied", "&cOnly the portal owner or an administrator can edit this portal.");
    public static final TextKey PORTAL_DELETED = text("portal.deleted", "&c{portal} Deleted");
    public static final TextKey PORTAL_ARRIVAL_FAILED = text("portal.travel.arrival_failed", "&cPortal arrival could not be placed; you remain at the destination spawn");
    public static final TextKey PORTAL_TRANSFER_COOLDOWN = text("portal.travel.transfer_cooldown", "&6Cross-server portal cooling down: {seconds}s");
    public static final TextKey PORTAL_DESTINATION_UNREACHABLE = text("portal.travel.destination_unreachable", "&cDestination server unreachable");
    public static final TextKey PORTAL_DESTINATION_UNREACHABLE_DETAIL = text("portal.travel.destination_unreachable_detail", "&cDestination server unreachable: {reason}");
    public static final TextKey PORTAL_TRANSFER_BLOCKED = text("portal.travel.transfer_blocked", "&cPortal transfer blocked: {reason}");
    public static final TextKey PORTAL_TRANSFER_BLOCKED_RETRY = text("portal.travel.transfer_blocked_retry", "&cPortal transfer blocked: {reason} (retry in {seconds}s)");
    public static final TextKey PORTAL_TRANSFER_HOLDING = text("portal.travel.transfer_holding", "&6Linking to the destination server...");
    public static final TextKey PORTAL_TRANSFER_INTERRUPTED = text("portal.travel.transfer_interrupted", "&cPortal transfer interrupted: you moved away from the portal.");
    public static final TextKey PORTAL_TRANSFER_SOURCE_UNAVAILABLE = text("portal.travel.transfer_source_unavailable", "&cPortal transfer interrupted: the source portal is no longer available.");

    public static final TextKey WAND_CORNER_A = text("wand.selection.corner_a", "&bCorner A set. Right-click the opposite corner.");
    public static final TextKey WAND_CORNER_B = text("wand.selection.corner_b", "&bCorner B set. Left-click the opposite corner.");
    public static final TextKey WAND_NOT_FLAT = text("wand.selection.not_flat", "&cSelection must be one block thick. Re-click a corner to flatten it.");
    public static final TextKey WAND_TOO_LARGE = text("wand.selection.too_large", "&cSelection too large: {count} cells (max {maximum}).");
    public static final PluralKey WAND_SELECTED = plural("wand.selection.selected", "count", Map.of(
            "one", "&bSelected {count} cell. Left-click the glass pane to open the portal.",
            "other", "&bSelected {count} cells. Left-click the glass pane to open the portal."
    ));
    public static final TextKey WAND_OPENING = text("wand.selection.opening", "&bOpening portal...");
    public static final TextKey WAND_OPEN_FAILED = text("wand.selection.open_failed", "&cThe portal could not be opened here.");

    public static final TextKey DOOR_CRAFT_ONE = text("door.craft.one_at_a_time", "&8[&6Wormholes&8] &7Craft dimensional doors one at a time so each receives a unique identity.");
    public static final TextKey DOOR_PAIR_UNPACK_FAILED = text("door.pair.unpack_failed", "&8[&6Wormholes&8] &7The door pair could not be unpacked; the kit was not consumed.");
    public static final TextKey DOOR_PAIR_UNPACKED = text("door.pair.unpacked", "&8[&6Wormholes&8] &7The entangled pair separated into linked Wormhole Doors A and B.");
    public static final TextKey DOOR_LEGACY_COMBINE = text("door.legacy.combine", "&8[&6Wormholes&8] &7Combine this legacy dimensional door with a wooden door before placing it.");
    public static final TextKey DOOR_PAIR_MISSING = text("door.pair.missing", "&8[&6Wormholes&8] &7That paired door has no registered partner identity.");
    public static final TextKey DOOR_ALREADY_PLACED = text("door.place.already_placed", "&8[&6Wormholes&8] &7That dimensional door is already placed, or its state could not be saved.");
    public static final TextKey DOOR_EXIT_ANCHORED = text("door.break.exit_anchored", "&8[&6Wormholes&8] &7The dimensional exit is anchored to this pocket.");
    public static final TextKey DOOR_BREAK_FIRST = text("door.break.support", "&8[&6Wormholes&8] &7Break the dimensional door before removing its support block.");
    public static final TextKey DOOR_DISABLE_WARNING = text("door.disable.warning", "&8[&6Wormholes&8] &7Dimensional Doors are being disabled. Leave through the pocket return door now.");
    public static final TextKey DOOR_RESCUE_CANCELLED = text("door.rescue.cancelled", "&8[&6Wormholes&8] &7Your emergency ejection was cancelled; the route was kept.");
    public static final TextKey DOOR_TRANSIT_MESSAGE = text("door.transit.message", "&8[&6Wormholes&8] &7{message}");
    public static final TextKey DOOR_LINK_NOT_PLACED = text("door.transit.link_not_placed", "The linked Wormhole Door has not been placed yet.");
    public static final TextKey DOOR_LINK_UNAVAILABLE = text("door.transit.link_unavailable", "The linked Wormhole Door is unavailable or obstructed.");
    public static final TextKey DOOR_NESTED_POCKET = text("door.transit.nested_pocket", "A pocket door cannot open another pocket from inside the shared void dimension.");
    public static final TextKey DOOR_POCKET_NOT_READY = text("door.transit.pocket_not_ready", "The pocket dimension is not ready.");
    public static final TextKey DOOR_POCKET_ALLOCATION_FAILED = text("door.transit.pocket_allocation_failed", "The pocket dimension could not be allocated.");
    public static final TextKey DOOR_SAFE_RETURN_NOT_FOUND = text("door.transit.safe_return_not_found", "A safe return route could not be found on this side of the door.");
    public static final TextKey DOOR_POCKET_PREPARE_FAILED = text("door.transit.pocket_prepare_failed", "The pocket could not be prepared safely.");
    public static final TextKey DOOR_RETURN_TICKET_SAVE_FAILED = text("door.transit.return_ticket_save_failed", "A safe return route could not be saved.");
    public static final TextKey DOOR_POCKET_ENTRY_UNSAFE = text("door.transit.pocket_entry_unsafe", "The pocket entry is not safe.");
    public static final TextKey DOOR_POCKET_ENTRY_CHUNK_FAILED = text("door.transit.pocket_entry_chunk_failed", "The pocket dimension could not load its entry chunk.");
    public static final TextKey DOOR_NO_RETURN_ROUTE = text("door.transit.no_return_route", "You do not have a return route stored for this pocket.");
    public static final TextKey DOOR_RETURN_UNAVAILABLE = text("door.transit.return_unavailable", "Your return door is unavailable or obstructed on its corresponding face.");
    public static final TextKey DOOR_RETURN_WORLD_UNLOADED = text("door.transit.return_world_unloaded", "Your return world is not loaded.");
    public static final TextKey DOOR_RETURN_POINT_OBSTRUCTED = text("door.transit.return_point_obstructed", "Your saved return point is obstructed.");
    public static final TextKey DOOR_RETURN_CHUNK_FAILED = text("door.transit.return_chunk_failed", "Your saved return chunk could not be loaded.");
    public static final TextKey DOOR_CLOSED_DURING_TRANSIT = text("door.transit.closed", "The dimensional door closed before transit completed.");
    public static final TextKey DOOR_CYCLE_CONSUMED = text("door.transit.cycle_consumed", "This door's open cycle was already used. Close and reopen the door.");
    public static final TextKey DOOR_SOURCE_MISSING = text("door.transit.source_missing", "The dimensional door is no longer there.");
    public static final TextKey DOOR_SOURCE_CLOSE_FAILED = text("door.transit.source_close_failed", "The source door could not close safely.");
    public static final TextKey DOOR_SOURCE_REGION_UNAVAILABLE = text("door.transit.source_region_unavailable", "The source door region is unavailable.");
    public static final TextKey DOOR_TRANSIT_START_FAILED = text("door.transit.start_failed", "The dimensional transit could not start.");
    public static final TextKey DOOR_TRANSIT_CANCELLED = text("door.transit.cancelled", "The dimensional transit was cancelled.");
    public static final TextKey DOOR_RESCUE_NO_ROUTE = text("door.rescue.reason.no_route", "The pocket has no saved return route.");
    public static final TextKey DOOR_RESCUE_RETURN_WORLD_UNAVAILABLE = text("door.rescue.reason.return_world_unavailable", "The saved return world is unavailable.");
    public static final TextKey DOOR_RESCUE_RETURN_POINT_OBSTRUCTED = text("door.rescue.reason.return_point_obstructed", "The saved return point is obstructed.");
    public static final TextKey DOOR_RESCUE_RETURN_CHUNK_FAILED = text("door.rescue.reason.return_chunk_failed", "The saved return chunk could not be loaded.");
    public static final TextKey DOOR_RESCUE_NO_FALLBACK_WORLD = text("door.rescue.fallback.no_world", "No non-pocket fallback world is loaded.");
    public static final TextKey DOOR_RESCUE_FALLBACK_OBSTRUCTED = text("door.rescue.fallback.obstructed", "The fallback spawn is obstructed.");
    public static final TextKey DOOR_RESCUE_FALLBACK_CHUNK_FAILED = text("door.rescue.fallback.chunk_failed", "The fallback spawn could not be loaded.");
    public static final TextKey DOOR_RESCUE_FALLBACK_SCHEDULE_FAILED = text("door.rescue.fallback.schedule_failed", "The fallback ejection could not be scheduled.");
    public static final TextKey DOOR_RESCUE_START_FAILED = text("door.rescue.reason.start_failed", "The emergency ejection could not start.");
    public static final TextKey DOOR_RESCUE_FAILED = text("door.rescue.failed", "&8[&6Wormholes&8] &7{route} {fallback} You remain protected at one heart.");

    public static final TextKey DOOR_ACCESS_LABEL_NEUTRAL = text("door.access.label.neutral", "Neutral");
    public static final TextKey DOOR_ACCESS_LABEL_WHITELIST = text("door.access.label.whitelist", "Whitelist");
    public static final TextKey DOOR_ACCESS_LABEL_BLACKLIST = text("door.access.label.blacklist", "Blacklist");
    public static final TextKey DOOR_OPEN_STATE_OPEN = text("door.open_state.open", "Open");
    public static final TextKey DOOR_OPEN_STATE_CLOSED = text("door.open_state.closed", "Closed");
    public static final TextKey DOOR_ACCESS_KIND_PAIR = text("door.access.kind.pair", "Entangled");
    public static final TextKey DOOR_ACCESS_KIND_PERSONAL = text("door.access.kind.personal", "Personal");
    public static final TextKey DOOR_ACCESS_KIND_PUBLIC = text("door.access.kind.public", "Public");
    public static final TextKey DOOR_ACCESS_KIND_RETURN = text("door.access.kind.return", "Exit");
    public static final TextKey DOOR_ACCESS_UNKNOWN_PLAYER = text("door.access.unknown_player", "Unknown ({id})");
    public static final TextKey DOOR_ACCESS_EDIT_DENIED = text("door.access.edit_denied", "&cOnly the door owner or an administrator can edit this door.");
    public static final TextKey DOOR_ACCESS_UNAVAILABLE = text("door.access.unavailable", "&cThis dimensional door has no access record.");
    public static final TextKey DOOR_ACCESS_SAVE_FAILED = text("door.access.save_failed", "&cThat door access change could not be saved.");
    public static final TextKey DOOR_ACCESS_PLAYER_NOT_FOUND = text("door.access.player_not_found", "&cNo player named {name} could be found.");
    public static final TextKey DOOR_ACCESS_OWNER_ALWAYS = text("door.access.owner_always", "&7The door owner always has access.");
    public static final TextKey DOOR_ACCESS_ALREADY_LISTED = text("door.access.already_listed", "&7{name} is already listed for this door.");
    public static final TextKey DOOR_ACCESS_ADDED = text("door.access.added", "&a{name} was added to this door's list.");
    public static final TextKey DOOR_ACCESS_REMOVED = text("door.access.removed", "&a{name} was removed from this door's list.");
    public static final TextKey DOOR_ACCESS_STATE_CHANGED = text("door.access.state_changed", "&a{name} is now {state} for this door.");
    public static final TextKey DOOR_ACCESS_PROMPT_PLAYER = text("door.access.prompt_player", "&bType the player name to list for this door (or '{cancel}'):");
    public static final TextKey DOOR_ACCESS_DENIED = text("door.access.denied", "&cThis dimensional door refuses you.");
    public static final TextKey DOOR_ACCESS_TRANSIT_DENIED = text("door.access.transit_denied", "You do not have access to this dimensional door.");

    public static final TextKey DOOR_MENU_ACCESS_TITLE = text("door.menu.access.title", "Door Access: {kind}");
    public static final LinesKey DOOR_MENU_ACCESS_PLACARD = lines("door.menu.access.placard",
            "&6&l{kind} Dimensional Door&r",
            "&7Owner: &f{owner}",
            "&7Whitelisted: &a{whitelisted}",
            "&7Blacklisted: &c{blacklisted}",
            "&7Listed players: &b{count}",
            "&8Owners and administrators edit this door.");
    public static final LinesKey DOOR_MENU_ACCESS_ADD_PLAYER = lines("door.menu.access.add_player",
            "&a&lAdd Player&r",
            "&7Type a player name in chat to list them.",
            "",
            "&8Click to add a player.");
    public static final LinesKey DOOR_MENU_ACCESS_OPEN_STATE = lines("door.menu.access.open_state",
            "&b&lOpenState: &f&l{state}",
            "&7The portal follows this block's physical state.",
            "&7Active state: &f{state}&7.",
            "",
            "&8Next: {next}. Click to switch.");
    public static final LinesKey DOOR_MENU_ACCESS_ENTRY = lines("door.menu.access.entry",
            "&f{name}",
            "&7State: &b{state}",
            "",
            "&8Left click: whitelist. Right click: blacklist.",
            "&8Middle click or shift + left click: remove.");

    public static final LinesKey PORTAL_MENU_DESTINATION = lines("portal.menu.destination",
            "&6&lDestination&r",
            "&7Choose a portal to link to.",
            "&7Currently linked: {destination}",
            "",
            "&8Click to open the destination picker.");
    public static final LinesKey PORTAL_MENU_GATEWAY_DESTINATION = lines("portal.menu.gateway_destination",
            "&6&lPair & Destination&r",
            "&7Pair another server or choose a gateway.",
            "&7Currently linked: {destination}",
            "",
            "&8Click to open the pairing hub.");
    public static final LinesKey PORTAL_MENU_RTP_DESTINATION = lines("portal.menu.rtp_destination",
            "&6&lRandom Destination&r",
            "&7Configure where and when this portal rerolls.",
            "&7Rotation: &b{rotation}",
            "",
            "&8Click to open random teleport settings.");
    public static final LinesKey PORTAL_MENU_RENAME = lines("portal.menu.rename",
            "&a&lRename Portal&r",
            "&7Change the name shown on links and menus.",
            "&7Current: &f{portal}",
            "",
            "&8Click to type a new name.");
    public static final LinesKey PORTAL_MENU_DELETE = lines("portal.menu.delete",
            "&c&lDelete Portal&r",
            "&7Permanently removes this portal.",
            "",
            "&c&nShift + Left Click to confirm&r");
    public static final LinesKey PORTAL_MENU_ADVANCED_SETTINGS = lines("portal.menu.advanced_settings",
            "&3&lAdvanced Stream Tuning&r",
            "&7Direct controls for diagnostics and unusual links.",
            "&7Changing one value marks Stream Quality as Custom.");
    public static final LinesKey PORTAL_MENU_BACK_SETTINGS = lines("portal.menu.back_settings",
            "&e&lBack to Settings&r",
            "&7Return to portal settings.");
    public static final LinesKey PORTAL_MENU_TRAVEL_MANAGED = lines("portal.menu.travel.managed",
            "&6&lTravel: {direction}&r",
            "&7Managed dimensional portal direction.",
            "&7{detail}");
    public static final LinesKey PORTAL_MENU_TRAVEL_MIRROR = lines("portal.menu.travel.mirror",
            "&c&lTravel: Mirror Locked&r",
            "&7Mirror mode is visual only.",
            "&7Entities cannot enter or leave through it.");
    public static final LinesKey PORTAL_MENU_TRAVEL = lines("portal.menu.travel.standard",
            "&b&lTravel: {mode}&r",
            "&7Controls travel through this portal.",
            "",
            "&7Outgoing: &b{outgoing}&7  Incoming: &b{incoming}",
            "",
            "&8Click to cycle travel direction.");
    public static final LinesKey PORTAL_MENU_STREAM_QUALITY = lines("portal.menu.stream_quality",
            "&b&lStream Quality: {quality}&r",
            "&7One control for remote view range and cadence.",
            "",
            "&7Depth &b{depth}&7  Entities &b{entities}t",
            "&7Refresh &b{refresh}t&7  Grace &b{grace}s",
            "",
            "&8Click: cycle quality.",
            "&8Shift-click: advanced tuning.");
    public static final LinesKey PORTAL_MENU_ORIENTATION = lines("portal.menu.orientation",
            "&9&lOrientation&r",
            "&7Facing: &9{facing}",
            "&7Up: &6{up}",
            "",
            "&8Click for facing, flip, and rotation.");
    public static final LinesKey PORTAL_MENU_ORIENTATION_PLACARD = lines("portal.menu.orientation_placard",
            "&9&lPortal Orientation&r",
            "&7Facing: &9{facing}",
            "&7Screen up: &6{up}");
    public static final LinesKey PORTAL_MENU_GATEWAY_CHOOSE = lines("portal.menu.gateway.choose",
            "&6&lChoose Destination&r",
            "&7Choose from discovered local and remote gateways.",
            "",
            "&8Click to open the destination list.");
    public static final LinesKey PORTAL_MENU_GATEWAY_UNPAIRED = lines("portal.menu.gateway.unpaired",
            "&6&lGateway Pairing&r",
            "&7Status: &cNot paired");
    public static final LinesKey PORTAL_MENU_GATEWAY_PAIRED = lines("portal.menu.gateway.paired",
            "&6&lGateway Pairing&r",
            "&7Destination: &6{destination}");
    public static final TextKey PORTAL_MENU_GATEWAY_SERVER = text("portal.menu.gateway.server", "&7Server: &f{server}");
    public static final TextKey PORTAL_MENU_GATEWAY_LINK = text("portal.menu.gateway.link", "&7Link: &b{state}");
    public static final LinesKey PORTAL_MENU_GATEWAY_EXPORT = lines("portal.menu.gateway.export",
            "&6&lCreate Invite&r",
            "&7Create a signed gateway code for another server.",
            "",
            "&8Click to copy a fresh code.");
    public static final LinesKey PORTAL_MENU_GATEWAY_IMPORT = lines("portal.menu.gateway.import",
            "&b&lUse Invite&r",
            "&7Paste a signed code from another gateway.",
            "",
            "&8Click, then paste the code in chat.");
    public static final LinesKey PORTAL_MENU_NETWORK_NUMBER = lines("portal.menu.network_number",
            "&b&l{label} &f&l{value}&r",
            "&7{description}",
            "",
            "&7Currently: &b{value}",
            "",
            "&8Left click: +{step}",
            "&8Right click: -{step}",
            "&8Shift-left: +{large_step}",
            "&8Shift-right: -{large_step}");
    public static final LinesKey PORTAL_MENU_FALLBACK_BLOCK = lines("portal.menu.fallback_block",
            "&b&lFallback Block&r",
            "&7Block shown beyond streamed depth.",
            "",
            "&7Currently: &f{block}",
            "",
            "&8Left: enter a block state.",
            "&8Right: reset to air.");
    public static final LinesKey PORTAL_MENU_BLACKOUT = lines("portal.menu.blackout",
            "&b&lBlackout Background&r",
            "&7Seal the projection's far and side boundaries with solid concrete so the local world never shows through.",
            "",
            "&7Currently: &f{state} &8({color})",
            "",
            "&8Left: toggle on/off.",
            "&8Right: choose a color.");
    public static final LinesKey PORTAL_MENU_BLACKOUT_COLOR_PLACARD = lines("portal.menu.blackout_color.placard",
            "&6&lBlackout Color&r",
            "&7Concrete color used for the blackout shell.",
            "",
            "&7Currently: &f{color}");
    public static final LinesKey PORTAL_MENU_BLACKOUT_COLOR_OPTION = lines("portal.menu.blackout_color.option",
            "&b&l{color}&r",
            "",
            "&8Click to select.");
    public static final LinesKey PORTAL_MENU_ACTIVATION_RANGE = lines("portal.menu.activation_range",
            "&b&lActivation Range &f&l{value}&r",
            "&7Distance in blocks at which this portal starts projecting for nearby players.",
            "",
            "&7Currently: &b{value}",
            "",
            "&8Left click: +{step}",
            "&8Right click: -{step}",
            "&8Shift-left: +{large_step}",
            "&8Shift-right: -{large_step}",
            "&8Below 8 resets to Global.");
    public static final LinesKey PORTAL_MENU_RENDER_MODE = lines("portal.menu.render_mode",
            "&b&lRender Mode &f&l{mode}&r",
            "&7How much of the destination volume this portal projects.",
            "",
            "&7Mode: &f{mode}",
            "",
            "&8PanOptic renders the full destination volume.",
            "&8Venticular keeps visible surfaces and omits hidden destination geometry.",
            "",
            "&8Left: cycle mode.");
    public static final LinesKey PORTAL_MENU_AMBIENT_PARTICLES = lines("portal.menu.ambient_particles",
            "&b&lAmbient Particles&r",
            "&7Decorative dust that drifts around this portal.",
            "",
            "&7Style: &f{style} &8({color})",
            "",
            "&8Left: cycle style.",
            "&8Right: choose a color.");
    public static final LinesKey PORTAL_MENU_AMBIENT_COLOR_PLACARD = lines("portal.menu.ambient_color.placard",
            "&6&lAmbient Color&r",
            "&7Color of the ambient dust particles.",
            "",
            "&7Currently: &f{color}");
    public static final LinesKey PORTAL_MENU_AMBIENT_CHANNEL = lines("portal.menu.ambient_color.channel",
            "&b&l{label} &f&l{value}&r",
            "",
            "&8Left click: +{step}",
            "&8Right click: -{step}",
            "&8Shift-left: +{large_step}",
            "&8Shift-right: -{large_step}");
    public static final LinesKey PORTAL_MENU_AMBIENT_COLOR_OPTION = lines("portal.menu.ambient_color.option",
            "&b&l{color}&r",
            "",
            "&8Click to select.");
    public static final LinesKey PORTAL_MENU_SURFACE_SKIN = lines("portal.menu.surface_skin",
            "&b&lSurface Skin&r",
            "&7Cover the portal aperture with a rendered block or fluid pane.",
            "",
            "&7Skin: &f{skin}",
            "",
            "&8Left: clear the skin.",
            "&8Right: open skin options.");
    public static final LinesKey PORTAL_MENU_SURFACE_SKIN_PLACARD = lines("portal.menu.surface_skin.placard",
            "&6&lSurface Skin&r",
            "&7Right-click the portal with a block or bucket to apply a skin.",
            "",
            "&7Skin: &f{skin}");
    public static final LinesKey PORTAL_MENU_SURFACE_SKIN_GLASS = lines("portal.menu.surface_skin.glass",
            "&b&lGlass Skin&r",
            "&7A clear glass window over the aperture.",
            "",
            "&8Click to apply glass.");
    public static final LinesKey PORTAL_MENU_SURFACE_SKIN_CLEAR = lines("portal.menu.surface_skin.clear",
            "&c&lClear Skin&r",
            "&7Remove the skin and restore projections.",
            "",
            "&8Click to remove the skin.");
    public static final LinesKey PORTAL_MENU_PLACARD_RTP = lines("portal.menu.placard.rtp",
            "&6&l{portal}&r",
            "&7Type: &e{type}",
            "&7Facing: &9{facing}",
            "&7Allocation: &b{allocation}",
            "&7Rotation: &b{rotation}",
            "",
            "&8Operators bypass white/blacklist.");
    public static final LinesKey PORTAL_MENU_PLACARD_LINKED = lines("portal.menu.placard.linked",
            "&6&l{portal}&r",
            "&7Type: &e{type}",
            "&7Facing: &9{facing}",
            "&7Linked to: &6{destination}",
            "",
            "&8Operators bypass white/blacklist.");
    public static final LinesKey PORTAL_MENU_PLACARD_UNLINKED = lines("portal.menu.placard.unlinked",
            "&6&l{portal}&r",
            "&7Type: &e{type}",
            "&7Facing: &9{facing}",
            "&7Linked to: &c{none}",
            "",
            "&8Operators bypass white/blacklist.");
    public static final LinesKey PORTAL_MENU_SETTINGS_PLACARD_GATEWAY = lines("portal.menu.settings_placard_gateway",
            "&b&lPortal Settings&r",
            "&7Access and transfer controls.",
            "&7Plus projection view tuning.",
            "",
            "&7Larger depth / shorter ticks =",
            "&7richer view, more bandwidth.");
    public static final LinesKey PORTAL_MENU_PUBLIC_LOOK_LABEL = lines("portal.menu.public_look_label",
            "&b&lPublic Look Label: &f&l{state}&r",
            "&7Show this portal's name when nearby players look at it.",
            "&7When Off, the route label remains portal-tool only.",
            "",
            "&eLeft click to toggle");
    public static final LinesKey PORTAL_MENU_COST_OPENER = lines("portal.menu.cost.opener",
            "&6&lTravel Cost&r",
            "&7Require an exact item or Vault currency",
            "&7when a player uses this portal.",
            "",
            "&7Mode: &f{mode}",
            "&7Cost: &f{cost}",
            "",
            "&8Click to configure.");
    public static final LinesKey PORTAL_MENU_COST_PLACARD = lines("portal.menu.cost.placard",
            "&6&lTravel Cost&r",
            "&7Players must pay before this portal",
            "&7allows them to travel.",
            "",
            "&7Mode: &f{mode}",
            "&7Cost: &f{cost}");
    public static final LinesKey PORTAL_MENU_COST_MODE_FREE = lines("portal.menu.cost.mode_free",
            "&a&lFree&r",
            "&7No payment is required.",
            "",
            "&8Click to make travel free.");
    public static final LinesKey PORTAL_MENU_COST_MODE_VANILLA = lines("portal.menu.cost.mode_vanilla",
            "&b&lVanilla Item&r",
            "&7Consume an exact matching item.",
            "",
            "&8Click, hold the item, then press Drop.");
    public static final LinesKey PORTAL_MENU_COST_MODE_VAULT = lines("portal.menu.cost.mode_vault",
            "&a&lVault Economy&r",
            "&7Withdraw currency through Vault.",
            "",
            "&8Click to enter an amount.");
    public static final LinesKey PORTAL_MENU_COST_MODE_VAULT_UNAVAILABLE = lines("portal.menu.cost.mode_vault_unavailable",
            "&c&lVault Economy Unavailable&r",
            "&7Vault and an economy provider are required.",
            "",
            "&8Install or enable both to use this mode.");
    public static final LinesKey PORTAL_MENU_COST_FREE_DETAIL = lines("portal.menu.cost.free_detail",
            "&a&lNo Cost&r",
            "&7Travel through this portal is free.");
    public static final LinesKey PORTAL_MENU_COST_SECONDARY_EMPTY = lines("portal.menu.cost.secondary_empty",
            "&8&lNo Additional Setting&r");
    public static final LinesKey PORTAL_MENU_COST_ITEM = lines("portal.menu.cost.item",
            "&b&l{item}&r",
            "&7This exact item is required for travel.",
            "&7The selection item is never consumed.",
            "",
            "&8Left: select another held item.",
            "&8Right: clear the cost.");
    public static final LinesKey PORTAL_MENU_COST_QUANTITY = lines("portal.menu.cost.quantity",
            "&b&lQuantity {quantity}&r",
            "&7How many exact matching items",
            "&7are consumed per traversal.",
            "",
            "&8Left click: +1",
            "&8Right click: -1",
            "&8Shift-left: +8",
            "&8Shift-right: -8",
            "&8Maximum: {maximum}");
    public static final LinesKey PORTAL_MENU_COST_VAULT_AMOUNT = lines("portal.menu.cost.vault_amount",
            "&a&l{amount}&r",
            "&7Currency withdrawn per traversal.",
            "",
            "&8Left: enter another amount.",
            "&8Right: clear the cost.");
    public static final LinesKey PORTAL_MENU_MODE_PLACARD = lines("portal.menu.mode_placard",
            "&e&lPortal Mode&r",
            "&7Current: &e{mode}",
            "",
            "&7Portal: basic linked portal.",
            "&7Wormhole: portal with viewport.",
            "&7Gateway: cross-server gateway.",
            "&7RTP: configurable random destinations.",
            "&7Mirror: reflect this side, no travel.");
    public static final LinesKey PORTAL_MENU_MODE_OPENER = lines("portal.menu.mode_opener",
            "&e&lMode&r",
            "&7{description}",
            "",
            "&7Currently: &e{mode}",
            "",
            "&8Click to change mode.");
    public static final LinesKey PORTAL_MENU_DIRECTION = lines("portal.menu.direction",
            "&9&lDirection&r",
            "&7Change the portal facing direction.",
            "",
            "&7Currently facing: &9{direction}",
            "",
            "&8Click then look, left click to apply.");
    public static final LinesKey PORTAL_MENU_FLIP_FACE = lines("portal.menu.flip_face",
            "&b&lFlip Face&r",
            "&7Reverse the portal face direction.",
            "&7Screen rotation stays aligned.",
            "",
            "&7Currently rolling up: &b{up}",
            "",
            "&8Click to flip.");
    public static final LinesKey PORTAL_MENU_ROTATE_COUNTERCLOCKWISE = lines("portal.menu.rotate_counterclockwise",
            "&6&lRotate Counterclockwise&r",
            "&7Roll the portal viewport 90 degrees",
            "&7without changing the face.",
            "",
            "&7Currently rolling up: &6{up}",
            "",
            "&8Click to rotate.");
    public static final LinesKey PORTAL_MENU_ROTATE_CLOCKWISE = lines("portal.menu.rotate_clockwise",
            "&6&lRotate Clockwise&r",
            "&7Roll the portal viewport 90 degrees",
            "&7without changing the face.",
            "",
            "&7Currently rolling up: &6{up}",
            "",
            "&8Click to rotate.");
    public static final LinesKey PORTAL_MENU_BACK = lines("portal.menu.back", "&e&lBack&r", "&7Return to the portal menu.");
    public static final LinesKey PORTAL_MENU_PROJECTION_ON = lines("portal.menu.projection.on",
            "&6&lProjection On&r",
            "&7Show this portal's live view.",
            "&7Destination or mirror imagery is visible.",
            "",
            "&7Currently: &bProjection On&r",
            "",
            "&8Click to toggle On / Off.");
    public static final LinesKey PORTAL_MENU_PROJECTION_OFF = lines("portal.menu.projection.off",
            "&8&lProjection Off&r",
            "&7The frame stays empty.",
            "&7No destination view, no mirror.",
            "",
            "&7Currently: &bProjection Off&r",
            "",
            "&8Click to toggle On / Off.");
    public static final LinesKey PORTAL_MENU_SETTINGS_GATEWAY = lines("portal.menu.settings_gateway",
            "&b&lSettings&r",
            "&7Permissions, transfers,",
            "&7and projection view tuning.",
            "",
            "&7Access: &6{access}",
            "&7Send &b{send}&7  Receive &b{receive}",
            "&7Depth &b{depth}&7  Entity &b{entity}t",
            "",
            "&8Click to open settings.");
    public static final LinesKey PORTAL_MENU_SETTINGS_SYNC = lines("portal.menu.settings_sync",
            "&b&lSettings Sync {state}&r",
            "&7When On, this portal's settings",
            "&7(depth, ticks, projection, permissions,",
            "&7transfers) sync to all linked peers.",
            "",
            "&7Currently: &b{state}",
            "",
            "&8Click to toggle.");
    public static final LinesKey PORTAL_MENU_PERMISSION = lines("portal.menu.permission",
            "&6&lAccess {mode}&r",
            "&7{description}",
            "&7Node: &f{node}",
            "",
            "&7Currently: &6{mode}",
            "",
            "&8Click to toggle whitelist / blacklist.",
            "&8Operators always bypass.");
    public static final LinesKey PORTAL_MENU_MODE_OPTION_SELECTED = lines("portal.menu.mode_option.selected",
            "&e&l{mode}&r", "&7{description}", "", "&aCurrently Selected");
    public static final LinesKey PORTAL_MENU_MODE_OPTION_AVAILABLE = lines("portal.menu.mode_option.available",
            "&e&l{mode}&r", "&7{description}", "", "&7Click to select");
    public static final LinesKey PORTAL_MENU_MIRROR_SELECTED = lines("portal.menu.mirror.selected",
            "&e&lMirror&r",
            "&7Reflect the local world back.",
            "&7See yourself looking through the frame.",
            "&7No travel while mirrored.",
            "",
            "&aCurrently Selected");
    public static final LinesKey PORTAL_MENU_MIRROR_AVAILABLE = lines("portal.menu.mirror.available",
            "&e&lMirror&r",
            "&7Reflect the local world back.",
            "&7See yourself looking through the frame.",
            "&7No travel while mirrored.",
            "",
            "&7Left click to select");
    public static final TextKey PORTAL_MENU_MIRROR_ROTATION = text("portal.menu.mirror.rotation", "&7Image rotation: &b{degrees} degrees");
    public static final TextKey PORTAL_MENU_MIRROR_ROTATE_CLOCKWISE = text("portal.menu.mirror.rotate_clockwise", "&8Right click: rotate clockwise.");
    public static final TextKey PORTAL_MENU_MIRROR_ROTATE_COUNTERCLOCKWISE = text("portal.menu.mirror.rotate_counterclockwise", "&8Shift + right click: rotate counterclockwise.");
    public static final LinesKey PORTAL_MENU_MIRROR_FLIP = lines("portal.menu.mirror.flip",
            "&7Wall mirrors flip in 180 degree steps",
            "&7so reflected entities stay aligned.",
            "&8Right click: flip the reflected image.");
    public static final LinesKey PORTAL_MENU_LOCAL_DESTINATION = lines("portal.menu.destination.local",
            "&6{portal}",
            "&7at {x}, {y}, {z} in {world} Facing {direction}");
    public static final LinesKey PORTAL_MENU_REMOTE_DESTINATION = lines("portal.menu.destination.remote",
            "&6{portal}",
            "&7on server &f{server}",
            "&7at {x}, {y}, {z} in {world} Facing {direction}",
            "&b{state}");
    public static final LinesKey PORTAL_MENU_DESTINATION_SORT = lines("portal.menu.destination.sort",
            "&e&lSort: &f&l{mode}&r", "&7Change the destination ordering.", "&eLeft click");
    public static final TextKey PORTAL_MENU_DESTINATION_SORT_SMART = text("portal.menu.destination.sort_smart", "Smart");
    public static final TextKey PORTAL_MENU_DESTINATION_SORT_NAME = text("portal.menu.destination.sort_name", "Name");
    public static final TextKey PORTAL_MENU_DESTINATION_SORT_WORLD = text("portal.menu.destination.sort_world", "World");
    public static final TextKey PORTAL_MENU_DESTINATION_SORT_DISTANCE = text("portal.menu.destination.sort_distance", "Distance");
    public static final LinesKey PORTAL_MENU_DESTINATION_PREVIOUS = lines("portal.menu.destination.previous_page",
            "&e&lPrevious Page&r", "&7Show the previous destinations.", "&eLeft click");
    public static final LinesKey PORTAL_MENU_DESTINATION_NEXT = lines("portal.menu.destination.next_page",
            "&e&lNext Page&r", "&7Show the next destinations.", "&eLeft click");
    public static final LinesKey PORTAL_MENU_DESTINATION_PAGE = lines("portal.menu.destination.page",
            "&b&lPage {page}/{pages}&r", "&7{count} destinations");
    public static final LinesKey PORTAL_MENU_DESTINATION_EMPTY = lines("portal.menu.destination.empty",
            "&7No destinations are available to link.");
    public static final TextKey PORTAL_PROMPT_INVITE = text("portal.prompt.invite", "&bPaste the portal invite in chat (or '{cancel}'):");
    public static final TextKey PORTAL_PROMPT_BLOCK_STATE = text("portal.prompt.block_state", "&bEnter a block state for this portal's network view edge, or '{cancel}':");
    public static final TextKey PORTAL_PROMPT_NAME = text("portal.prompt.name", "&bType the new portal name in chat (or '{cancel}'):");
    public static final TextKey PORTAL_PROMPT_COST_ITEM = text("portal.prompt.cost_item", "&bHold the exact cost item in your main hand, then press Drop. The item will not leave your inventory.");
    public static final TextKey PORTAL_PROMPT_COST_VAULT = text("portal.prompt.cost_vault", "&bEnter the Vault currency amount per traversal (or '{cancel}'):");
    public static final TextKey PORTAL_INPUT_CANCEL = text("portal.input.cancel", "cancel");
    public static final TextKey PORTAL_RTP_EDITOR_TITLE = text("portal.rtp.editor_title", "RTP: {portal}");
    public static final TextKey PORTAL_DIMENSIONAL_LINK_MANAGED = text("portal.notice.dimensional_link_managed", "This dimensional portal keeps its generated link.");
    public static final TextKey PORTAL_NOT_RTP = text("portal.notice.not_rtp", "This portal is no longer in random teleport mode.");
    public static final TextKey PORTAL_RTP_SETTING_REJECTED = text("portal.rtp.notice.setting_rejected", "Could not apply that setting: {reason}");
    public static final TextKey PORTAL_REGION_UNAVAILABLE = text("portal.notice.region_unavailable", "The portal region is unavailable; try again.");
    public static final TextKey PORTAL_RTP_APPLIED = text("portal.rtp.notice.applied", "Random destination settings applied.");
    public static final TextKey PORTAL_RTP_RESET_DEFAULTS = text("portal.rtp.notice.reset_defaults", "Random teleport settings reset to defaults.");
    public static final TextKey PORTAL_RTP_EDITOR_REFRESHED = text("portal.rtp.notice.editor_refreshed", "Settings changed; the editor was refreshed.");
    public static final TextKey PORTAL_RTP_RUNTIME_UNAVAILABLE = text("portal.rtp.notice.runtime_unavailable", "Random teleport runtime is unavailable.");
    public static final TextKey PORTAL_RTP_REROLL_FAILED = text("portal.rtp.notice.reroll_failed", "Manual reroll failed; see the server log.");
    public static final TextKey PORTAL_RTP_REROLL_PREPARING = text("portal.rtp.notice.reroll_preparing", "Preparing a new random destination.");
    public static final TextKey PORTAL_RTP_REROLL_UNAVAILABLE = text("portal.rtp.notice.reroll_unavailable", "Reroll is unavailable while the route is preparing or in use.");
    public static final TextKey PORTAL_RTP_POOL_REBUILDING = text("portal.rtp.notice.pool_rebuilding", "Rebuilding the private destination pool.");
    public static final TextKey PORTAL_RTP_POOL_FAILED = text("portal.rtp.notice.pool_failed", "Pool rebuild failed; see the server log.");
    public static final TextKey PORTAL_RTP_NOT_READY = text("portal.rtp.notice.not_ready", "&eThe portal is still stabilizing its destination.");
    public static final TextKey PORTAL_RTP_TRAVERSAL_FAILED = text("portal.rtp.notice.traversal_failed", "&cThe portal could not stabilize; try again.");
    public static final TextKey PORTAL_TRAVEL_MANAGED = text("portal.notice.travel_managed", "Dimensional portal travel is managed automatically.");
    public static final TextKey PORTAL_TRAVEL_MIRROR_LOCKED = text("portal.notice.travel_mirror_locked", "Mirror mode never allows travel.");
    public static final TextKey PORTAL_TRAVEL_CHANGED = text("portal.notice.travel_changed", "Travel {mode}");
    public static final TextKey PORTAL_STREAM_QUALITY_CHANGED = text("portal.notice.stream_quality_changed", "Stream Quality {quality}");
    public static final TextKey PORTAL_NETWORK_VALUE_CHANGED = text("portal.notice.network_value_changed", "{label} {value}");
    public static final TextKey PORTAL_FALLBACK_SET = text("portal.notice.fallback_set", "Fallback set to {block}");
    public static final TextKey PORTAL_FALLBACK_RESET = text("portal.notice.fallback_reset", "Fallback reset to {block}");
    public static final TextKey PORTAL_MANAGED_MODE = text("portal.notice.managed_mode", "Managed dimensional portals stay in portal mode.");
    public static final TextKey PORTAL_PROJECTION_RECEIVER_INACTIVE = text("portal.notice.projection_receiver_inactive", "The End arrival stays visually inactive.");
    public static final TextKey PORTAL_PROJECTION_CHANGED = text("portal.notice.projection_changed", "Projections: {mode}");
    public static final TextKey PORTAL_SETTINGS_SYNC_CHANGED = text("portal.notice.settings_sync_changed", "Settings Sync {state}");
    public static final TextKey PORTAL_ACCESS_CHANGED = text("portal.notice.access_changed", "Access {mode}");
    public static final TextKey PORTAL_COST_ITEM_SET = text("portal.notice.cost_item_set", "Travel now requires {item}.");
    public static final TextKey PORTAL_COST_ITEM_INVALID = text("portal.notice.cost_item_invalid", "That item could not be saved as a travel cost.");
    public static final TextKey PORTAL_COST_CLEARED = text("portal.notice.cost_cleared", "Travel cost cleared.");
    public static final TextKey PORTAL_COST_QUANTITY_CHANGED = text("portal.notice.cost_quantity_changed", "Travel cost quantity: {quantity}");
    public static final TextKey PORTAL_COST_VAULT_CHANGED = text("portal.notice.cost_vault_changed", "Vault travel cost: {amount}");
    public static final TextKey PORTAL_COST_VAULT_INVALID = text("portal.notice.cost_vault_invalid", "Enter a positive currency amount no greater than {maximum}.");
    public static final TextKey PORTAL_COST_VAULT_UNAVAILABLE_NOTICE = text("portal.notice.cost_vault_unavailable", "Vault and an economy provider must be available before selecting this cost.");
    public static final TextKey PORTAL_MIRROR_SELECT_FIRST = text("portal.notice.mirror_select_first", "Choose Mirror before rotating the image.");
    public static final TextKey PORTAL_MIRROR_ROTATION_CHANGED = text("portal.notice.mirror_rotation_changed", "Mirror Rotation {degrees} degrees");
    public static final TextKey PORTAL_RTP_CANNOT_LINK = text("portal.notice.rtp_cannot_link", "Random teleport portals do not link to destinations.");
    public static final TextKey PORTAL_LABEL_BOTH_WAYS = text("portal.label.travel.both", "Both Ways");
    public static final TextKey PORTAL_LABEL_OUTBOUND_ONLY = text("portal.label.travel.outbound", "Outbound Only");
    public static final TextKey PORTAL_LABEL_INBOUND_ONLY = text("portal.label.travel.inbound", "Inbound Only");
    public static final TextKey PORTAL_LABEL_LOCKED = text("portal.label.travel.locked", "Locked");
    public static final TextKey PORTAL_LABEL_ARRIVAL_ONLY = text("portal.label.travel.arrival", "Arrival Only");
    public static final TextKey PORTAL_LABEL_DEPARTURE_ONLY = text("portal.label.travel.departure", "Departure Only");
    public static final TextKey PORTAL_LABEL_COST_FREE = text("portal.label.cost.free", "Free");
    public static final TextKey PORTAL_LABEL_COST_VANILLA = text("portal.label.cost.vanilla", "Vanilla Item");
    public static final TextKey PORTAL_LABEL_COST_VAULT = text("portal.label.cost.vault", "Vault Economy");
    public static final TextKey PORTAL_LABEL_DIMENSIONAL_BOTH_ACTIVE = text("portal.label.travel.dimensional_both", "Both linked halves stay active.");
    public static final TextKey PORTAL_LABEL_DIMENSIONAL_RETURN_DISABLED = text("portal.label.travel.dimensional_return_disabled", "The return path stays disabled.");
    public static final TextKey PORTAL_LABEL_STANDARD = text("portal.label.quality.standard", "Standard");
    public static final TextKey PORTAL_LABEL_PERFORMANCE = text("portal.label.quality.performance", "Performance");
    public static final TextKey PORTAL_LABEL_BALANCED = text("portal.label.quality.balanced", "Balanced");
    public static final TextKey PORTAL_LABEL_CINEMATIC = text("portal.label.quality.cinematic", "Cinematic");
    public static final TextKey PORTAL_LABEL_CUSTOM = text("portal.label.quality.custom", "Custom");
    public static final TextKey PORTAL_LABEL_PORTAL = text("portal.label.mode.portal", "Portal");
    public static final TextKey PORTAL_LABEL_WORMHOLE = text("portal.label.mode.wormhole", "Wormhole");
    public static final TextKey PORTAL_LABEL_GATEWAY = text("portal.label.mode.gateway", "Gateway");
    public static final TextKey PORTAL_LABEL_RTP = text("portal.label.mode.rtp", "RTP");
    public static final TextKey PORTAL_LABEL_MIRROR = text("portal.label.mode.mirror", "Mirror");
    public static final TextKey PORTAL_LABEL_DIRECTION_UP = text("portal.label.direction.up", "Up");
    public static final TextKey PORTAL_LABEL_DIRECTION_DOWN = text("portal.label.direction.down", "Down");
    public static final TextKey PORTAL_LABEL_DIRECTION_NORTH = text("portal.label.direction.north", "North");
    public static final TextKey PORTAL_LABEL_DIRECTION_SOUTH = text("portal.label.direction.south", "South");
    public static final TextKey PORTAL_LABEL_DIRECTION_EAST = text("portal.label.direction.east", "East");
    public static final TextKey PORTAL_LABEL_DIRECTION_WEST = text("portal.label.direction.west", "West");
    public static final TextKey PORTAL_MODE_DESCRIPTION_PORTAL = text("portal.mode.description.portal", "Basic linkable portal.");
    public static final TextKey PORTAL_MODE_DESCRIPTION_WORMHOLE = text("portal.mode.description.wormhole", "Linkable portal with viewport projection.");
    public static final TextKey PORTAL_MODE_DESCRIPTION_GATEWAY = text("portal.mode.description.gateway", "Reserved for cross-network linking.");
    public static final TextKey PORTAL_MODE_DESCRIPTION_RTP = text("portal.mode.description.rtp", "Local random teleport portal.");
    public static final TextKey PORTAL_MODE_DESCRIPTION_MIRROR = text("portal.mode.description.mirror", "Reflect the local world back with travel locked.");
    public static final TextKey PORTAL_LABEL_SHARED = text("portal.label.allocation.shared", "Shared");
    public static final TextKey PORTAL_LABEL_PER_PLAYER = text("portal.label.allocation.per_player", "Per-player");
    public static final TextKey PORTAL_RTP_ROTATION_PRIVATE = text("portal.rtp.rotation.private", "Private every {duration}");
    public static final TextKey PORTAL_LABEL_WHITELIST = text("portal.label.permission.whitelist", "Whitelist");
    public static final TextKey PORTAL_LABEL_BLACKLIST = text("portal.label.permission.blacklist", "Blacklist");
    public static final TextKey PORTAL_PERMISSION_DESCRIPTION_WHITELIST = text("portal.permission.description.whitelist", "Players need the node to use this portal.");
    public static final TextKey PORTAL_PERMISSION_DESCRIPTION_BLACKLIST = text("portal.permission.description.blacklist", "Players with the node are blocked.");
    public static final TextKey PORTAL_LABEL_DIRECT = text("portal.label.network.direct", "Direct");
    public static final TextKey PORTAL_LABEL_SIDEBAND = text("portal.label.network.sideband", "Sideband");
    public static final TextKey PORTAL_LABEL_RECONNECTING = text("portal.label.network.reconnecting", "Reconnecting");
    public static final TextKey PORTAL_NETWORK_LABEL_CAPTURE_RADIUS = text("portal.network.label.capture_radius", "Capture Radius");
    public static final TextKey PORTAL_NETWORK_DESCRIPTION_CAPTURE_RADIUS = text("portal.network.description.capture_radius", "Blocks streamed around the destination portal.");
    public static final TextKey PORTAL_NETWORK_LABEL_FULL_REFRESH = text("portal.network.label.full_refresh", "Full Refresh");
    public static final TextKey PORTAL_NETWORK_DESCRIPTION_FULL_REFRESH = text("portal.network.description.full_refresh", "Ticks between full block refreshes.");
    public static final TextKey PORTAL_NETWORK_LABEL_ENTITY_UPDATE = text("portal.network.label.entity_update", "Entity Update");
    public static final TextKey PORTAL_NETWORK_DESCRIPTION_ENTITY_UPDATE = text("portal.network.description.entity_update", "Ticks between entity updates.");
    public static final TextKey PORTAL_NETWORK_LABEL_VIEW_GRACE = text("portal.network.label.view_grace", "View Grace");
    public static final TextKey PORTAL_NETWORK_DESCRIPTION_VIEW_GRACE = text("portal.network.description.view_grace", "Seconds to keep a warm stream after looking away.");
    public static final TextKey PORTAL_NETWORK_LABEL_BLACKOUT = text("portal.network.label.blackout", "Blackout Background");
    public static final TextKey PORTAL_NETWORK_LABEL_BLACKOUT_COLOR = text("portal.network.label.blackout_color", "Blackout Color");
    public static final TextKey PORTAL_NETWORK_LABEL_ACTIVATION_RANGE = text("portal.network.label.activation_range", "Activation Range");
    public static final TextKey PORTAL_NETWORK_LABEL_RENDER_MODE = text("portal.network.label.render_mode", "Render Mode");
    public static final TextKey PORTAL_LABEL_PUBLIC_LOOK_LABEL = text("portal.label.public_look_label", "Public Look Label");
    public static final TextKey PORTAL_LABEL_ACTIVATION_GLOBAL = text("portal.label.activation_global", "Global ({range})");
    public static final TextKey PORTAL_NETWORK_LABEL_AMBIENT_STYLE = text("portal.network.label.ambient_style", "Ambient Particles");
    public static final TextKey PORTAL_NETWORK_LABEL_AMBIENT_COLOR = text("portal.network.label.ambient_color", "Ambient Color");
    public static final TextKey PORTAL_NETWORK_LABEL_SURFACE_SKIN = text("portal.network.label.surface_skin", "Surface Skin");
    public static final TextKey PORTAL_LABEL_AMBIENT_RED = text("portal.label.ambient.red", "Red");
    public static final TextKey PORTAL_LABEL_AMBIENT_GREEN = text("portal.label.ambient.green", "Green");
    public static final TextKey PORTAL_LABEL_AMBIENT_BLUE = text("portal.label.ambient.blue", "Blue");
    public static final TextKey PORTAL_LABEL_AMBIENT_STYLE_SPARKS = text("portal.label.ambient_style.sparks", "Sparks");
    public static final TextKey PORTAL_LABEL_AMBIENT_STYLE_OUTLINE = text("portal.label.ambient_style.outline", "Outline");
    public static final TextKey PORTAL_LABEL_AMBIENT_STYLE_CORNERS = text("portal.label.ambient_style.corners", "Corners");
    public static final TextKey PORTAL_LABEL_AMBIENT_STYLE_OFF = text("portal.label.ambient_style.off", "Off");
    public static final TextKey PORTAL_LABEL_SKIN_NONE = text("portal.label.skin.none", "None");
    public static final LinesKey PORTAL_PROMPT_DIRECTION = lines("portal.prompt.direction",
            "&8[&6Wormholes&8] &7Look in a direction then left click to apply.",
            "&8[&6Wormholes&8] &7Shift-Left click to cancel.");
    public static final TextKey PORTAL_DIRECTION_CANCELLED = text("portal.direction.cancelled", "&8[&6Wormholes&8] &7Cancelled");
    public static final TextKey PORTAL_DIRECTION_SET = text("portal.direction.set", "&8[&6Wormholes&8] &7Direction set");
    public static final TextKey PORTAL_SETTING_NOTIFICATION = text("portal.setting.notification", "&a{portal}: &r{message}");
    public static final TextKey PORTAL_LINKED = text("portal.link.linked", "&a{portal} linked to {destination}.");
    public static final TextKey PORTAL_LINKED_REMOTE = text("portal.link.linked_remote", "&a{portal} linked to {destination} on {server}.");
    public static final TextKey PORTAL_UNLINKED = text("portal.link.unlinked", "&e{portal} unlinked from {destination}.");
    public static final TextKey PORTAL_FACE_FLIPPED = text("portal.orientation.face_flipped", "&a{portal} face flipped to {direction}.");
    public static final TextKey PORTAL_ROTATED_COUNTERCLOCKWISE = text("portal.orientation.rotated_counterclockwise", "&a{portal} rolled counterclockwise.");
    public static final TextKey PORTAL_ROTATED_CLOCKWISE = text("portal.orientation.rotated_clockwise", "&a{portal} rolled clockwise.");
    public static final TextKey PORTAL_DIRECTION_CHANGED = text("portal.orientation.direction_changed", "&a{portal}'s direction changed to {direction}.");
    public static final TextKey PORTAL_MODE_CHANGED = text("portal.mode.changed", "&a{portal} mode set to {mode}.");

    public static final TextKey LABEL_NONE = text("label.none", "None");
    public static final TextKey LABEL_ON = text("label.on", "On");
    public static final TextKey LABEL_OFF = text("label.off", "Off");
    public static final TextKey LABEL_READY = text("label.ready", "Ready");
    public static final TextKey LABEL_PREPARING = text("label.preparing", "Preparing");
    public static final TextKey LABEL_OPEN = text("label.open", "Open");
    public static final TextKey LABEL_CLOSED = text("label.closed", "Closed");

    public static final LinesKey RTP_OVERVIEW_DESTINATION = lines("rtp.overview.destination", "&e&lDestination & Area&r", "&7World, center, and radius.", "&eLeft click");
    public static final LinesKey RTP_OVERVIEW_LANDING = lines("rtp.overview.landing", "&e&lLanding Rules&r", "&7Surface and height behavior.", "&eLeft click");
    public static final LinesKey RTP_OVERVIEW_ROUTING = lines("rtp.overview.routing", "&e&lRotation & Pool&r", "&7Sharing, rotation, and leases.", "&eLeft click");
    public static final LinesKey RTP_OVERVIEW_EFFECTS = lines("rtp.overview.effects", "&e&lEffects&r", "&7Projection rim and portal sound.", "&eLeft click");
    public static final LinesKey RTP_RESET_DEFAULTS = lines("rtp.overview.reset_defaults", "&e&lReset To Defaults&r", "&7Immediately restore this portal's random teleport settings to their defaults.", "&eLeft click");
    public static final LinesKey RTP_BACK_PORTAL = lines("rtp.navigation.back_portal", "&e&lBack to Portal&r", "&7Return to the portal menu.", "&eLeft click");
    public static final LinesKey RTP_BACK_OVERVIEW = lines("rtp.navigation.overview", "&e&lOverview&r", "&7Return to the RTP overview.", "&eLeft click");
    public static final LinesKey RTP_BACK_CATEGORY = lines("rtp.navigation.back_category", "&e&lBack&r", "&7Return to the previous category.", "&eLeft click");
    public static final LinesKey RTP_DESTINATION_HEADER = lines("rtp.destination.header", "&b&lDestination & Area&r", "&7Every option uses a normal left click.");
    public static final LinesKey RTP_WORLD_CURRENT = lines("rtp.destination.world_current", "&a&l{world}&r", "&7Current target world.", "&aSelected");
    public static final LinesKey RTP_WORLD_AVAILABLE = lines("rtp.destination.world_available", "&b&l{world}&r", "&7Use this loaded world.", "&eLeft click to select");
    public static final LinesKey RTP_PREVIOUS_WORLDS = lines("rtp.destination.previous_worlds", "&e&lPrevious Worlds&r", "&7Show the previous page.", "&eLeft click");
    public static final LinesKey RTP_NEXT_WORLDS = lines("rtp.destination.next_worlds", "&e&lNext Worlds&r", "&7Show the next page.", "&eLeft click");
    public static final LinesKey RTP_BIOME_LINK = lines("rtp.destination.biome_link", "&e&lTarget Biome &f&l{value}&r", "&7Prefer landing in a chosen biome.", "&eLeft click");
    public static final TextKey RTP_BIOME_ANY_LABEL = text("rtp.biome.any_label", "Any");
    public static final LinesKey RTP_BIOME_HEADER = lines("rtp.biome.header", "&b&lTarget Biome&r", "&7The search prefers this biome and falls back to any safe spot when it cannot be found nearby.");
    public static final LinesKey RTP_BIOME_ANY_SELECTED = lines("rtp.biome.any_selected", "&a&lAny Biome&r", "&7No biome preference.", "&aSelected");
    public static final LinesKey RTP_BIOME_ANY_AVAILABLE = lines("rtp.biome.any_available", "&b&lAny Biome&r", "&7Clear the biome preference.", "&eLeft click to select");
    public static final LinesKey RTP_BIOME_CURRENT = lines("rtp.biome.current", "&a&l{biome}&r", "&7{key}", "&aSelected");
    public static final LinesKey RTP_BIOME_AVAILABLE = lines("rtp.biome.available", "&b&l{biome}&r", "&7{key}", "&eLeft click to select");
    public static final LinesKey RTP_BIOME_EMPTY = lines("rtp.biome.empty", "&7No biomes are available for the target world.");
    public static final LinesKey RTP_PREVIOUS_BIOMES = lines("rtp.biome.previous", "&e&lPrevious Biomes&r", "&7Show the previous page.", "&eLeft click");
    public static final LinesKey RTP_NEXT_BIOMES = lines("rtp.biome.next", "&e&lNext Biomes&r", "&7Show the next page.", "&eLeft click");
    public static final LinesKey RTP_CENTER_PORTAL_SELECTED = lines("rtp.destination.center_portal_selected", "&a&lPortal-relative Center&r", "&7Center the annulus on this portal.", "&aSelected");
    public static final LinesKey RTP_CENTER_PORTAL_AVAILABLE = lines("rtp.destination.center_portal_available", "&b&lPortal-relative Center&r", "&7Center the annulus on this portal.", "&eLeft click to select");
    public static final LinesKey RTP_CENTER_CUSTOM_SELECTED = lines("rtp.destination.center_custom_selected", "&a&lCustom Center&r", "&7Use editable X and Z coordinates.", "&aSelected");
    public static final LinesKey RTP_CENTER_CUSTOM_AVAILABLE = lines("rtp.destination.center_custom_available", "&b&lCustom Center&r", "&7Use editable X and Z coordinates.", "&eLeft click to select");
    public static final LinesKey RTP_NUMERIC_LINK = lines("rtp.numeric.link", "&e&l{label} &f&l{value}&r", "&7Open clear decrease/increase controls.", "&eLeft click");
    public static final LinesKey RTP_RESET_CENTER = lines("rtp.destination.reset_center", "&e&lReset Center / Target&r", "&7Use the source world and portal center.", "&eLeft click");
    public static final LinesKey RTP_LANDING_HEADER = lines("rtp.landing.header", "&b&lLanding Rules&r", "&7Choose surface or preferred-height behavior and its safety mode.");
    public static final LinesKey RTP_SURFACE_SELECTED = lines("rtp.landing.surface_selected", "&a&lSurface&r", "&7SAFE avoids water and tree tops. UNSAFE uses the topmost surface.", "&aSelected");
    public static final LinesKey RTP_SURFACE_AVAILABLE = lines("rtp.landing.surface_available", "&b&lSurface&r", "&7SAFE avoids water and tree tops. UNSAFE uses the topmost surface.", "&eLeft click to select");
    public static final LinesKey RTP_PREFERRED_SELECTED = lines("rtp.landing.preferred_selected", "&a&lPreferred / Exact Height&r", "&7SAFE searches outward. UNSAFE uses the exact preferred Y.", "&aSelected");
    public static final LinesKey RTP_PREFERRED_AVAILABLE = lines("rtp.landing.preferred_available", "&b&lPreferred / Exact Height&r", "&7SAFE searches outward. UNSAFE uses the exact preferred Y.", "&eLeft click to select");
    public static final LinesKey RTP_SAFE_LANDING = lines("rtp.landing.safe_policy", "&b&lLanding Safety: &f&l{mode}&r", "&7SAFE rejects water, hazards, trees, collisions, and unsupported ground.", "&7UNSAFE accepts the selected surface or exact preferred Y as-is.", "&eLeft click to toggle");
    public static final LinesKey RTP_ROUTING_HEADER = lines("rtp.routing.header", "&b&lRotation & Pool&r", "&7Choose modes directly, then apply the batch once.");
    public static final LinesKey RTP_SHARED_SELECTED = lines("rtp.routing.shared_selected", "&a&lShared Destination&r", "&7Everyone sees and uses the same route.", "&aSelected");
    public static final LinesKey RTP_SHARED_AVAILABLE = lines("rtp.routing.shared_available", "&b&lShared Destination&r", "&7Everyone sees and uses the same route.", "&eLeft click to select");
    public static final LinesKey RTP_PRIVATE_SELECTED = lines("rtp.routing.private_selected", "&a&lPer-player Destinations&r", "&7Each player receives a private reservation.", "&aSelected");
    public static final LinesKey RTP_PRIVATE_AVAILABLE = lines("rtp.routing.private_available", "&b&lPer-player Destinations&r", "&7Each player receives a private reservation.", "&eLeft click to select");
    public static final LinesKey RTP_STATIC_SELECTED = lines("rtp.routing.static_selected", "&a&lStatic&r", "&7Keep the same destination.", "&aSelected");
    public static final LinesKey RTP_STATIC_AVAILABLE = lines("rtp.routing.static_available", "&b&lStatic&r", "&7Keep the same destination.", "&eLeft click to select");
    public static final LinesKey RTP_TIMED_SELECTED = lines("rtp.routing.timed_selected", "&a&lTimed&r", "&7Rotate after the configured duration.", "&aSelected");
    public static final LinesKey RTP_TIMED_AVAILABLE = lines("rtp.routing.timed_available", "&b&lTimed&r", "&7Rotate after the configured duration.", "&eLeft click to select");
    public static final LinesKey RTP_TRIP_SELECTED = lines("rtp.routing.trip_selected", "&a&lAfter Every Trip&r", "&7Promote a prepared replacement after use.", "&aSelected");
    public static final LinesKey RTP_TRIP_AVAILABLE = lines("rtp.routing.trip_available", "&b&lAfter Every Trip&r", "&7Promote a prepared replacement after use.", "&eLeft click to select");
    public static final LinesKey RTP_MANUAL_REROLL = lines("rtp.routing.manual_reroll", "&e&lManual Reroll&r", "&7{description}", "&eLeft click");
    public static final LinesKey RTP_REBUILD_POOL = lines("rtp.routing.rebuild_pool", "&e&lRebuild Pool&r", "&7{description}", "&eLeft click");
    public static final TextKey RTP_ACTION_CONFIRM = text("rtp.routing.action_confirm", "Open a separate confirmation screen.");
    public static final LinesKey RTP_EFFECTS_HEADER = lines("rtp.effects.header", "&b&lEffects&r", "&7Presentation changes do not regenerate destinations.");
    public static final LinesKey RTP_RIM_ON_SELECTED = lines("rtp.effects.rim_on_selected", "&a&lReadiness Rim On&r", "&7Show private readiness around the portal rim.", "&aSelected");
    public static final LinesKey RTP_RIM_ON_AVAILABLE = lines("rtp.effects.rim_on_available", "&b&lReadiness Rim On&r", "&7Show private readiness around the portal rim.", "&eLeft click to select");
    public static final LinesKey RTP_RIM_OFF_SELECTED = lines("rtp.effects.rim_off_selected", "&a&lReadiness Rim Off&r", "&7Hide the readiness rim.", "&aSelected");
    public static final LinesKey RTP_RIM_OFF_AVAILABLE = lines("rtp.effects.rim_off_available", "&b&lReadiness Rim Off&r", "&7Hide the readiness rim.", "&eLeft click to select");
    public static final LinesKey RTP_SOUND_ON_SELECTED = lines("rtp.effects.sound_on_selected", "&a&lPortal Sounds On&r", "&7Play this RTP portal's effects and travel sounds.", "&aSelected");
    public static final LinesKey RTP_SOUND_ON_AVAILABLE = lines("rtp.effects.sound_on_available", "&b&lPortal Sounds On&r", "&7Play this RTP portal's effects and travel sounds.", "&eLeft click to select");
    public static final LinesKey RTP_SOUND_OFF_SELECTED = lines("rtp.effects.sound_off_selected", "&a&lPortal Sounds Off&r", "&7Mute this RTP portal; particles remain enabled.", "&aSelected");
    public static final LinesKey RTP_SOUND_OFF_AVAILABLE = lines("rtp.effects.sound_off_available", "&b&lPortal Sounds Off&r", "&7Mute this RTP portal; particles remain enabled.", "&eLeft click to select");
    public static final LinesKey RTP_TARGET_UNAVAILABLE = lines("rtp.numeric.target_unavailable", "&b&lTarget World Unavailable&r", "&7Load the target world before editing this value.");
    public static final LinesKey RTP_NUMERIC_VALUE = lines("rtp.numeric.value", "&b&l{value}&r", "&7Current draft value.");
    public static final LinesKey RTP_NUMERIC_ADJUST = lines("rtp.numeric.adjust", "&e&l{direction}{step}&r", "&7Adjust the draft by {direction}{step}.", "&eLeft click");
    public static final LinesKey RTP_NUMERIC_HEADER = lines("rtp.numeric.header", "&b&l{label}&r", "&7{description}");
    public static final LinesKey RTP_CONFIRM_REROLL = lines("rtp.confirm.reroll", "&b&lReroll Shared Route?&r", "&7The current projection stays online until the replacement is ready.");
    public static final LinesKey RTP_CONFIRM_REBUILD = lines("rtp.confirm.rebuild", "&b&lRebuild Private Pool?&r", "&7Existing reservations stay intact while free candidates rebuild.");
    public static final LinesKey RTP_CONFIRM = lines("rtp.confirm.confirm", "&e&lConfirm&r", "&7Run the action now.", "&eLeft click");
    public static final LinesKey RTP_CANCEL = lines("rtp.confirm.cancel", "&e&lCancel&r", "&7Return without changing runtime state.", "&eLeft click");
    public static final TextKey RTP_STATUS_READY = text("rtp.status.ready", "Ready");
    public static final TextKey RTP_STATUS_WARMING = text("rtp.status.warming", "Warming");
    public static final TextKey RTP_STATUS_REROLLING = text("rtp.status.rerolling", "Rerolling");
    public static final TextKey RTP_STATUS_BACKOFF = text("rtp.status.backoff", "Retry Backoff");
    public static final TextKey RTP_STATUS_WORLD_UNAVAILABLE = text("rtp.status.world_unavailable", "Target World Unavailable");
    public static final TextKey RTP_STATUS_INTEGRATION_FAILED = text("rtp.status.integration_failed", "Access Integration Failed");
    public static final TextKey RTP_STATUS_FAILED = text("rtp.status.failed", "Failed");
    public static final TextKey RTP_STATUS_IDLE = text("rtp.status.idle", "Idle");
    public static final TextKey RTP_ROTATION_STATIC = text("rtp.rotation.static", "Static");
    public static final TextKey RTP_ROTATION_TIMED = text("rtp.rotation.timed", "Timed");
    public static final TextKey RTP_ROTATION_TRIP = text("rtp.rotation.trip", "After Every Trip");
    public static final LinesKey RTP_STATUS_HEADER = lines("rtp.status.header", "&6&lRandom Destination Status&r", "&7State: {state}");
    public static final TextKey RTP_STATUS_RETRY = text("rtp.status.retry", "&7Retry in: &c{duration}");
    public static final TextKey RTP_STATUS_ACTIVE = text("rtp.status.active", "&7Active: {readiness}");
    public static final TextKey RTP_STATUS_STANDBY = text("rtp.status.standby", "&7Standby: {readiness}");
    public static final TextKey RTP_STATUS_ROTATION = text("rtp.status.rotation", "&7Draft rotation: &b{rotation}");
    public static final TextKey RTP_STATUS_POOL = text("rtp.status.pool", "&7Free: &b{free}&7  Reserved: &b{reserved}");
    public static final TextKey RTP_STATUS_TARGET_MISSING = text("rtp.status.target_missing", "&cTarget world is not loaded.");
    public static final TextKey RTP_STATUS_ACCESS_FAILED = text("rtp.status.access_failed", "&cDestination access checks failed closed.");
    public static final TextKey RTP_LABEL_CENTER_X = text("rtp.numeric.center_x", "Center X");
    public static final TextKey RTP_LABEL_CENTER_Z = text("rtp.numeric.center_z", "Center Z");
    public static final TextKey RTP_LABEL_MIN_RADIUS = text("rtp.numeric.minimum_radius", "Minimum Radius");
    public static final TextKey RTP_LABEL_MAX_RADIUS = text("rtp.numeric.maximum_radius", "Maximum Radius");
    public static final TextKey RTP_LABEL_LOWER_Y = text("rtp.numeric.lower_y", "Lower Y");
    public static final TextKey RTP_LABEL_UPPER_Y = text("rtp.numeric.upper_y", "Upper Y");
    public static final TextKey RTP_LABEL_PREFERRED_Y = text("rtp.numeric.preferred_y", "Preferred Y");
    public static final TextKey RTP_LABEL_CYCLE = text("rtp.numeric.cycle", "Cycle Duration");
    public static final TextKey RTP_LABEL_PRIVATE_ROTATION = text("rtp.numeric.private_rotation", "Private Rotation Time");
    public static final TextKey RTP_LABEL_LEASE = text("rtp.numeric.lease", "Idle Lease Grace");
    public static final TextKey RTP_LABEL_RELEASE = text("rtp.numeric.release", "Private Release");
    public static final TextKey RTP_DESCRIPTION_CENTER = text("rtp.numeric.description.center", "Move the custom center on this axis.");
    public static final TextKey RTP_DESCRIPTION_MIN_RADIUS = text("rtp.numeric.description.minimum_radius", "Inner edge of the destination annulus.");
    public static final TextKey RTP_DESCRIPTION_MAX_RADIUS = text("rtp.numeric.description.maximum_radius", "Outer edge of the destination annulus.");
    public static final TextKey RTP_DESCRIPTION_Y = text("rtp.numeric.description.y", "Legal feet-height search bound.");
    public static final TextKey RTP_DESCRIPTION_CYCLE = text("rtp.numeric.description.cycle", "Time between shared timed rotations.");
    public static final TextKey RTP_DESCRIPTION_PRIVATE_ROTATION = text("rtp.numeric.description.private_rotation", "Maximum age of each player's prepared destination.");
    public static final TextKey RTP_DESCRIPTION_LEASE = text("rtp.numeric.description.lease", "Keep prepared destinations warm briefly after everyone leaves.");
    public static final TextKey RTP_DESCRIPTION_RELEASE = text("rtp.numeric.description.release", "Delay before an unused private reservation releases.");
    public static final TextKey RTP_DURATION_HOURS = text("rtp.duration.hours", "{value}h");
    public static final TextKey RTP_DURATION_MINUTES = text("rtp.duration.minutes", "{value}m");
    public static final TextKey RTP_DURATION_SECONDS = text("rtp.duration.seconds", "{value}s");
    public static final TextKey RTP_DURATION_DECIMAL_SECONDS = text("rtp.duration.decimal_seconds", "{value}s");

    private WormholesMessages() {
    }

    public static MessageCatalog catalog() {
        return MessageCatalog.builder(ENGLISH_LOCALE)
                .addAll(DirectorMessages.keys())
                .addAll(BukkitLanguageMessages.keys())
                .addAll(KEYS)
                .build();
    }

    private static TextKey text(String id, String english) {
        TextKey key = TextKey.of(id, english);
        KEYS.add(key);
        return key;
    }

    private static LinesKey lines(String id, String... english) {
        LinesKey key = LinesKey.of(id, english);
        KEYS.add(key);
        return key;
    }

    private static PluralKey plural(String id, String selectorArgument, Map<String, String> english) {
        PluralKey key = PluralKey.of(id, selectorArgument, english);
        KEYS.add(key);
        return key;
    }
}
