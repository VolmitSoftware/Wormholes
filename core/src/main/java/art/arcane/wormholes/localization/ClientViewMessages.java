package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

public final class ClientViewMessages {
    private static final MessageGroup GROUP = new MessageGroup("clientview.");

    public static final TextKey COMMAND_CLIENTVIEW = GROUP.text("clientview.command.help.clientview",
        "ClientView sessions for players running the Wormholes mod");
    public static final TextKey COMMAND_STATUS = GROUP.text("clientview.command.help.status", "List every ClientView session");
    public static final TextKey COMMAND_ON = GROUP.text("clientview.command.help.on", "Offer ClientView to modded clients again");
    public static final TextKey COMMAND_OFF = GROUP.text("clientview.command.help.off", "Return every ClientView player to vanilla projection");
    public static final TextKey COMMAND_RESET = GROUP.text("clientview.command.help.reset", "Restart one player's ClientView stream");
    public static final TextKey COMMAND_RESET_PLAYER = GROUP.text("clientview.command.help.reset.player", "Online player name");

    public static final TextKey HEADER = GROUP.text("clientview.command.header",
        "&6ClientView &7runtime {state}, configured {mode}, {count} sessions");
    public static final TextKey ROW = GROUP.text("clientview.command.row", "&e{name} &7{state} &8| &7{value}");
    public static final TextKey NONE = GROUP.text("clientview.command.none", "&7No player has a ClientView session.");
    public static final TextKey ENABLED = GROUP.text("clientview.command.enabled", "&aClientView is offered again. Configured: {mode}.");
    public static final TextKey DISABLED = GROUP.text("clientview.command.disabled",
        "&cClientView is off. Every session returned to vanilla projection.");
    public static final TextKey RESET = GROUP.text("clientview.command.reset", "&aRestarting the ClientView stream for {name}.");
    public static final TextKey NO_SESSION = GROUP.text("clientview.command.no_session", "&c{name} has no active ClientView session.");
    public static final TextKey PLAYER_MISSING = GROUP.text("clientview.command.player_missing", "&cNo online player is named {name}.");

    private ClientViewMessages() {
    }

    public static List<MessageKey> keys() {
        return GROUP.keys();
    }
}
