package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.ServerConnectService;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

@Director(name = "server", descriptionKey = "command.help.server", description = "Cross-server travel: connect to, list, and exchange linked servers")
public class CommandServer {
    @Director(name = "connect", sync = true, descriptionKey = "command.help.server.connect", description = "Transfer yourself to a linked server (shorthand: /wh server <name>)")
    public void connect(@Param(name = "sender", contextual = true) CommandSender sender,
                        @Param(name = "name", descriptionKey = "command.help.server.connect.name", description = "Linked server name (see /wh server list)", customHandler = ServerNameHandler.class) String name) {
        connectAndReport(sender, name);
    }

    @Director(name = "export", sync = true, descriptionKey = "command.help.server.export", description = "Export this server as a code other servers can import")
    public void export(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        if (Wormholes.importExportService == null) {
            send(sender, WormholesMessages.NETWORK_NOT_INITIALIZED);
            return;
        }
        Wormholes.importExportService.exportServerToChat(sender);
    }

    @Director(name = "import", sync = true, descriptionKey = "command.help.server.import", description = "Import a server (WHS1.) or portal (WHP5.) code exported by another server")
    public void importCode(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "code", descriptionKey = "command.help.server.import.code", description = "Code from the other server's export") String code) {
        importAndReport(sender, code);
    }

    @Director(name = "list", sync = true, descriptionKey = "command.help.server.list", description = "List linked servers and their connection state")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            send(sender, WormholesMessages.NETWORK_NOT_INITIALIZED);
            return;
        }
        List<NetworkConfig.PeerEntry> peers = network.peers();
        if (peers.isEmpty()) {
            send(sender, WormholesMessages.SERVER_LIST_EMPTY);
            return;
        }
        send(sender, WormholesMessages.SERVER_LIST_HEADER);
        peers.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        for (NetworkConfig.PeerEntry peer : peers) {
            boolean ready = network.isPeerReady(peer.name);
            send(sender, WormholesMessages.SERVER_LIST_ENTRY, WormholesLocalization.args(
                    MessageArgument.untrusted("server", peer.name),
                    MessageArgument.trusted("state", ready ? "<green>ready" : "<red>offline"),
                    MessageArgument.untrusted("address", gameAddress(peer))));
        }
    }

    @Director(name = "remove", sync = true, descriptionKey = "command.help.server.remove", description = "Forget a linked server (deletes its route and trusted key)")
    public void remove(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "name", descriptionKey = "command.help.server.remove.name", description = "Linked server name to forget", customHandler = ServerNameHandler.class) String name) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            send(sender, WormholesMessages.NETWORK_NOT_INITIALIZED);
            return;
        }
        String resolved = ServerConnectService.resolveName(network, name);
        if (resolved == null || !network.removePeer(resolved)) {
            send(sender, WormholesMessages.SERVER_UNKNOWN, args("server", name));
            return;
        }
        if (Wormholes.remotePortalRegistry != null) {
            Wormholes.remotePortalRegistry.removePeer(resolved);
        }
        send(sender, WormholesMessages.SERVER_REMOVED, args("server", resolved));
    }

    public static void importAndReport(CommandSender sender, String code) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        if (Wormholes.importExportService == null) {
            send(sender, WormholesMessages.NETWORK_NOT_INITIALIZED);
            return;
        }
        Wormholes.importExportService.importCode(sender, null, code);
    }

    public static void connectAndReport(CommandSender sender, String requestedName) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, WormholesMessages.SERVER_ONLY_PLAYERS);
            return;
        }
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            send(sender, WormholesMessages.NETWORK_NOT_INITIALIZED);
            return;
        }
        String resolved = ServerConnectService.resolveName(network, requestedName);
        if (resolved == null) {
            send(sender, WormholesMessages.SERVER_UNKNOWN, args("server", requestedName));
            return;
        }
        send(sender, WormholesMessages.SERVER_CONNECTING, args("server", resolved));
        ServerConnectService.Result result = ServerConnectService.connect(
                network, player, resolved, Wormholes.settings.getNetwork().transferMode);
        switch (result) {
            case SENT -> {
            }
            case NOT_READY -> send(sender, WormholesMessages.SERVER_NOT_READY, args("server", resolved));
            case UNKNOWN_SERVER -> send(sender, WormholesMessages.SERVER_UNKNOWN, args("server", resolved));
            case TRANSFER_FAILED -> send(sender, WormholesMessages.SERVER_CONNECT_FAILED, args("server", resolved));
        }
    }

    public static List<String> knownServerNames() {
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            return List.of();
        }
        KList<String> names = new KList<>();
        for (NetworkConfig.PeerEntry peer : network.peers()) {
            if (peer.name != null && !peer.name.isBlank()) {
                names.add(peer.name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static String gameAddress(NetworkConfig.PeerEntry peer) {
        String host = peer.publicHost != null && !peer.publicHost.isBlank() ? peer.publicHost : peer.host;
        return (host == null || host.isBlank() ? "?" : host) + ":" + peer.publicPort;
    }

    private static MessageArgs args(String name, Object value) {
        return WormholesLocalization.args(MessageArgument.untrusted(name, value));
    }

    private static void send(CommandSender sender, TextKey key) {
        send(sender, key, MessageArgs.empty());
    }

    private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(key, arguments));
    }

    public static final class ServerNameHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            return new KList<>(knownServerNames());
        }

        @Override
        public String toString(String value) {
            return value == null ? "" : value;
        }

        @Override
        public String parse(String input, boolean force) throws DirectorParsingException {
            if (input == null || input.trim().isEmpty()) {
                throw new DirectorParsingException(Wormholes.text().plain(WormholesMessages.SERVER_NAME_EMPTY));
            }
            return input.trim();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}
