package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

/**
 * Message keys owned by the mesh lane. Every id starts with "mesh.". Add keys here and translate
 * them inside the "lane:mesh" block of every languages/*.toml file.
 */
public final class MeshMessages {
    private static final MessageGroup GROUP = new MessageGroup("mesh.");

    public static final TextKey PENDING = GROUP.text("mesh.pending", "&8[&6Wormholes&8] &e{server}&7 wants to join through &f{name}&7: /wh network accept {server}");
    public static final TextKey ACCEPTED = GROUP.text("mesh.accepted", "&8[&6Wormholes&8] &aAccepted &f{server}&a into the network.");
    public static final TextKey REJECTED = GROUP.text("mesh.rejected", "&8[&6Wormholes&8] &cRejected &f{server}&c and discarded its introduction.");
    public static final TextKey TOMBSTONED = GROUP.text("mesh.tombstoned", "&8[&6Wormholes&8] &f{server}&7 was removed network-wide.");
    public static final TextKey DISABLED = GROUP.text("mesh.disabled", "&8[&6Wormholes&8] &7Mesh federation is &cdisabled&7 ([network.mesh] enabled = false).");
    public static final TextKey INTRODUCTIONS_NONE = GROUP.text("mesh.introductions.none", "&8[&6Wormholes&8] &7No introductions are waiting for approval.");
    public static final TextKey INTRODUCTIONS_UNKNOWN = GROUP.text("mesh.introductions.unknown", "&8[&6Wormholes&8] &c{server} is not waiting for approval.");
    public static final TextKey MEMBERS_NONE = GROUP.text("mesh.members.none", "&8[&6Wormholes&8] &7No network members are known yet.");
    public static final TextKey MEMBERS_ROW = GROUP.text("mesh.members.row", "&8[&6Wormholes&8] &f{server}&7 {state} &8{fingerprint}");
    public static final TextKey VERSIONS_NONE = GROUP.text("mesh.versions.none", "&8[&6Wormholes&8] &7No peers have linked yet.");
    public static final TextKey VERSIONS_ROW = GROUP.text("mesh.versions.row", "&8[&6Wormholes&8] &f{server}&7: protocol &f{value}&7, plugin &f{name}&7, capabilities &f{state}");
    public static final TextKey VERSIONS_REDUCED = GROUP.text("mesh.versions.reduced", "&8[&6Wormholes&8] &e{server}&7 links with reduced capability: &f{reason}");
    public static final TextKey DRAIN_ON = GROUP.text("mesh.drain.on", "&8[&6Wormholes&8] &eDrain mode on: this server refuses new arrivals until drain is turned off.");
    public static final TextKey DRAIN_OFF = GROUP.text("mesh.drain.off", "&8[&6Wormholes&8] &aDrain mode off: this server accepts arrivals again.");
    public static final TextKey DRAIN_STATE = GROUP.text("mesh.drain.state", "&8[&6Wormholes&8] &7Drain mode is {state}.");
    public static final TextKey DRAIN_INVALID = GROUP.text("mesh.drain.invalid", "&8[&6Wormholes&8] &cDrain state must be on or off, got {value}.");
    public static final TextKey QUEUE_POSITION = GROUP.text("mesh.queue.position", "&7Waiting for a free server: position &f{count}&7, &f{seconds}&7s");
    public static final TextKey QUEUE_TIMEOUT = GROUP.text("mesh.queue.timeout", "&cNo destination server had room in time.");
    public static final TextKey POLICY_NONE = GROUP.text("mesh.policy.none", "&cNo destination is available right now.");
    public static final TextKey POLICY_SET = GROUP.text("mesh.policy.set", "&8[&6Wormholes&8] &aPolicy on &f{portal}&a: {value}");
    public static final TextKey POLICY_CLEARED = GROUP.text("mesh.policy.cleared", "&8[&6Wormholes&8] &7Policy cleared on &f{portal}&7; it links to its plain destination again.");
    public static final TextKey POLICY_PORTAL_UNKNOWN = GROUP.text("mesh.policy.portal_unknown", "&8[&6Wormholes&8] &cNo local portal named {portal}.");
    public static final TextKey POLICY_INVALID = GROUP.text("mesh.policy.invalid", "&8[&6Wormholes&8] &cInvalid policy: {reason}");
    public static final TextKey COMMAND_MEMBERS = GROUP.text("mesh.command.help.members", "List every network member with link state and key fingerprint");
    public static final TextKey COMMAND_PENDING = GROUP.text("mesh.command.help.pending", "List introduced servers waiting for approval");
    public static final TextKey COMMAND_ACCEPT = GROUP.text("mesh.command.help.accept", "Trust an introduced server and save its route");
    public static final TextKey COMMAND_ACCEPT_SERVER = GROUP.text("mesh.command.param.accept.server", "Introduced server name (see /wh network pending)");
    public static final TextKey COMMAND_REJECT = GROUP.text("mesh.command.help.reject", "Discard an introduced server's pending introduction");
    public static final TextKey COMMAND_REJECT_SERVER = GROUP.text("mesh.command.param.reject.server", "Introduced server name to reject");
    public static final TextKey COMMAND_VERSIONS = GROUP.text("mesh.command.help.versions", "Show each peer's protocol, plugin version and capability set");
    public static final TextKey COMMAND_DRAIN = GROUP.text("mesh.command.help.drain", "Take this server out of destination rotation or put it back");
    public static final TextKey COMMAND_DRAIN_STATE = GROUP.text("mesh.command.param.drain.state", "on or off; omit to show the current state");
    public static final TextKey COMMAND_POLICY = GROUP.text("mesh.command.help.policy", "Set or clear a gateway's destination policy");
    public static final TextKey COMMAND_POLICY_PORTAL = GROUP.text("mesh.command.param.policy.portal", "Local portal name or id");
    public static final TextKey COMMAND_POLICY_CANDIDATES = GROUP.text("mesh.command.param.policy.candidates", "Comma-separated server:portal[:weight] entries; empty clears the policy");
    public static final TextKey COMMAND_POLICY_STRATEGY = GROUP.text("mesh.command.param.policy.strategy", "FIRST_AVAILABLE, LEAST_LOADED, ROUND_ROBIN, STICKY or NEAREST");
    public static final TextKey COMMAND_POLICY_HEADROOM = GROUP.text("mesh.command.param.policy.headroom", "Free player slots a candidate must report");
    public static final TextKey COMMAND_POLICY_TPS = GROUP.text("mesh.command.param.policy.tps", "Minimum TPS a candidate must report; 0 ignores TPS");
    public static final TextKey COMMAND_POLICY_QUEUE = GROUP.text("mesh.command.param.policy.queue", "Hold travelers when every candidate is full");

    private MeshMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
