package art.arcane.wormholes.commands;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.ClientViewReplies;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.render.client.session.ClientViewSessionRegistry;
import art.arcane.wormholes.render.clientview.BukkitClientView;
import art.arcane.wormholes.render.clientview.ClientViewObserver;
import art.arcane.wormholes.service.WormholesAudience;

@Director(name = "clientview", descriptionKey = "clientview.command.help.clientview",
        description = "ClientView sessions for players running the Wormholes mod")
public class CommandClientView {
    static final String PERMISSION = "wormholes.admin";

    @Director(name = "status", sync = true, descriptionKey = "clientview.command.help.status",
            description = "List every ClientView session")
    public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
        BukkitClientView clientView = clientView(sender);
        if (clientView == null) {
            return;
        }
        ClientViewSessionRegistry<ClientViewObserver, BlockData> registry = clientView.registry();
        for (ClientViewReplies.Reply reply : ClientViewReplies.status(registry.runtimeEnabled(), registry.options().enabled(),
                registry.stats(), CommandClientView::playerName)) {
            send(sender, reply);
        }
    }

    @Director(name = "on", sync = true, descriptionKey = "clientview.command.help.on",
            description = "Offer ClientView to modded clients again")
    public void on(@Param(name = "sender", contextual = true) CommandSender sender) {
        BukkitClientView clientView = clientView(sender);
        if (clientView == null) {
            return;
        }
        clientView.runtimeEnabled(true);
        send(sender, ClientViewReplies.enabled(clientView.registry().options().enabled()));
    }

    @Director(name = "off", sync = true, descriptionKey = "clientview.command.help.off",
            description = "Return every ClientView player to vanilla projection")
    public void off(@Param(name = "sender", contextual = true) CommandSender sender) {
        BukkitClientView clientView = clientView(sender);
        if (clientView == null) {
            return;
        }
        clientView.runtimeEnabled(false);
        send(sender, ClientViewReplies.disabled());
    }

    @Director(name = "reset", sync = true, descriptionKey = "clientview.command.help.reset",
            description = "Restart one player's ClientView stream")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "player", descriptionKey = "clientview.command.help.reset.player",
                              description = "Online player name") String name) {
        BukkitClientView clientView = clientView(sender);
        if (clientView == null) {
            return;
        }
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            send(sender, ClientViewReplies.playerMissing(name));
            return;
        }
        send(sender, ClientViewReplies.reset(player.getName(), clientView.reset(player)));
    }

    private static String playerName(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player == null ? null : player.getName();
    }

    private static BukkitClientView clientView(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION)) {
            WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, WormholesMessages.COMMAND_NO_PERMISSION, MessageArgs.empty()));
            return null;
        }
        ProjectionManager projection = Wormholes.projectionManager;
        return projection == null ? null : projection.clientView();
    }

    private static void send(CommandSender sender, ClientViewReplies.Reply reply) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, reply.key(), reply.arguments()));
    }
}
