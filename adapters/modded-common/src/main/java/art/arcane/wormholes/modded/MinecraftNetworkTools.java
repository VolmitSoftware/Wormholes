package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.localization.MeshMessages;
import art.arcane.wormholes.network.mesh.DestinationCandidate;
import art.arcane.wormholes.network.mesh.DestinationPolicy;
import art.arcane.wormholes.network.mesh.SelectionStrategy;
import art.arcane.wormholes.network.mesh.PeerQuarantineStore;
import art.arcane.wormholes.network.mesh.VersionReport;
import art.arcane.wormholes.access.PortalLookup;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Locale;
import art.arcane.wormholes.network.Handshake;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.NetworkPairingService;
import art.arcane.wormholes.network.PortalCode;
import art.arcane.wormholes.network.ServerCode;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.function.Consumer;

public final class MinecraftNetworkTools implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private ExecutorService worker;
    private long generation;
    private CompletableFuture<Void> enabling;

    public MinecraftNetworkTools(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public void start() {
        runtime.requireServerThread();
        generation++;
        worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("Wormholes-pairing").factory());
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wormholes")
            .then(Commands.literal("network").requires(source -> runtime.access().permission(source, "wormholes.admin.network"))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("doctor").executes(context -> doctor(context.getSource())))
                .then(Commands.literal("members").executes(context -> members(context.getSource())))
                .then(Commands.literal("pending").executes(context -> pending(context.getSource())))
                .then(Commands.literal("versions").executes(context -> versions(context.getSource())))
                .then(Commands.literal("accept").then(Commands.argument("server", StringArgumentType.word())
                    .executes(context -> introduction(context.getSource(), StringArgumentType.getString(context, "server"), true))))
                .then(Commands.literal("reject").then(Commands.argument("server", StringArgumentType.word())
                    .executes(context -> introduction(context.getSource(), StringArgumentType.getString(context, "server"), false))))
                .then(Commands.literal("drain").executes(context -> drain(context.getSource(), ""))
                    .then(Commands.argument("state", StringArgumentType.word())
                        .executes(context -> drain(context.getSource(), StringArgumentType.getString(context, "state")))))
                .then(Commands.literal("policy").then(policyArguments()))
                .then(Commands.literal("import").then(Commands.argument("code", StringArgumentType.greedyString())
                    .executes(context -> importCode(context.getSource(), null, StringArgumentType.getString(context, "code")))))
                .then(Commands.literal("export").then(Commands.argument("portal", UuidArgument.uuid())
                    .executes(context -> exportPortal(context.getSource(), UuidArgument.getUuid(context, "portal")))))
                .then(Commands.literal("link").then(Commands.argument("portal", UuidArgument.uuid())
                    .then(Commands.argument("code", StringArgumentType.greedyString())
                        .executes(context -> importCode(context.getSource(), UuidArgument.getUuid(context, "portal"),
                            StringArgumentType.getString(context, "code")))))))
            .then(Commands.literal("server").requires(source -> runtime.access().permission(source, "wormholes.admin.network"))
                .then(Commands.argument("destination", StringArgumentType.word())
                    .executes(context -> connect(context.getSource(), StringArgumentType.getString(context, "destination"))))
                .then(Commands.literal("connect").then(Commands.argument("destination", StringArgumentType.word())
                    .executes(context -> connect(context.getSource(), StringArgumentType.getString(context, "destination")))))
                .then(Commands.literal("export").executes(context -> exportServer(context.getSource())))
                .then(Commands.literal("import").then(Commands.argument("code", StringArgumentType.greedyString())
                    .executes(context -> importCode(context.getSource(), null, StringArgumentType.getString(context, "code")))))
                .then(Commands.literal("list").executes(context -> status(context.getSource())))
                .then(Commands.literal("remove").then(Commands.argument("server", StringArgumentType.word())
                    .executes(context -> remove(context.getSource(), StringArgumentType.getString(context, "server")))))));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> policyArguments() {
        RequiredArgumentBuilder<CommandSourceStack, String> portal = Commands.argument("portal", StringArgumentType.string());
        portal.executes(context -> policy(context, "", "FIRST_AVAILABLE", 1, 0, true));
        RequiredArgumentBuilder<CommandSourceStack, String> candidates = Commands.argument("candidates", StringArgumentType.string());
        candidates.executes(context -> policy(context, StringArgumentType.getString(context, "candidates"), "FIRST_AVAILABLE", 1, 0, true));
        RequiredArgumentBuilder<CommandSourceStack, String> strategy = Commands.argument("strategy", StringArgumentType.word());
        strategy.executes(context -> policy(context, StringArgumentType.getString(context, "candidates"), StringArgumentType.getString(context, "strategy"), 1, 0, true));
        RequiredArgumentBuilder<CommandSourceStack, Integer> headroom = Commands.argument("headroom", IntegerArgumentType.integer(0));
        headroom.executes(context -> policy(context, StringArgumentType.getString(context, "candidates"), StringArgumentType.getString(context, "strategy"), IntegerArgumentType.getInteger(context, "headroom"), 0, true));
        RequiredArgumentBuilder<CommandSourceStack, Double> tps = Commands.argument("tps", DoubleArgumentType.doubleArg(0, 20));
        tps.executes(context -> policy(context, StringArgumentType.getString(context, "candidates"), StringArgumentType.getString(context, "strategy"), IntegerArgumentType.getInteger(context, "headroom"), DoubleArgumentType.getDouble(context, "tps"), true));
        tps.then(Commands.argument("queue", BoolArgumentType.bool()).executes(context -> policy(context,
            StringArgumentType.getString(context, "candidates"), StringArgumentType.getString(context, "strategy"), IntegerArgumentType.getInteger(context, "headroom"),
            DoubleArgumentType.getDouble(context, "tps"), BoolArgumentType.getBool(context, "queue"))));
        return portal.then(candidates.then(strategy.then(headroom.then(tps))));
    }

    private int members(CommandSourceStack source) {
        NetworkManager network = runtime.network().manager();
        List<NetworkConfig.PeerEntry> peers = network.peers();
        peers.sort((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.name, second.name));
        if (peers.isEmpty()) {
            send(source, MeshMessages.MEMBERS_NONE, Map.of());
        }
        for (NetworkConfig.PeerEntry peer : peers) {
            byte[] key = network.trustedKey(peer.name);
            send(source, MeshMessages.MEMBERS_ROW, Map.of("server", peer.name, "state", network.isPeerReady(peer.name) ? "ready" : "offline",
                "fingerprint", key == null ? "untrusted" : Handshake.fingerprint(key)));
        }
        return 1;
    }

    private int pending(CommandSourceStack source) {
        NetworkManager network = runtime.network().manager();
        if (!meshEnabled(source)) {
            return 0;
        }
        List<PeerQuarantineStore.Entry> entries = network.quarantine().all();
        entries.sort((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(first.name(), second.name()));
        if (entries.isEmpty()) {
            send(source, MeshMessages.INTRODUCTIONS_NONE, Map.of());
        }
        for (PeerQuarantineStore.Entry entry : entries) {
            send(source, MeshMessages.PENDING, Map.of("server", entry.name(), "name", entry.introducer()));
        }
        return 1;
    }

    private int introduction(CommandSourceStack source, String requested, boolean accepted) {
        if (!meshEnabled(source)) {
            return 0;
        }
        NetworkManager network = runtime.network().manager();
        String name = network.quarantine().all().stream().map(PeerQuarantineStore.Entry::name)
            .filter(candidate -> candidate.equalsIgnoreCase(requested)).findFirst().orElse(null);
        if (name == null) {
            send(source, MeshMessages.INTRODUCTIONS_UNKNOWN, Map.of("server", requested));
            return 0;
        }
        work(source, () -> {
            boolean changed = accepted ? network.mesh().acceptQuarantined(name) : network.mesh().rejectQuarantined(name);
            return changed ? name : "";
        }, changed -> {
            if (changed.isBlank()) {
                send(source, MeshMessages.INTRODUCTIONS_UNKNOWN, Map.of("server", name));
            } else {
                if (accepted && !network.isRunning()) {
                    network.start();
                }
                send(source, accepted ? MeshMessages.ACCEPTED : MeshMessages.REJECTED, Map.of("server", name));
            }
        });
        return 1;
    }

    private int versions(CommandSourceStack source) {
        List<VersionReport.Row> rows = VersionReport.build(runtime.network().manager());
        if (rows.isEmpty()) {
            send(source, MeshMessages.VERSIONS_NONE, Map.of());
        }
        for (VersionReport.Row row : rows) {
            send(source, MeshMessages.VERSIONS_ROW, Map.of("server", row.server(), "value", row.protocolVersion() == 0 ? "unknown" : Integer.toString(row.protocolVersion()),
                "name", row.pluginVersion(), "state", row.capabilityNames()));
            if (row.reduced()) {
                send(source, MeshMessages.VERSIONS_REDUCED, Map.of("server", row.server(), "reason", row.missingNames()));
            }
        }
        return 1;
    }

    private int drain(CommandSourceStack source, String state) {
        NetworkManager network = runtime.network().manager();
        String value = state.toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            send(source, MeshMessages.DRAIN_STATE, Map.of("state", network.drain().isDraining() ? "on" : "off"));
            return 1;
        }
        if (!value.equals("on") && !value.equals("off")) {
            send(source, MeshMessages.DRAIN_INVALID, Map.of("value", state));
            return 0;
        }
        work(source, () -> {
            try {
                network.drain().set(value.equals("on"));
                return value;
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not persist network drain state", failure);
            }
        }, changed -> send(source, changed.equals("on") ? MeshMessages.DRAIN_ON : MeshMessages.DRAIN_OFF, Map.of()));
        return 1;
    }

    private int policy(CommandContext<CommandSourceStack> context, String candidates, String strategy, int headroom, double tps, boolean queue) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "portal");
        PortalLookup.Match<MinecraftPortal> match = PortalLookup.find(name, runtime.portals().snapshot(), MinecraftPortal::getId, MinecraftPortal::getName);
        MinecraftPortal portal = match.portal();
        if (portal == null || !manageable(source, portal)) {
            send(source, MeshMessages.POLICY_PORTAL_UNKNOWN, Map.of("portal", name));
            return 0;
        }
        try {
            DestinationPolicy policy = parsePolicy(candidates, strategy, headroom, tps, queue);
            portal.setMeshPolicy(policy == null ? null : policy.encode());
            runtime.portals().save(portal);
            send(source, policy == null ? MeshMessages.POLICY_CLEARED : MeshMessages.POLICY_SET,
                policy == null ? Map.of("portal", portal.getName()) : Map.of("portal", portal.getName(), "value", strategy + " " + candidates));
            return 1;
        } catch (IllegalArgumentException invalid) {
            send(source, MeshMessages.POLICY_INVALID, Map.of("reason", invalid.getMessage()));
            return 0;
        }
    }

    static DestinationPolicy parsePolicy(String candidates, String strategy, int headroom, double tps, boolean queue) {
        if (candidates.isBlank()) {
            return null;
        }
        List<DestinationCandidate> parsed = new ArrayList<>();
        for (String entry : candidates.split(",")) {
            DestinationCandidate candidate = DestinationCandidate.parse(entry.trim());
            if (candidate == null) {
                throw new IllegalArgumentException("Expected server:portal[:weight]: " + entry);
            }
            parsed.add(candidate);
        }
        DestinationPolicy policy = new DestinationPolicy(parsed, SelectionStrategy.valueOf(strategy.toUpperCase(Locale.ROOT)), headroom, tps, queue);
        if (policy.encode().length() > 1024) {
            throw new IllegalArgumentException("Policy exceeds 1024 characters");
        }
        return policy;
    }

    private boolean meshEnabled(CommandSourceStack source) {
        if (!runtime.network().manager().activeConfig().mesh.enabled) {
            send(source, MeshMessages.DISABLED, Map.of());
            return false;
        }
        return true;
    }

    public int exportPortal(CommandSourceStack source, UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (!manageable(source, portal)) {
            return 0;
        }
        String name = portal.getName();
        NetworkPairingService pairing = pairing();
        enable(source, () -> pairing.portalCode(portalId, name).encode(), encoded -> copy(source, encoded,
            WormholesMessages.NETWORK_COPY_CODE, WormholesMessages.NETWORK_COPY_CODE_HOVER, Map.of("portal", name)));
        return 1;
    }

    public int importCode(CommandSourceStack source, UUID portalId, String encoded) {
        MinecraftPortal portal = portalId == null ? null : runtime.portals().get(portalId);
        if (portalId != null && !manageable(source, portal)) {
            return 0;
        }
        ServerCode serverCode = ServerCode.decode(encoded);
        PortalCode portalCode = serverCode == null ? PortalCode.decode(encoded) : null;
        if (serverCode == null && portalCode == null) {
            send(source, WormholesMessages.NETWORK_CODE_INVALID, Map.of("prefix", PortalCode.PREFIX + " or " + ServerCode.PREFIX));
            return 0;
        }
        String name = serverCode == null ? portalCode.serverName() : serverCode.serverName();
        if (name.equals(runtime.network().manager().getLocalName())) {
            send(source, WormholesMessages.NETWORK_CODE_SAME_SERVER, Map.of());
            return 0;
        }
        NetworkPairingService pairing = pairing();
        enable(source, () -> {
            if (serverCode != null) {
                pairing.importServer(serverCode);
            } else {
                pairing.importPortal(portalCode);
            }
            return name;
        }, ignored -> {
            if (portal != null && portalCode != null) {
                if (runtime.portals().get(portalId) != portal || !manageable(source, portal)) {
                    return;
                }
                if (!portal.linkRemote(name, portalCode.portalId())) {
                    send(source, WormholesMessages.PORTAL_DESTINATION_UNREACHABLE, Map.of());
                    return;
                }
                runtime.portals().save(portal);
                runtime.menus().refresh(portalId);
                send(source, WormholesMessages.NETWORK_LINKED, Map.of("portal", portal.getName(),
                    "destination", portalCode.portalName(), "server", name));
            } else {
                String key = serverCode == null ? portalCode.publicKey() : serverCode.publicKey();
                send(source, WormholesMessages.SERVER_SAVED, Map.of("server", name,
                    "fingerprint", Handshake.fingerprint(Handshake.decodePublicKeyText(key))));
            }
            send(source, WormholesMessages.NETWORK_CHECK_STATUS, Map.of());
        });
        return 1;
    }

    @Override
    public void close() {
        generation++;
        enabling = null;
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    private int connect(CommandSourceStack source, String requested) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            send(source, WormholesMessages.SERVER_ONLY_PLAYERS, Map.of());
            return 0;
        }
        NetworkManager network = runtime.network().manager();
        String name = network.peers().stream().map(peer -> peer.name)
            .filter(peer -> peer.equalsIgnoreCase(requested)).findFirst().orElse(null);
        if (name == null) {
            send(source, WormholesMessages.SERVER_UNKNOWN, Map.of("server", requested));
            return 0;
        }
        if (!runtime.network().handoffs().begin(player, name, null, null, null)) {
            send(source, WormholesMessages.SERVER_CONNECT_FAILED, Map.of("server", name));
            return 0;
        }
        send(source, WormholesMessages.SERVER_CONNECTING, Map.of("server", name));
        return 1;
    }

    private int exportServer(CommandSourceStack source) {
        NetworkPairingService pairing = pairing();
        enable(source, () -> pairing.serverCode().encode(), encoded -> copy(source, encoded,
            WormholesMessages.SERVER_COPY_CODE, WormholesMessages.SERVER_COPY_CODE_HOVER,
            Map.of("server", runtime.network().manager().getLocalName())));
        return 1;
    }

    private int status(CommandSourceStack source) {
        NetworkManager network = runtime.network().manager();
        if (!network.activeConfig().enabled) {
            send(source, WormholesMessages.NETWORK_DISABLED, Map.of());
            return 1;
        }
        if (!network.isRunning()) {
            send(source, WormholesMessages.NETWORK_NOT_RUNNING, Map.of());
            return 0;
        }
        send(source, network.activeConfig().listenEnabled ? WormholesMessages.NETWORK_LISTENING : WormholesMessages.NETWORK_OUTBOUND_ONLY,
            Map.of("server", network.getLocalName(), "address", network.getListenAddress()));
        send(source, WormholesMessages.NETWORK_PUBLIC_KEY, Map.of("fingerprint", network.getPublicKeyFingerprint()));
        List<NetworkManager.PeerStatus> statuses = network.status();
        if (statuses.isEmpty()) {
            send(source, WormholesMessages.NETWORK_NO_ROUTES, Map.of());
        }
        for (NetworkManager.PeerStatus peer : statuses) {
            send(source, WormholesMessages.NETWORK_PEER, Map.of("server", peer.name(), "state", peer.state(),
                "address", peer.address(), "rtt", peer.rttMillis() < 0 ? "" : " " + peer.rttMillis() + "ms"));
        }
        return 1;
    }

    private int doctor(CommandSourceStack source) {
        List<String> diagnostics = runtime.network().manager().diagnostics();
        if (diagnostics.isEmpty()) {
            send(source, WormholesMessages.NETWORK_DOCTOR_CLEAR, Map.of());
        } else {
            send(source, WormholesMessages.NETWORK_DOCTOR_HEADER, Map.of());
            for (String diagnostic : diagnostics) {
                send(source, WormholesMessages.NETWORK_DOCTOR_LINE, Map.of("diagnostic", diagnostic));
            }
        }
        return 1;
    }

    private int remove(CommandSourceStack source, String requested) {
        NetworkManager network = runtime.network().manager();
        String name = network.peers().stream().map(peer -> peer.name)
            .filter(peer -> peer.equalsIgnoreCase(requested)).findFirst().orElse(null);
        if (name == null) {
            send(source, WormholesMessages.SERVER_UNKNOWN, Map.of("server", requested));
            return 0;
        }
        work(source, () -> {
            network.removePeer(name);
            return name;
        }, removed -> {
            runtime.network().remotePortals().removePeer(removed);
            runtime.network().viewServer().peerDisconnected(removed);
            send(source, WormholesMessages.SERVER_REMOVED, Map.of("server", removed));
        });
        return 1;
    }

    private void enable(CommandSourceStack source, Supplier<String> operation, Consumer<String> success) {
        NetworkConfig config = runtime.configuration().settings().getNetwork();
        long submitted = generation;
        if (enabling == null) {
            if (config.enabled) {
                enabling = CompletableFuture.completedFuture(null);
            } else {
                config.enabled = true;
                enabling = runtime.configuration().persist();
            }
        }
        CompletableFuture<Void> pending = enabling;
        pending.whenComplete((ignored, error) -> {
            if (!runtime.running() || generation != submitted) {
                return;
            }
            if (enabling == pending) {
                enabling = null;
            }
            if (runtime.configuration().settings().getNetwork() != config) {
                failure(source, new IllegalStateException("Network configuration changed during pairing"));
                return;
            }
            if (error != null) {
                config.enabled = false;
                failure(source, error);
                return;
            }
            try {
                if (!runtime.network().manager().isRunning()) {
                    runtime.network().reload();
                }
                work(source, operation, success);
            } catch (RuntimeException failure) {
                failure(source, failure);
            }
        });
    }

    private void work(CommandSourceStack source, Supplier<String> operation, Consumer<String> success) {
        MinecraftServer server = runtime.server();
        long submitted = generation;
        CompletableFuture.supplyAsync(operation, worker).whenComplete((value, error) -> server.execute(() -> {
            if (!runtime.running() || generation != submitted) {
                return;
            }
            if (error == null) {
                success.accept(value);
            } else {
                failure(source, error);
            }
        }));
    }

    private NetworkPairingService pairing() {
        return new NetworkPairingService(runtime.network().manager());
    }

    private boolean manageable(CommandSourceStack source, MinecraftPortal portal) {
        ServerPlayer actor = source.getPlayer();
        boolean allowed = portal != null && (runtime.access().permission(source, "wormholes.admin.network")
            || actor != null && runtime.portals().canManage(actor, portal));
        if (!allowed) {
            send(source, WormholesMessages.COMMAND_NO_PERMISSION, Map.of());
        }
        return allowed;
    }

    private void copy(CommandSourceStack source, String encoded, TextKey title, TextKey hover, Map<String, ?> arguments) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            send(source, WormholesMessages.SERVER_CODE_RAW, Map.of("code", encoded));
            return;
        }
        Component text = MinecraftMenuText.text(player, title, arguments).copy().withStyle(style -> style
            .withClickEvent(new ClickEvent.CopyToClipboard(encoded))
            .withHoverEvent(new HoverEvent.ShowText(MinecraftMenuText.text(player, hover, Map.of()))));
        source.sendSuccess(() -> text, false);
        send(source, WormholesMessages.NETWORK_CODE_FINGERPRINT,
            Map.of("fingerprint", runtime.network().manager().getPublicKeyFingerprint()));
        if (encoded.length() > 250) {
            send(source, WormholesMessages.NETWORK_CODE_TOO_LONG, Map.of());
        }
    }

    private static void send(CommandSourceStack source, TextKey key, Map<String, ?> arguments) {
        source.sendSuccess(() -> MinecraftMenuText.text(source.getPlayer(), key, arguments), false);
    }

    private static void failure(CommandSourceStack source, Throwable error) {
        LOGGER.error("Wormholes network operation failed", error);
        source.sendFailure(Component.literal("Network operation failed. See the server log."));
    }
}
