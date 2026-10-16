package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

public final class MenuMessages {
    private static final MessageGroup GROUP = new MessageGroup("menu.");

    public static final TextKey TITLE_PORTAL = GROUP.text("menu.native.title.portal", "Portal");
    public static final TextKey TITLE_MODE = GROUP.text("menu.native.title.mode", "Portal Mode");
    public static final TextKey TITLE_ORIENTATION = GROUP.text("menu.native.title.orientation", "Orientation");
    public static final TextKey TITLE_GATEWAY = GROUP.text("menu.native.title.gateway", "Gateway Pairing");
    public static final TextKey TITLE_SETTINGS = GROUP.text("menu.native.title.settings", "Settings");
    public static final TextKey TITLE_ADVANCED = GROUP.text("menu.native.title.advanced", "Advanced Stream Tuning");
    public static final TextKey TITLE_BLACKOUT = GROUP.text("menu.native.title.blackout", "Blackout Color");
    public static final TextKey TITLE_AMBIENT = GROUP.text("menu.native.title.ambient", "Ambient Color");
    public static final TextKey TITLE_SKIN = GROUP.text("menu.native.title.skin", "Surface Skin");
    public static final TextKey TITLE_COST = GROUP.text("menu.native.title.cost", "Travel Cost");
    public static final TextKey TITLE_EXTENSIONS = GROUP.text("menu.native.title.extensions", "More Settings");
    public static final TextKey TITLE_DESTINATIONS = GROUP.text("menu.native.title.destinations", "Destinations");
    public static final TextKey TITLE_SELECT = GROUP.text("menu.native.title.select", "Choose a Portal");
    public static final TextKey TITLE_RTP = GROUP.text("menu.native.title.rtp", "Random Teleport");
    public static final TextKey TITLE_RTP_DESTINATION = GROUP.text("menu.native.title.rtp_destination", "Random Destination");
    public static final TextKey TITLE_RTP_BIOME = GROUP.text("menu.native.title.rtp_biome", "Target Biome");
    public static final TextKey TITLE_RTP_LANDING = GROUP.text("menu.native.title.rtp_landing", "Landing");
    public static final TextKey TITLE_RTP_ROUTING = GROUP.text("menu.native.title.rtp_routing", "Routing");
    public static final TextKey TITLE_RTP_EFFECTS = GROUP.text("menu.native.title.rtp_effects", "Effects");
    public static final TextKey TITLE_NETWORK = GROUP.text("menu.native.title.network", "Network");
    public static final TextKey TITLE_DIAL = GROUP.text("menu.native.title.dial", "Dial");
    public static final TextKey TITLE_RULES = GROUP.text("menu.native.title.rules", "Rules");
    public static final TextKey TITLE_RULES_LIST = GROUP.text("menu.native.title.rules_list", "Rule List");
    public static final TextKey TITLE_RULE = GROUP.text("menu.native.title.rule", "Rule");
    public static final TextKey TITLE_TEMPLATES = GROUP.text("menu.native.title.templates", "Rule Templates");
    public static final TextKey TITLE_ACCESS = GROUP.text("menu.native.title.access", "Access");
    public static final TextKey TITLE_TRANSIT = GROUP.text("menu.native.title.transit", "Transit");
    public static final TextKey TITLE_FIDELITY = GROUP.text("menu.native.title.fidelity", "Fidelity");
    public static final TextKey TITLE_ATLAS = GROUP.text("menu.native.title.atlas", "Atlas");
    public static final TextKey TITLE_DOOR = GROUP.text("menu.native.title.door", "Door Access");
    public static final TextKey TITLE_LANGUAGE = GROUP.text("menu.native.title.language", "Language");

    public static final TextKey CONTEXT_PORTAL = GROUP.text("menu.native.context.portal", "{portal} - {type}");
    public static final TextKey CONTEXT_DOOR = GROUP.text("menu.native.context.door", "{kind} door");
    public static final TextKey CONTEXT_NETWORK = GROUP.text("menu.native.context.network", "{name}");

    public static final TextKey CHROME_BACK = GROUP.text("menu.native.chrome.back", "Back");
    public static final TextKey CHROME_CLOSE = GROUP.text("menu.native.chrome.close", "Close");
    public static final TextKey CHROME_APPLY = GROUP.text("menu.native.chrome.apply", "Apply");
    public static final TextKey CHROME_CONFIRM = GROUP.text("menu.native.chrome.confirm", "Confirm");
    public static final TextKey CHROME_CANCEL = GROUP.text("menu.native.chrome.cancel", "Cancel");
    public static final TextKey CHROME_PREVIOUS_PAGE = GROUP.text("menu.native.chrome.previous_page", "Previous page");
    public static final TextKey CHROME_NEXT_PAGE = GROUP.text("menu.native.chrome.next_page", "Next page");
    public static final TextKey CHROME_PAGE = GROUP.text("menu.native.chrome.page", "Page {page} / {pages}");
    public static final TextKey CHROME_USE_HELD_ITEM = GROUP.text("menu.native.chrome.use_held_item", "Use held item");
    public static final TextKey CHROME_AIM = GROUP.text("menu.native.chrome.aim", "Aim");
    public static final TextKey CHROME_TIMEOUT = GROUP.text("menu.native.chrome.timeout", "The server did not answer.");

    public static final TextKey ACTION_ROTATE = GROUP.text("menu.native.action.rotate", "Rotate");
    public static final TextKey ACTION_ROTATE_CLOCKWISE = GROUP.text("menu.native.action.rotate_clockwise", "Rotate clockwise");
    public static final TextKey ACTION_ROTATE_COUNTERCLOCKWISE = GROUP.text("menu.native.action.rotate_counterclockwise", "Rotate counterclockwise");
    public static final TextKey ACTION_RESET = GROUP.text("menu.native.action.reset", "Reset");
    public static final TextKey ACTION_CLEAR = GROUP.text("menu.native.action.clear", "Clear");
    public static final TextKey ACTION_COLORS = GROUP.text("menu.native.action.colors", "Colors");
    public static final TextKey ACTION_ADVANCED = GROUP.text("menu.native.action.advanced", "Advanced");
    public static final TextKey ACTION_REROLL = GROUP.text("menu.native.action.reroll", "Reroll");
    public static final TextKey ACTION_REMOVE = GROUP.text("menu.native.action.remove", "Remove");
    public static final TextKey ACTION_FAVORITE = GROUP.text("menu.native.action.favorite", "Favorite");
    public static final TextKey ACTION_GUIDE = GROUP.text("menu.native.action.guide", "Guide");
    public static final TextKey ACTION_WHITELIST = GROUP.text("menu.native.action.whitelist", "Whitelist");
    public static final TextKey ACTION_BLACKLIST = GROUP.text("menu.native.action.blacklist", "Blacklist");
    public static final TextKey ACTION_USE_DEFAULT = GROUP.text("menu.native.action.use_default", "Use server default");

    public static final LinesKey CONFIRM_DELETE_PORTAL = GROUP.lines("menu.native.confirm.delete_portal",
        "Delete {portal}?",
        "This removes the portal and unlinks portals that lead to it.",
        "This cannot be undone.");
    public static final TextKey CONFIRM_DELETE_PORTAL_ACCEPT = GROUP.text("menu.native.confirm.delete_portal.accept", "Delete");
    public static final LinesKey CONFIRM_RTP_RESET = GROUP.lines("menu.native.confirm.rtp_reset",
        "Reset {portal}?",
        "Every random teleport setting returns to its default.");
    public static final TextKey CONFIRM_RTP_RESET_ACCEPT = GROUP.text("menu.native.confirm.rtp_reset.accept", "Reset");
    public static final LinesKey CONFIRM_RULES_TEMPLATE = GROUP.lines("menu.native.confirm.rules_template",
        "Load {name}?",
        "The template replaces every rule on this portal.");
    public static final TextKey CONFIRM_RULES_TEMPLATE_ACCEPT = GROUP.text("menu.native.confirm.rules_template.accept", "Load");
    public static final LinesKey CONFIRM_NEXUS_LEAVE = GROUP.lines("menu.native.confirm.nexus_leave",
        "Leave {name}?",
        "This portal stops dialing and receiving through the network.");
    public static final TextKey CONFIRM_NEXUS_LEAVE_ACCEPT = GROUP.text("menu.native.confirm.nexus_leave.accept", "Leave");

    public static final TextKey FEEDBACK_REFRESHED = GROUP.text("menu.native.feedback.refreshed", "The menu changed and was refreshed.");
    public static final TextKey FEEDBACK_UNAVAILABLE = GROUP.text("menu.native.feedback.unavailable", "That option is not available right now.");
    public static final TextKey FEEDBACK_INVALID_VALUE = GROUP.text("menu.native.feedback.invalid_value", "That value is not allowed.");
    public static final TextKey FEEDBACK_SAVE_FAILED = GROUP.text("menu.native.feedback.save_failed", "The change could not be saved.");
    public static final TextKey FEEDBACK_TIMEOUT = GROUP.text("menu.native.feedback.timeout", "The server took too long to answer.");

    public static final TextKey PLACEHOLDER_NAME = GROUP.text("menu.native.placeholder.name", "Portal name");
    public static final TextKey PLACEHOLDER_CODE = GROUP.text("menu.native.placeholder.code", "Invite code");
    public static final TextKey PLACEHOLDER_BLOCK_STATE = GROUP.text("menu.native.placeholder.block_state", "Block state, for example minecraft:glass");
    public static final TextKey PLACEHOLDER_AMOUNT = GROUP.text("menu.native.placeholder.amount", "Amount");
    public static final TextKey PLACEHOLDER_PLAYER = GROUP.text("menu.native.placeholder.player", "Player name");
    public static final TextKey PLACEHOLDER_KEY = GROUP.text("menu.native.placeholder.key", "Permission key");
    public static final TextKey PLACEHOLDER_GROUP = GROUP.text("menu.native.placeholder.group", "Group name");
    public static final TextKey PLACEHOLDER_NETWORK = GROUP.text("menu.native.placeholder.network", "Network name");
    public static final TextKey PLACEHOLDER_ADDRESS = GROUP.text("menu.native.placeholder.address", "Address");
    public static final TextKey PLACEHOLDER_RULE_ID = GROUP.text("menu.native.placeholder.rule_id", "Rule id");
    public static final TextKey PLACEHOLDER_LINE = GROUP.text("menu.native.placeholder.line", "Rule line");
    public static final TextKey PLACEHOLDER_FACTOR = GROUP.text("menu.native.placeholder.factor", "Factor");
    public static final TextKey PLACEHOLDER_SOUND = GROUP.text("menu.native.placeholder.sound", "Sound id");
    public static final TextKey PLACEHOLDER_TICKS = GROUP.text("menu.native.placeholder.ticks", "Ticks");
    public static final TextKey PLACEHOLDER_SCALE = GROUP.text("menu.native.placeholder.scale", "Scale");
    public static final TextKey PLACEHOLDER_SEARCH = GROUP.text("menu.native.placeholder.search", "Search");

    public static final TextKey TRANSIT_FACTOR = GROUP.text("menu.native.transit.factor", "Momentum factor");
    public static final TextKey TRANSIT_THRESHOLD_EFFECT = GROUP.text("menu.native.transit.threshold_effect", "Threshold effect");
    public static final TextKey TRANSIT_ARRIVAL_SOUND = GROUP.text("menu.native.transit.arrival_sound", "Arrival sound");
    public static final TextKey TRANSIT_MASK_TICKS = GROUP.text("menu.native.transit.mask_ticks", "Mask ticks");
    public static final TextKey TRANSIT_SCALE_MIN = GROUP.text("menu.native.transit.scale_min", "Minimum scale");
    public static final TextKey TRANSIT_SCALE_MAX = GROUP.text("menu.native.transit.scale_max", "Maximum scale");

    public static final TextKey NEXUS_COMPARATOR = GROUP.text("menu.native.nexus.comparator", "Comparator output");

    public static final LinesKey LIST_PAGE = GROUP.lines("menu.native.list.page", "&b&lPage {page}/{pages}&r", "&7{count} entries");

    private MenuMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
