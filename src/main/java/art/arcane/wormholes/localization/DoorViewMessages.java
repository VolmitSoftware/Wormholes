package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

/**
 * Message keys owned by the doors lane. Every id starts with "doorview.". Add keys here and translate
 * them inside the "lane:doors" block of every languages/*.toml file.
 */
public final class DoorViewMessages {
    private static final MessageGroup GROUP = new MessageGroup("doorview.");

    public static final TextKey TOGGLE_INHERIT = GROUP.text("doorview.toggle.inherit",
            "&8[&6Wormholes&8] &7Projection for this door: &finherit&7.");
    public static final TextKey TOGGLE_ON = GROUP.text("doorview.toggle.on",
            "&8[&6Wormholes&8] &aProjection for this door: on.");
    public static final TextKey TOGGLE_OFF = GROUP.text("doorview.toggle.off",
            "&8[&6Wormholes&8] &eProjection for this door: off.");
    public static final TextKey DISABLED = GROUP.text("doorview.disabled",
            "&8[&6Wormholes&8] &cDoor projection is disabled in wormholes.toml.");
    public static final TextKey STATE_INHERIT = GROUP.text("doorview.state.inherit", "inherit");
    public static final TextKey STATE_ON = GROUP.text("doorview.state.on", "on");
    public static final TextKey STATE_OFF = GROUP.text("doorview.state.off", "off");
    public static final LinesKey MENU_PROJECTION = GROUP.lines("doorview.menu.projection",
            "&b&lProjection: {state}&r",
            "&7Show the destination through this doorway.",
            "&eLeft-click to cycle");

    private DoorViewMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
