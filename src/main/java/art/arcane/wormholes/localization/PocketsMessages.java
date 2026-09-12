package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

/**
 * Message keys owned by the doors lane. Every id starts with "pockets.". Add keys here and translate
 * them inside the "lane:doors" block of every languages/*.toml file.
 */
public final class PocketsMessages {
    private static final MessageGroup GROUP = new MessageGroup("pockets.");

    public static final TextKey TEMPLATE_LIST = GROUP.text("pockets.template.list",
            "&8[&6Wormholes&8] &7Templates (&f{count}&7): &f{value}");
    public static final TextKey TEMPLATE_APPLIED = GROUP.text("pockets.template.applied",
            "&8[&6Wormholes&8] &aApplied template &f{name}&a to pocket &f{space}&a.");
    public static final TextKey TEMPLATE_MISSING = GROUP.text("pockets.template.missing",
            "&8[&6Wormholes&8] &cThere is no pocket template named &f{name}&c.");
    public static final LinesKey CONFIRM_OVERWRITE = GROUP.lines("pockets.confirm.overwrite",
            "&8[&6Wormholes&8] &cThis replaces everything inside pocket &f{space}&c.",
            "&8[&6Wormholes&8] &7Run the same command with &fconfirm=true&7 to go ahead.");
    public static final TextKey RULES_SET = GROUP.text("pockets.rules.set",
            "&8[&6Wormholes&8] &a{key} &7= &f{value}");
    public static final TextKey RULES_INVALID = GROUP.text("pockets.rules.invalid",
            "&8[&6Wormholes&8] &cUnknown pocket rule &f{key}&c.");
    public static final TextKey ROSTER_ADDED = GROUP.text("pockets.roster.added",
            "&8[&6Wormholes&8] &a{name}&a joined the pocket roster as &f{value}&a.");
    public static final TextKey ROSTER_REMOVED = GROUP.text("pockets.roster.removed",
            "&8[&6Wormholes&8] &e{name}&e left the pocket roster.");
    public static final TextKey ROSTER_ROLE = GROUP.text("pockets.roster.role",
            "&8[&6Wormholes&8] &a{name}&a is now &f{value}&a in this pocket.");
    public static final TextKey ROSTER_MISSING = GROUP.text("pockets.roster.missing",
            "&8[&6Wormholes&8] &c{name}&c is not on this pocket roster.");
    public static final TextKey ROOM_ADDED = GROUP.text("pockets.room.added",
            "&8[&6Wormholes&8] &aAdded room &f{count}&a to pocket &f{space}&a.");
    public static final TextKey ROOM_LIMIT = GROUP.text("pockets.room.limit",
            "&8[&6Wormholes&8] &cThis pocket already has its maximum of &f{count}&c rooms.");
    public static final TextKey ROOM_BLOCKED = GROUP.text("pockets.room.blocked",
            "&8[&6Wormholes&8] &cPlace the door on an interior wall.");
    public static final TextKey INSTANCE_RESET = GROUP.text("pockets.instance.reset",
            "&8[&6Wormholes&8] &aPocket instance &f{space}&a was reset.");
    public static final TextKey INSTANCE_NONE = GROUP.text("pockets.instance.none",
            "&8[&6Wormholes&8] &cThis pocket is not an instance.");
    public static final TextKey SNAPSHOT_SAVED = GROUP.text("pockets.snapshot.saved",
            "&8[&6Wormholes&8] &aSaved snapshot &f{name}&a of pocket &f{space}&a.");
    public static final TextKey SNAPSHOT_RESTORED = GROUP.text("pockets.snapshot.restored",
            "&8[&6Wormholes&8] &aRestored snapshot &f{name}&a into pocket &f{space}&a.");
    public static final TextKey SNAPSHOT_MISSING = GROUP.text("pockets.snapshot.missing",
            "&8[&6Wormholes&8] &cThis pocket has no snapshot named &f{name}&c.");
    public static final TextKey DENIED_BUILD = GROUP.text("pockets.denied.build",
            "&8[&6Wormholes&8] &cYou are not a builder in this pocket.");
    public static final TextKey DENIED_PVP = GROUP.text("pockets.denied.pvp",
            "&8[&6Wormholes&8] &cFighting is turned off in this pocket.");

    private PocketsMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
