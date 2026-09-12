package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

/**
 * Message keys owned by the access lane. Every id starts with "access.". Add keys here and translate
 * them inside the "lane:access" block of every languages/*.toml file.
 */
public final class AccessMessages {
    private static final MessageGroup GROUP = new MessageGroup("access.");

    public static final TextKey DENIED_ROLE = GROUP.text("access.denied.role", "&cYou are not allowed to use {portal}.");
    public static final TextKey DENIED_CLAIM = GROUP.text("access.denied.claim", "&cA land claim blocks {portal}.");
    public static final TextKey DENIED_LIMIT = GROUP.text("access.denied.limit", "&cYou already own {count} portals; the limit is {maximum}.");
    public static final TextKey CONSTRUCT_DENIED = GROUP.text("access.construct.denied", "&cYou cannot build a portal here: {reason}.");
    public static final TextKey CLAIM_REASON = GROUP.text("access.claim.reason", "{plugin} protects this ground");
    public static final TextKey CLAIM_FAILURE = GROUP.text("access.claim.failure", "{plugin} could not be asked");

    public static final TextKey TRANSFER_DONE = GROUP.text("access.transfer.done", "&8[&6Wormholes&8] &a{portal} now belongs to {name}.");
    public static final TextKey KEY_SET = GROUP.text("access.key.set", "&aPermission key for {portal} is now {key}.");
    public static final TextKey KEY_TAKEN = GROUP.text("access.key.taken", "&cPermission key {key} is already used.");
    public static final TextKey KEY_INVALID = GROUP.text("access.key.invalid", "&cPermission keys use a-z, 0-9, dot, dash, and underscore, up to 64 characters.");

    public static final TextKey PLAYER_NOT_FOUND = GROUP.text("access.player.not_found", "&cNo player named {name}.");
    public static final TextKey PORTAL_NOT_FOUND = GROUP.text("access.portal.not_found", "&8[&6Wormholes&8] &cNo portal named {portal}.");
    public static final TextKey PORTAL_AMBIGUOUS = GROUP.text("access.portal.ambiguous", "&8[&6Wormholes&8] &c{count} portals match {portal}; use the portal id.");

    public static final TextKey ROLE_CHANGED = GROUP.text("access.role.changed", "&a{name} is now {state} on {portal}.");
    public static final TextKey ROLE_REMOVED = GROUP.text("access.role.removed", "&a{name} no longer has a role on {portal}.");
    public static final TextKey ROLE_OWNER_ALWAYS = GROUP.text("access.role.owner_always", "&7The owner always has full access.");
    public static final TextKey LISTED_CHANGED = GROUP.text("access.listed.changed", "&a{portal} is now {state} in the public directory.");
    public static final TextKey GROUP_ADDED = GROUP.text("access.group.added", "&a{portal} now grants access to {node}.");
    public static final TextKey GROUP_CLEARED = GROUP.text("access.group.cleared", "&a{portal} no longer grants access by group.");

    public static final TextKey LIMITS_REPORT = GROUP.text("access.limits.report", "&8[&6Wormholes&8] &7{name} owns {count} portals; the limit is {maximum}.");
    public static final TextKey LIMITS_UNLIMITED = GROUP.text("access.limits.unlimited", "&8[&6Wormholes&8] &7{name} owns {count} portals and has no limit.");

    public static final TextKey PROMPT_PLAYER = GROUP.text("access.prompt.player", "&7Type a player name in chat, or {cancel} to stop.");
    public static final TextKey PROMPT_KEY = GROUP.text("access.prompt.key", "&7Type the new permission key in chat, or {cancel} to stop.");
    public static final TextKey PROMPT_GROUP = GROUP.text("access.prompt.group", "&7Type a permission node in chat, or {cancel} to stop.");

    public static final TextKey LABEL_OWNER = GROUP.text("access.label.owner", "Owner");
    public static final TextKey LABEL_CO_OWNER = GROUP.text("access.label.co_owner", "Co-owner");
    public static final TextKey LABEL_USER = GROUP.text("access.label.user", "Trusted");
    public static final TextKey LABEL_DENIED = GROUP.text("access.label.denied", "Denied");
    public static final TextKey LABEL_LISTED = GROUP.text("access.label.listed", "Listed");
    public static final TextKey LABEL_UNLISTED = GROUP.text("access.label.unlisted", "Hidden");

    public static final LinesKey MENU_ENTRY = GROUP.lines("access.menu.entry",
        "&b&lAccess&r",
        "&7Roles, groups, and the permission key.");
    public static final LinesKey MENU_PLACARD = GROUP.lines("access.menu.placard",
        "&b&lAccess: {portal}&r",
        "&7Owner: &f{owner}",
        "&7Roles: &f{count}",
        "&7Key: &f{key}");
    public static final LinesKey MENU_ADD = GROUP.lines("access.menu.add",
        "&a&lAdd a player&r",
        "&7Type a name in chat to give them a role.");
    public static final LinesKey MENU_ROLE = GROUP.lines("access.menu.role",
        "&f&l{name}&r",
        "&7Role: &f{state}",
        "&7Left or right click to change the role.",
        "&7Shift-left click to remove it.");
    public static final LinesKey MENU_GROUPS = GROUP.lines("access.menu.groups",
        "&e&lAllowed groups&r",
        "&7{count} permission nodes grant access.",
        "&7Left click to add one, right click to clear.");
    public static final LinesKey MENU_KEY = GROUP.lines("access.menu.key",
        "&b&lPermission key&r",
        "&7{key}",
        "&7Left click to change it.");
    public static final LinesKey MENU_LISTED = GROUP.lines("access.menu.listed",
        "&b&lPublic directory&r",
        "&7{state}",
        "&7Left click to toggle.");

    public static final TextKey COMMAND_ACCESS = GROUP.text("access.command.help.access", "Portal access lists, ownership, and limits");
    public static final TextKey COMMAND_TRANSFER = GROUP.text("access.command.help.transfer", "Give a portal to another player");
    public static final TextKey COMMAND_TRANSFER_PORTAL = GROUP.text("access.command.help.transfer.portal", "Portal name or id");
    public static final TextKey COMMAND_TRANSFER_PLAYER = GROUP.text("access.command.help.transfer.player", "Name of the new owner");
    public static final TextKey COMMAND_KEY = GROUP.text("access.command.help.key", "Set the stable permission key of a portal");
    public static final TextKey COMMAND_KEY_PORTAL = GROUP.text("access.command.help.key.portal", "Portal name or id");
    public static final TextKey COMMAND_KEY_KEY = GROUP.text("access.command.help.key.key", "New permission key");
    public static final TextKey COMMAND_LIMITS = GROUP.text("access.command.help.limits", "Show how many portals a player owns and may own");
    public static final TextKey COMMAND_LIMITS_PLAYER = GROUP.text("access.command.help.limits.player", "Player to inspect; defaults to you");

    private AccessMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
