package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

/**
 * Message keys owned by the nexus lane. Every id starts with "atlas.". Add keys here and translate
 * them inside the "lane:nexus" block of every languages/*.toml file.
 */
public final class AtlasMessages {
    private static final MessageGroup GROUP = new MessageGroup("atlas.");

    public static final TextKey TITLE = GROUP.text("atlas.title", "&8Atlas &7- &f{count} portals");
    public static final TextKey ROW = GROUP.text("atlas.row", "&f{portal} &8| &7{destination} &8| &b{state}");
    public static final TextKey EMPTY = GROUP.text("atlas.empty",
            "&8[&6Wormholes&8] &7No portals discovered yet.");
    public static final TextKey DISABLED = GROUP.text("atlas.disabled",
            "&8[&6Wormholes&8] &cThe atlas is turned off on this server.");
    public static final TextKey ONLY_PLAYERS = GROUP.text("atlas.only_players",
            "&8[&6Wormholes&8] &cOnly players have an atlas.");
    public static final TextKey NO_PERMISSION = GROUP.text("atlas.no_permission",
            "&8[&6Wormholes&8] &cYou do not have permission to open the atlas.");
    public static final TextKey FAVORITE_ADDED = GROUP.text("atlas.favorite.added",
            "&8[&6Wormholes&8] &aPinned {portal}.");
    public static final TextKey FAVORITE_REMOVED = GROUP.text("atlas.favorite.removed",
            "&8[&6Wormholes&8] &aUnpinned {portal}.");
    public static final TextKey FAVORITE_FULL = GROUP.text("atlas.favorite.full",
            "&8[&6Wormholes&8] &cYou already pinned {count} portals.");
    public static final TextKey GUIDE_BEARING = GROUP.text("atlas.guide.bearing", "&b{portal} &f{value}");
    public static final TextKey GUIDE_SET = GROUP.text("atlas.guide.set",
            "&8[&6Wormholes&8] &aGuiding to {portal}.");
    public static final TextKey GUIDE_CLEARED = GROUP.text("atlas.guide.cleared",
            "&8[&6Wormholes&8] &aGuide off.");
    public static final TextKey GUIDE_UNKNOWN = GROUP.text("atlas.guide.unknown",
            "&8[&6Wormholes&8] &cNo portal named {portal} in your atlas.");
    public static final LinesKey USAGE = GROUP.lines("atlas.usage",
            "&8[&6Wormholes&8] &7/atlas &8- &7open your portal list",
            "&8[&6Wormholes&8] &7/atlas favorites &8- &7only pinned portals",
            "&8[&6Wormholes&8] &7/atlas recents &8- &7portals you used lately",
            "&8[&6Wormholes&8] &7/atlas guide \\<portal> &8- &7show a bearing; /atlas guide off stops it");

    public static final LinesKey MENU_ROW = GROUP.lines("atlas.menu.row",
            "&6{portal}",
            "&7{world} &8- &f{destination}",
            "&b{state}",
            "",
            "&eLeft-click to dial. &eRight-click to pin. &eShift-left-click to guide.");
    public static final LinesKey MENU_FAVORITES = GROUP.lines("atlas.menu.favorites",
            "&d&lPinned only: {state}&r",
            "&7Show only the portals you pinned.",
            "&eLeft-click");
    public static final LinesKey MENU_RECENTS = GROUP.lines("atlas.menu.recents",
            "&b&lRecent only: {state}&r",
            "&7Show only the portals you used lately.",
            "&eLeft-click");
    public static final LinesKey MENU_GUIDE = GROUP.lines("atlas.menu.guide",
            "&a&lGuide: {portal}&r",
            "&7Show a bearing to the guided portal on the action bar.",
            "&eLeft-click to stop guiding");

    private AtlasMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
