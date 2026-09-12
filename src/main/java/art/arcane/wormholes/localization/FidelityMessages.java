package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

/**
 * Message keys owned by the view lane. Every id starts with "fidelity.". Add keys here and translate
 * them inside the "lane:view" block of every languages/*.toml file.
 */
public final class FidelityMessages {
    private static final MessageGroup GROUP = new MessageGroup("fidelity.");

    public static final LinesKey MENU_ENTRY = GROUP.lines("fidelity.menu.entry",
        "&b&lFidelity&r",
        "&7Atmosphere, acoustics, detail and block entities for this portal.",
        "&eLeft-click");
    public static final LinesKey MENU_PLACARD = GROUP.lines("fidelity.menu.placard",
        "&b&lProjection fidelity&r",
        "&7Per-portal channels layered on the projection.");
    public static final TextKey MENU_ATMOSPHERE = GROUP.text("fidelity.menu.atmosphere", "&bAtmosphere: &f{mode}");
    public static final TextKey MENU_ACOUSTICS = GROUP.text("fidelity.menu.acoustics", "&bAcoustics: &f{mode}");
    public static final TextKey MENU_LOD = GROUP.text("fidelity.menu.lod", "&bDetail: &f{mode}");
    public static final TextKey MENU_BLOCK_ENTITIES = GROUP.text("fidelity.menu.block_entities", "&bBlock entities: &f{state}");
    public static final LinesKey MENU_HINT = GROUP.lines("fidelity.menu.hint",
        "&8Click to cycle.",
        "&8Shift-click to use the server default.");
    public static final LinesKey MENU_BACK = GROUP.lines("fidelity.menu.back",
        "&e&lBack&r",
        "&7Return to the portal menu.");
    public static final TextKey MENU_DEFAULT = GROUP.text("fidelity.menu.default", "default");
    public static final TextKey BEDROCK_PROFILE = GROUP.text("fidelity.bedrock.profile",
        "Bedrock profile active for {count} viewers");

    private FidelityMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
