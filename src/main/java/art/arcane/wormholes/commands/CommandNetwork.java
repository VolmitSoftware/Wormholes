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
import art.arcane.wormholes.localization.MeshMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.network.Handshake;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.mesh.DestinationCandidate;
import art.arcane.wormholes.network.mesh.DestinationPolicy;
import art.arcane.wormholes.network.mesh.MeshPortalExtension;
import art.arcane.wormholes.network.mesh.PeerQuarantineStore;
import art.arcane.wormholes.network.mesh.SelectionStrategy;
import art.arcane.wormholes.network.mesh.VersionReport;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Director(name = "network", descriptionKey = "command.help.network", description = "Cross-server wormhole network")
public class CommandNetwork {
    @Director(name = "import", sync = true, descriptionKey = "command.help.network.import", description = "Same as /wh server import; accepts a server or portal code")
    public void importCode(@Param(name = "sender", contextual = true) CommandSender sender,
                           @Param(name = "code", descriptionKey = "command.help.network.import.code", description = "Server or portal code from the other server's export") String code) {
        CommandServer.importAndReport(sender, code);
    }

    @Director(name = "status", sync = true, descriptionKey = "command.help.network.status", description = "Show peer connection status")
    public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        NetworkManager network = Wormholes.networkManager;
        NetworkConfig config = Wormholes.settings.getNetwork();
        if (network == null || !config.enabled) {
            send(sender, WormholesMessages.NETWORK_DISABLED);
            return;
        }
        if (!network.isRunning()) {
            send(sender, WormholesMessages.NETWORK_NOT_RUNNING);
            return;
        }
        if (config.listenEnabled) {
            send(sender, WormholesMessages.NETWORK_LISTENING, args(
                    MessageArgument.untrusted("server", network.getLocalName()),
                    MessageArgument.untrusted("address", network.getListenAddress())
            ));
        } else {
            send(sender, WormholesMessages.NETWORK_OUTBOUND_ONLY,
                    args(MessageArgument.untrusted("server", network.getLocalName())));
        }
        send(sender, WormholesMessages.NETWORK_PUBLIC_KEY,
                args(MessageArgument.untrusted("fingerprint", network.getPublicKeyFingerprint())));
        List<NetworkManager.PeerStatus> statuses = network.status();
        if (statuses.isEmpty()) {
            send(sender, WormholesMessages.NETWORK_NO_ROUTES);
            return;
        }
        for (NetworkManager.PeerStatus status : statuses) {
            String stateColor = switch (status.state()) {
                case "CONNECTED" -> "<green>";
                case "CONNECTING", "WAITING" -> "<yellow>";
                default -> "<red>";
            };
            String rtt = status.rttMillis() >= 0 ? " " + status.rttMillis() + "ms" : "";
            send(sender, WormholesMessages.NETWORK_PEER, args(
                    MessageArgument.untrusted("server", status.name()),
                    MessageArgument.trusted("state", stateColor + escapeState(status.state())),
                    MessageArgument.untrusted("address", status.address()),
                    MessageArgument.untrusted("rtt", rtt)
            ));
            if (status.lastError() != null && !status.state().equals("CONNECTED")) {
                send(sender, WormholesMessages.NETWORK_LAST_ATTEMPT,
                        args(MessageArgument.untrusted("error", status.lastError())));
            }
        }
        if (hasUnconnectedPeer(statuses)) {
            printDiagnostics(sender, network);
        }
    }

    @Director(name = "doctor", sync = true, descriptionKey = "command.help.network.doctor", description = "Explain why network peers are not connecting")
    public void doctor(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return;
        }
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            send(sender, WormholesMessages.NETWORK_NOT_INITIALIZED);
            return;
        }
        printDiagnostics(sender, network);
    }

    @Director(name = "members", sync = true, descriptionKey = "mesh.command.help.members", description = "List every network member with link state and key fingerprint")
    public void members(@Param(name = "sender", contextual = true) CommandSender sender) {
        NetworkManager network = adminNetwork(sender);
        if (network == null) {
            return;
        }
        List<NetworkConfig.PeerEntry> peers = network.peers();
        if (peers.isEmpty()) {
            send(sender, MeshMessages.MEMBERS_NONE);
            return;
        }
        peers.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name));
        for (NetworkConfig.PeerEntry peer : peers) {
            byte[] key = network.trustedKey(peer.name);
            String state = network.isPeerReady(peer.name) ? "<green>ready" : "<red>offline";
            send(sender, MeshMessages.MEMBERS_ROW, args(
                    MessageArgument.untrusted("server", peer.name),
                    MessageArgument.trusted("state", state),
                    MessageArgument.untrusted("fingerprint", key == null ? "untrusted" : Handshake.fingerprint(key))));
        }
    }

    @Director(name = "pending", sync = true, descriptionKey = "mesh.command.help.pending", description = "List introduced servers waiting for approval")
    public void pending(@Param(name = "sender", contextual = true) CommandSender sender) {
        NetworkManager network = meshNetwork(sender);
        if (network == null) {
            return;
        }
        List<PeerQuarantineStore.Entry> entries = network.quarantine().all();
        if (entries.isEmpty()) {
            send(sender, MeshMessages.INTRODUCTIONS_NONE);
            return;
        }
        entries.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
        for (PeerQuarantineStore.Entry entry : entries) {
            send(sender, MeshMessages.PENDING, args(
                    MessageArgument.untrusted("server", entry.name()),
                    MessageArgument.untrusted("name", entry.introducer())));
        }
    }

    @Director(name = "accept", sync = true, descriptionKey = "mesh.command.help.accept", description = "Trust an introduced server and save its route")
    public void accept(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "server", descriptionKey = "mesh.command.param.accept.server", description = "Introduced server name (see /wh network pending)", customHandler = PendingServerHandler.class) String server) {
        NetworkManager network = meshNetwork(sender);
        if (network == null) {
            return;
        }
        if (!network.mesh().acceptQuarantined(server)) {
            send(sender, MeshMessages.INTRODUCTIONS_UNKNOWN, args(MessageArgument.untrusted("server", server)));
            return;
        }
        if (!network.isRunning()) {
            network.start();
        }
        send(sender, MeshMessages.ACCEPTED, args(MessageArgument.untrusted("server", server)));
    }

    @Director(name = "reject", sync = true, descriptionKey = "mesh.command.help.reject", description = "Discard an introduced server's pending introduction")
    public void reject(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "server", descriptionKey = "mesh.command.param.reject.server", description = "Introduced server name to reject", customHandler = PendingServerHandler.class) String server) {
        NetworkManager network = meshNetwork(sender);
        if (network == null) {
            return;
        }
        if (!network.mesh().rejectQuarantined(server)) {
            send(sender, MeshMessages.INTRODUCTIONS_UNKNOWN, args(MessageArgument.untrusted("server", server)));
            return;
        }
        send(sender, MeshMessages.REJECTED, args(MessageArgument.untrusted("server", server)));
    }

    @Director(name = "versions", sync = true, descriptionKey = "mesh.command.help.versions", description = "Show each peer's protocol, plugin version and capability set")
    public void versions(@Param(name = "sender", contextual = true) CommandSender sender) {
        NetworkManager network = adminNetwork(sender);
        if (network == null) {
            return;
        }
        List<VersionReport.Row> rows = VersionReport.build(network);
        if (rows.isEmpty()) {
            send(sender, MeshMessages.VERSIONS_NONE);
            return;
        }
        for (VersionReport.Row row : rows) {
            send(sender, MeshMessages.VERSIONS_ROW, args(
                    MessageArgument.untrusted("server", row.server()),
                    MessageArgument.untrusted("value", row.protocolVersion() == 0 ? "unknown" : Integer.toString(row.protocolVersion())),
                    MessageArgument.untrusted("name", row.pluginVersion()),
                    MessageArgument.untrusted("state", row.capabilityNames())));
            if (row.reduced()) {
                send(sender, MeshMessages.VERSIONS_REDUCED, args(
                        MessageArgument.untrusted("server", row.server()),
                        MessageArgument.untrusted("reason", row.missingNames())));
            }
        }
    }

    @Director(name = "drain", sync = true, descriptionKey = "mesh.command.help.drain", description = "Take this server out of destination rotation or put it back")
    public void drain(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "state", descriptionKey = "mesh.command.param.drain.state", description = "on or off; omit to show the current state", defaultValue = "") String state) {
        NetworkManager network = adminNetwork(sender);
        if (network == null) {
            return;
        }
        String value = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            send(sender, MeshMessages.DRAIN_STATE, args(MessageArgument.trusted("state", network.drain().isDraining() ? "<yellow>on" : "<green>off")));
            return;
        }
        if (!value.equals("on") && !value.equals("off")) {
            send(sender, MeshMessages.DRAIN_INVALID, args(MessageArgument.untrusted("value", state)));
            return;
        }
        boolean drain = value.equals("on");
        try {
            network.drain().set(drain);
        } catch (IOException e) {
            Wormholes.w("net: could not persist drain flag: " + e.getMessage());
        }
        send(sender, drain ? MeshMessages.DRAIN_ON : MeshMessages.DRAIN_OFF);
    }

    @Director(name = "policy", sync = true, descriptionKey = "mesh.command.help.policy", description = "Set or clear a gateway's destination policy")
    public void policy(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "portal", descriptionKey = "mesh.command.param.policy.portal", description = "Local portal name or id") String portal,
                       @Param(name = "candidates", descriptionKey = "mesh.command.param.policy.candidates", description = "Comma-separated server:portal[:weight] entries; empty clears the policy", defaultValue = "") String candidates,
                       @Param(name = "strategy", descriptionKey = "mesh.command.param.policy.strategy", description = "FIRST_AVAILABLE, LEAST_LOADED, ROUND_ROBIN, STICKY or NEAREST", defaultValue = "FIRST_AVAILABLE") String strategy,
                       @Param(name = "headroom", descriptionKey = "mesh.command.param.policy.headroom", description = "Free player slots a candidate must report", defaultValue = "1") int headroom,
                       @Param(name = "tps", descriptionKey = "mesh.command.param.policy.tps", description = "Minimum TPS a candidate must report; 0 ignores TPS", defaultValue = "0") double tps,
                       @Param(name = "queue", descriptionKey = "mesh.command.param.policy.queue", description = "Hold travelers when every candidate is full", defaultValue = "true") boolean queue) {
        if (adminNetwork(sender) == null) {
            return;
        }
        LocalPortal target = findLocalPortal(portal);
        MeshPortalExtension extension = target == null ? null : target.extension(MeshPortalExtension.class);
        if (extension == null) {
            send(sender, MeshMessages.POLICY_PORTAL_UNKNOWN, args(MessageArgument.untrusted("portal", portal)));
            return;
        }
        String candidateText = candidates == null ? "" : candidates.trim();
        if (candidateText.isEmpty()) {
            extension.setPolicy(null);
            send(sender, MeshMessages.POLICY_CLEARED, args(MessageArgument.untrusted("portal", target.getName())));
            return;
        }
        SelectionStrategy selection;
        try {
            selection = SelectionStrategy.valueOf(strategy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(sender, MeshMessages.POLICY_INVALID, args(MessageArgument.untrusted("reason", "unknown strategy " + strategy)));
            return;
        }
        List<DestinationCandidate> parsed = new ArrayList<>();
        for (String entry : candidateText.split(",")) {
            DestinationCandidate candidate = DestinationCandidate.parse(entry);
            if (candidate == null) {
                send(sender, MeshMessages.POLICY_INVALID, args(MessageArgument.untrusted("reason", "candidate " + entry.trim() + " is not server:portal[:weight]")));
                return;
            }
            parsed.add(candidate);
        }
        DestinationPolicy policy = new DestinationPolicy(parsed, selection, headroom, tps, queue);
        if (policy.encode().length() > 1024) {
            send(sender, MeshMessages.POLICY_INVALID, args(MessageArgument.untrusted("reason", "too many candidates for one policy")));
            return;
        }
        extension.setPolicy(policy);
        StringBuilder summary = new StringBuilder(selection.name());
        for (DestinationCandidate candidate : parsed) {
            summary.append(' ').append(candidate.text());
        }
        send(sender, MeshMessages.POLICY_SET, args(
                MessageArgument.untrusted("portal", target.getName()),
                MessageArgument.untrusted("value", summary.toString())));
    }

    private static LocalPortal findLocalPortal(String reference) {
        if (reference == null || reference.isBlank() || Wormholes.portalManager == null) {
            return null;
        }
        String wanted = reference.trim();
        for (ILocalPortal candidate : Wormholes.portalManager.getLocalPortals()) {
            if (!(candidate instanceof LocalPortal local)) {
                continue;
            }
            if (wanted.equalsIgnoreCase(local.getId().toString()) || wanted.equalsIgnoreCase(local.getName())) {
                return local;
            }
        }
        return null;
    }

    private static NetworkManager adminNetwork(CommandSender sender) {
        if (!sender.hasPermission("wormholes.admin.network")) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
            return null;
        }
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            send(sender, WormholesMessages.NETWORK_NOT_INITIALIZED);
            return null;
        }
        return network;
    }

    private static NetworkManager meshNetwork(CommandSender sender) {
        NetworkManager network = adminNetwork(sender);
        if (network == null) {
            return null;
        }
        if (!Wormholes.settings.getNetwork().mesh.enabled) {
            send(sender, MeshMessages.DISABLED);
            return null;
        }
        return network;
    }

    private static boolean hasUnconnectedPeer(List<NetworkManager.PeerStatus> statuses) {
        for (NetworkManager.PeerStatus status : statuses) {
            if (!status.state().equals("CONNECTED")) {
                return true;
            }
        }
        return false;
    }

    private static void printDiagnostics(CommandSender sender, NetworkManager network) {
        List<String> diagnostics = network.diagnostics();
        if (diagnostics.isEmpty()) {
            send(sender, WormholesMessages.NETWORK_DOCTOR_CLEAR);
            return;
        }
        send(sender, WormholesMessages.NETWORK_DOCTOR_HEADER);
        for (String diagnostic : diagnostics) {
            send(sender, WormholesMessages.NETWORK_DOCTOR_LINE,
                    args(MessageArgument.untrusted("diagnostic", diagnostic)));
        }
    }

    private static MessageArgs args(MessageArgument... arguments) {
        return WormholesLocalization.args(arguments);
    }

    private static String escapeState(String state) {
        return state.toLowerCase(Locale.ROOT).replace("<", "").replace(">", "");
    }

    private static void send(CommandSender sender, TextKey key) {
        send(sender, key, MessageArgs.empty());
    }

    private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, key, arguments));
    }

    public static final class PendingServerHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            KList<String> names = new KList<>();
            NetworkManager network = Wormholes.networkManager;
            if (network != null) {
                for (PeerQuarantineStore.Entry entry : network.quarantine().all()) {
                    names.add(entry.name());
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
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
