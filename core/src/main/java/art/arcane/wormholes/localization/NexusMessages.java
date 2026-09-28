package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

/**
 * Message keys owned by the nexus lane. Every id starts with "nexus.". Add keys here and translate
 * them inside the "lane:nexus" block of every languages/*.toml file.
 */
public final class NexusMessages {
    private static final MessageGroup GROUP = new MessageGroup("nexus.");

    public static final TextKey DIALED = GROUP.text("nexus.dialed",
            "&8[&6Wormholes&8] &a{portal} dialed to {address}: &f{destination}");
    public static final TextKey DIAL_UNKNOWN = GROUP.text("nexus.dial.unknown",
            "&8[&6Wormholes&8] &cNo portal at address {address}.");
    public static final TextKey DIAL_NONE = GROUP.text("nexus.dial.none",
            "&8[&6Wormholes&8] &c{portal} is not on a network.");
    public static final TextKey DIAL_DEBOUNCED = GROUP.text("nexus.dial.debounced",
            "&8[&6Wormholes&8] &7Dialing too fast; wait a moment.");
    public static final TextKey DIAL_MANAGED = GROUP.text("nexus.dial.managed",
            "&8[&6Wormholes&8] &c{portal} is a managed portal and cannot be dialed.");

    public static final TextKey CREATED = GROUP.text("nexus.created",
            "&8[&6Wormholes&8] &aCreated network {name}.");
    public static final TextKey DELETED = GROUP.text("nexus.deleted",
            "&8[&6Wormholes&8] &aDeleted network {name}.");
    public static final TextKey JOINED = GROUP.text("nexus.joined",
            "&8[&6Wormholes&8] &a{portal} joined {name} at address {address}.");
    public static final TextKey LEFT = GROUP.text("nexus.left",
            "&8[&6Wormholes&8] &a{portal} left {name}.");
    public static final TextKey ADDRESS_TAKEN = GROUP.text("nexus.address.taken",
            "&8[&6Wormholes&8] &cAddress {address} is already used in {name}.");
    public static final TextKey ADDRESS_SET = GROUP.text("nexus.address.set",
            "&8[&6Wormholes&8] &a{portal} answers to {address}.");
    public static final TextKey ADDRESS_INVALID = GROUP.text("nexus.address.invalid",
            "&8[&6Wormholes&8] &cAddresses use only these characters: {value}");
    public static final TextKey NETWORK_UNKNOWN = GROUP.text("nexus.network.unknown",
            "&8[&6Wormholes&8] &cNo network named {name}.");
    public static final TextKey NETWORK_EXISTS = GROUP.text("nexus.network.exists",
            "&8[&6Wormholes&8] &cA network named {name} already exists.");
    public static final TextKey NETWORK_FULL = GROUP.text("nexus.network.full",
            "&8[&6Wormholes&8] &c{name} already holds {count} portals.");
    public static final TextKey NETWORK_LIMIT = GROUP.text("nexus.network.limit",
            "&8[&6Wormholes&8] &cYou already own {count} networks.");
    public static final TextKey PORTAL_UNKNOWN = GROUP.text("nexus.portal.unknown",
            "&8[&6Wormholes&8] &cNo portal named {portal}.");
    public static final TextKey PORTAL_NOT_MEMBER = GROUP.text("nexus.portal.not_member",
            "&8[&6Wormholes&8] &c{portal} is not a member of {name}.");
    public static final TextKey VISIBILITY_SET = GROUP.text("nexus.visibility.set",
            "&8[&6Wormholes&8] &a{name} is now {value}.");
    public static final TextKey HUB_SET = GROUP.text("nexus.hub.set",
            "&8[&6Wormholes&8] &a{portal} is the hub of {name}.");
    public static final TextKey LIST_EMPTY = GROUP.text("nexus.list.empty",
            "&8[&6Wormholes&8] &7No networks yet.");
    public static final TextKey LIST_ENTRY = GROUP.text("nexus.list.entry",
            "&7{name} &8- &f{count} portals&7, {value}, owner &f{owner}");
    public static final TextKey INFO_HEADER = GROUP.text("nexus.info.header",
            "&6{name} &8- &f{count} portals&7, {value}, {mode}");
    public static final TextKey INFO_MEMBER = GROUP.text("nexus.info.member",
            "&e{address} &f{portal} &7in {world} &8{state}");

    public static final TextKey DOCTOR_HEADER = GROUP.text("nexus.doctor.header",
            "&8[&6Wormholes&8] &6Link doctor: {count} findings.");
    public static final TextKey DOCTOR_CLEAN = GROUP.text("nexus.doctor.clean",
            "&8[&6Wormholes&8] &aNo link problems found.");
    public static final TextKey DOCTOR_ONEWAY = GROUP.text("nexus.doctor.oneway",
            "&e{portal} -> {destination} has no return link.");
    public static final TextKey DOCTOR_DANGLING = GROUP.text("nexus.doctor.dangling",
            "&c{portal} points at a missing destination.");
    public static final TextKey DOCTOR_UNLOADED = GROUP.text("nexus.doctor.unloaded",
            "&e{portal} points into unloaded world {world}.");
    public static final TextKey DOCTOR_OFFLINE = GROUP.text("nexus.doctor.offline",
            "&c{portal} points at {server}, offline for {count} days.");

    public static final TextKey REDSTONE_SET = GROUP.text("nexus.redstone.set",
            "&8[&6Wormholes&8] &a{portal} redstone: {value}.");

    public static final TextKey RECIPROCAL_PAIRED = GROUP.text("nexus.reciprocal.paired",
            "&8[&6Wormholes&8] &a{portal} and {destination} now link both ways.");
    public static final TextKey RECIPROCAL_UNPAIRED = GROUP.text("nexus.reciprocal.unpaired",
            "&8[&6Wormholes&8] &a{portal} and {destination} no longer link back.");
    public static final TextKey RECIPROCAL_DENIED = GROUP.text("nexus.reciprocal.denied",
            "&8[&6Wormholes&8] &cYou cannot manage both {portal} and {destination}.");

    public static final TextKey PROMPT_NETWORK_NAME = GROUP.text("nexus.prompt.network_name",
            "&bType the network name in chat (or '{cancel}'):");
    public static final TextKey PROMPT_ADDRESS = GROUP.text("nexus.prompt.address",
            "&bType the new address in chat (or '{cancel}'):");

    public static final LinesKey MENU_ENTRY = GROUP.lines("nexus.menu.entry",
            "&b&lNetwork&r",
            "&7Networks, addresses, dialing, destination policy, redstone.",
            "&7Network: &f{name}",
            "&7Address: &f{address}",
            "&eLeft-click");
    public static final LinesKey MENU_PLACARD = GROUP.lines("nexus.menu.placard",
            "&b&lNetwork: {name}&r",
            "&7Address: &f{address}",
            "&7Members: &f{count}");
    public static final LinesKey MENU_JOIN = GROUP.lines("nexus.menu.join",
            "&a&lJoin a network&r",
            "&7Add this portal to a network and give it an address.",
            "&eLeft-click");
    public static final LinesKey MENU_LEAVE = GROUP.lines("nexus.menu.leave",
            "&c&lLeave {name}&r",
            "&7Remove this portal and its address from the network.",
            "&eLeft-click");
    public static final LinesKey MENU_CREATE = GROUP.lines("nexus.menu.create",
            "&a&lCreate a network&r",
            "&7Name a new network and put this portal in it.",
            "&eLeft-click");
    public static final LinesKey MENU_ADDRESS = GROUP.lines("nexus.menu.address",
            "&e&lAddress: {address}&r",
            "&7Short code other portals dial to reach this one.",
            "&eLeft-click to retype. &eRight-click to reroll.");
    public static final LinesKey MENU_VISIBILITY = GROUP.lines("nexus.menu.visibility",
            "&e&lVisibility: {value}&r",
            "&7Public networks list for everyone; members-only lists for members; hidden never lists.",
            "&eLeft-click to cycle");
    public static final LinesKey MENU_TOPOLOGY = GROUP.lines("nexus.menu.topology",
            "&e&lTopology: {value}&r",
            "&7Mesh dials any member; hub routes through one portal; chain and ring dial neighbours.",
            "&eLeft-click to cycle");
    public static final LinesKey MENU_RECIPROCAL = GROUP.lines("nexus.menu.reciprocal",
            "&e&lReturn link: {state}&r",
            "&7Keep the destination pointing back at this portal.",
            "&eLeft-click to toggle");
    public static final LinesKey MENU_POLICY = GROUP.lines("nexus.menu.policy",
            "&e&lDestinations: {mode}&r",
            "&7{count} entries. Ordered, weighted, scheduled, per-player, or return.",
            "&eLeft-click to cycle the mode. &eRight-click to clear.");
    public static final LinesKey MENU_REDSTONE = GROUP.lines("nexus.menu.redstone",
            "&e&lRedstone: {value}&r",
            "&7What a rising edge next to the frame does, and what a comparator reads.",
            "&eLeft-click for the action. &eRight-click for comparator output.");
    public static final LinesKey MENU_DIAL = GROUP.lines("nexus.menu.dial",
            "&b&lDial&r",
            "&7Point this portal at another address on its network.",
            "&eLeft-click");
    public static final LinesKey MENU_DIAL_ENTRY = GROUP.lines("nexus.menu.dial_entry",
            "&e{address} &f{portal}",
            "&7{world}",
            "&b{state}");
    public static final LinesKey MENU_LINK_AND_RETURN = GROUP.lines("nexus.menu.link_and_return",
            "&d&lLink and return&r",
            "&7Also point {destination} back at {portal}.",
            "&eLeft-click");

    private NexusMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
