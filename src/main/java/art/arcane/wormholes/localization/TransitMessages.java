package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

/**
 * Message keys owned by the transit lane. Every id starts with "transit.". Add keys here and translate
 * them inside the "lane:transit" block of every languages/*.toml file.
 */
public final class TransitMessages {
    private static final MessageGroup GROUP = new MessageGroup("transit.");

    public static final TextKey DENIED_MEMBRANE = GROUP.text("transit.denied.membrane",
        "{portal} can only be entered from the front.");
    public static final TextKey DENIED_CONVOY_SIZE = GROUP.text("transit.denied.convoy_size",
        "Your rig has {count} entities; the limit is {value}.");
    public static final TextKey DENIED_CONVOY_FIT = GROUP.text("transit.denied.convoy_fit",
        "Your rig does not fit through {portal}.");
    public static final TextKey DENIED_CONVOY_MEMBER = GROUP.text("transit.denied.convoy_member",
        "Part of your rig may not use {portal}.");
    public static final TextKey CONVOY_WAITING = GROUP.text("transit.convoy.waiting",
        "Bring your whole rig through {portal}.");
    public static final TextKey CONVOY_FAILED = GROUP.text("transit.convoy.failed",
        "Convoy transfer failed: {reason}.");
    public static final TextKey BOUNCED = GROUP.text("transit.bounced",
        "{portal} pushed you back.");

    public static final LinesKey MENU_ENTRY = GROUP.lines("transit.menu.entry",
        "&b&lTransit&r",
        "&7Momentum, orientation, membrane, bounce, and cues.",
        "&eLeft-click");
    public static final LinesKey MENU_MOMENTUM = GROUP.lines("transit.menu.momentum",
        "&6&lMomentum: {mode}&r",
        "&7Factor {value}. How exit speed is derived from entry speed.",
        "&eLeft-click to cycle the mode",
        "&eRight-click to set the factor");
    public static final LinesKey MENU_ORIENTATION = GROUP.lines("transit.menu.orientation",
        "&6&lOrientation: {mode}&r",
        "&7Which way travelers face when they arrive.",
        "&eLeft-click to cycle");
    public static final LinesKey MENU_MEMBRANE = GROUP.lines("transit.menu.membrane",
        "&6&lMembrane: {state}&r",
        "&7Only the front side lets travelers in; the back side pushes them away.",
        "&eLeft-click to toggle");
    public static final LinesKey MENU_BOUNCE = GROUP.lines("transit.menu.bounce",
        "&6&lBounce: {state}&r",
        "&7Reflect travelers instead of moving them.",
        "&eLeft-click to toggle");
    public static final TextKey MENU_PROMPT = GROUP.text("transit.menu.prompt",
        "&bType the new value in chat (or '{cancel}'):");
    public static final LinesKey MENU_PROFILE = GROUP.lines("transit.menu.profile",
        "&6&lTransition cues&r",
        "&7Threshold {mode}",
        "&7Arrival {state}",
        "&eLeft-click: threshold effect, Right-click: arrival sound",
        "&eShift + Left-click: mask ticks");

    private TransitMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
