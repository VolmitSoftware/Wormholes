package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.localization.OpsMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.ops.PortalListModel;
import art.arcane.wormholes.ops.PortalLocator;
import art.arcane.wormholes.ops.PortalMaintenance;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.MinecraftRtpCandidateLoader;
import art.arcane.wormholes.portal.rtp.RtpDestination;
import art.arcane.wormholes.portal.rtp.RtpSafetyValidator;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpValidationRequest;
import art.arcane.wormholes.portal.rtp.RtpWorld;
import art.arcane.wormholes.util.Direction;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MinecraftOperations implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private final MinecraftStatsSnapshots stats;
    private final MinecraftBackups backups;
    private final MinecraftDiagnostics diagnostics;
    private final MinecraftMetricsConsole metrics;

    public MinecraftOperations(WormholesModRuntime runtime) {
        this.runtime = runtime;
        stats = new MinecraftStatsSnapshots(runtime);
        backups = new MinecraftBackups(runtime);
        diagnostics = new MinecraftDiagnostics(runtime, stats);
        metrics = new MinecraftMetricsConsole(runtime);
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wormholes")
            .executes(context -> help(dispatcher, context.getSource()))
            .then(Commands.literal("help").executes(context -> help(dispatcher, context.getSource())))
            .then(Commands.literal("info").executes(context -> {
                lines(context.getSource(), WormholesMessages.COMMAND_INFO,
                    Map.of("range", runtime.configuration().settings().getProjection().range));
                return 1;
            }))
            .then(Commands.literal("version").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("Wormholes " + MinecraftNetworkService.version()), false);
                return 1;
            }))
            .then(diagnostics.commands())
            .then(Commands.literal("stats").requires(source -> allowed(source, "wormholes.admin"))
                .executes(context -> snapshot(context.getSource(), false))
                .then(Commands.argument("now", BoolArgumentType.bool()).executes(context ->
                    snapshot(context.getSource(), BoolArgumentType.getBool(context, "now")))))
            .then(Commands.literal("admin")
                .then(Commands.literal("freeze").requires(source -> allowed(source, "wormholes.admin.projection"))
                    .executes(context -> freeze(context.getSource(), 30))
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(0)).executes(context ->
                        freeze(context.getSource(), IntegerArgumentType.getInteger(context, "seconds")))))
                .then(Commands.literal("flush").requires(source -> allowed(source, "wormholes.admin.projection"))
                    .executes(context -> {
                        int count = runtime.projections().flush();
                        context.getSource().sendSuccess(() -> Component.literal("Flushed projections for " + count + " observers."), true);
                        return count;
                    }))
                .then(Commands.literal("deleteallportals").requires(source -> allowed(source, "wormholes.admin.reset"))
                    .executes(context -> deleteAll(context.getSource())))
                .then(Commands.literal("deleteeverything").requires(source -> allowed(source, "wormholes.admin.reset"))
                    .executes(context -> backups.reset(context.getSource())))
                .then(backups.commands())
                .then(portalCommands())));
    }

    public void tick() {
        stats.tick();
        backups.tick();
        diagnostics.tick();
        metrics.tick();
    }

    @Override
    public void close() {
        stats.close();
        backups.close();
        diagnostics.close();
        metrics.close();
    }

    int metricsPort() {
        return metrics.boundPort();
    }

    private LiteralArgumentBuilder<CommandSourceStack> portalCommands() {
        return Commands.literal("portals").requires(source -> allowed(source, "wormholes.admin.portals"))
            .then(Commands.literal("list").executes(context -> list(context.getSource(), "", 1, PortalListModel.Filters.none()))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(context ->
                    list(context.getSource(), "", IntegerArgumentType.getInteger(context, "page"), PortalListModel.Filters.none()))
                    .then(Commands.argument("filters", StringArgumentType.greedyString()).executes(context ->
                        filteredList(context.getSource(), IntegerArgumentType.getInteger(context, "page"), StringArgumentType.getString(context, "filters")))))
                .then(Commands.argument("filters", StringArgumentType.greedyString()).executes(context ->
                    filteredList(context.getSource(), 1, StringArgumentType.getString(context, "filters")))))
            .then(Commands.literal("find").then(Commands.argument("name", StringArgumentType.greedyString())
                .executes(context -> list(context.getSource(), StringArgumentType.getString(context, "name"), 1, PortalListModel.Filters.none()))))
            .then(Commands.literal("info").then(Commands.argument("portal", StringArgumentType.greedyString())
                .executes(context -> info(context.getSource(), StringArgumentType.getString(context, "portal")))))
            .then(Commands.literal("tp").then(Commands.argument("portal", StringArgumentType.greedyString())
                .executes(context -> teleport(context.getSource(), StringArgumentType.getString(context, "portal")))))
            .then(Commands.literal("retarget").then(Commands.argument("portal", StringArgumentType.string())
                .then(Commands.argument("destination", StringArgumentType.greedyString()).executes(context ->
                    retarget(context.getSource(), StringArgumentType.getString(context, "portal"), StringArgumentType.getString(context, "destination"))))))
            .then(Commands.literal("unlink").then(Commands.argument("portal", StringArgumentType.greedyString())
                .executes(context -> unlink(context.getSource(), StringArgumentType.getString(context, "portal")))))
            .then(Commands.literal("prune").executes(context -> prune(context.getSource(), true, false))
                .then(Commands.argument("dry", BoolArgumentType.bool()).executes(context ->
                    prune(context.getSource(), BoolArgumentType.getBool(context, "dry"), false))
                    .then(Commands.argument("confirm", BoolArgumentType.bool()).executes(context -> prune(context.getSource(),
                        BoolArgumentType.getBool(context, "dry"), BoolArgumentType.getBool(context, "confirm"))))))
            .then(Commands.literal("rename-server").then(Commands.argument("old", StringArgumentType.string())
                .then(Commands.argument("new", StringArgumentType.string()).executes(context -> rename(context.getSource(),
                    StringArgumentType.getString(context, "old"), StringArgumentType.getString(context, "new"), false))
                    .then(Commands.argument("confirm", BoolArgumentType.bool()).executes(context -> rename(context.getSource(),
                        StringArgumentType.getString(context, "old"), StringArgumentType.getString(context, "new"),
                        BoolArgumentType.getBool(context, "confirm")))))));
    }

    private int help(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source) {
        for (String usage : dispatcher.getAllUsage(dispatcher.getRoot().getChild("wormholes"), source, true)) {
            source.sendSuccess(() -> Component.literal("/wormholes " + usage), false);
        }
        return 1;
    }

    private int snapshot(CommandSourceStack source, boolean now) {
        if (now) {
            stats.write().whenCompleteAsync((path, failure) -> {
                if (!runtime.running()) {
                    return;
                }
                if (failure == null) {
                    lines(source, WormholesMessages.COMMAND_STATS_PATH, Map.of("path", path.toString()));
                } else {
                    LOGGER.error("Could not write Wormholes stats snapshot", failure);
                    send(source, WormholesMessages.COMMAND_STATS_UNAVAILABLE, Map.of());
                }
            }, runtime.server());
        } else {
            lines(source, WormholesMessages.COMMAND_STATS_PATH, Map.of("path", stats.path().toString()));
        }
        return 1;
    }

    private int freeze(CommandSourceStack source, int seconds) {
        int duration = seconds <= 0 ? 0 : Math.clamp(seconds, 5, 300);
        runtime.projections().freeze(duration);
        send(source, duration == 0 ? WormholesMessages.COMMAND_PROJECTION_RESUMED : WormholesMessages.COMMAND_PROJECTION_FROZEN,
            duration == 0 ? Map.of() : Map.of("seconds", duration));
        return 1;
    }

    private int deleteAll(CommandSourceStack source) {
        List<MinecraftPortal> portals = runtime.portals().snapshot();
        for (MinecraftPortal portal : portals) {
            runtime.portals().remove(portal.getId());
        }
        runtime.projections().flush();
        source.sendSuccess(() -> Component.literal("Deleted " + portals.size() + " portals."), true);
        return portals.size();
    }

    private int filteredList(CommandSourceStack source, int page, String text) {
        Map<String, String> values = new HashMap<>();
        for (String token : text.trim().split("\\s+")) {
            String[] pair = token.split("=", 2);
            if (pair.length != 2 || !List.of("world", "type", "owner", "state").contains(pair[0])
                || values.putIfAbsent(pair[0], pair[1]) != null) {
                source.sendFailure(Component.literal("Filters are world=key type=type owner=name-or-uuid state=open|closed|linked|unlinked"));
                return 0;
            }
        }
        return list(source, "", page, new PortalListModel.Filters(values.getOrDefault("world", ""),
            values.getOrDefault("type", ""), values.getOrDefault("owner", ""), values.getOrDefault("state", "")));
    }

    private int retarget(CommandSourceStack source, String sourceQuery, String destinationQuery) {
        MinecraftPortal portal = find(source, sourceQuery);
        MinecraftPortal destination = find(source, destinationQuery);
        if (portal == null || destination == null) {
            return 0;
        }
        if (portal.isManaged() || portal.isMirrorMode() || portal.getType() == PortalType.RTP
            || destination.getType() == PortalType.RTP) {
            source.sendFailure(Component.literal("This portal cannot use a local destination."));
            return 0;
        }
        portal.link(destination);
        runtime.portals().save(portal);
        send(source, OpsMessages.PORTALS_RETARGETED, Map.of("portal", portal.getName(), "destination", destination.getName()));
        return 1;
    }

    private int list(CommandSourceStack source, String name, int page, PortalListModel.Filters filters) {
        List<PortalListModel.PortalRow> rows = new ArrayList<>();
        String needle = name.toLowerCase(Locale.ROOT);
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (portal.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                rows.add(new PortalListModel.PortalRow(portal.getId(), portal.getName(), portal.getWorldKey(),
                    portal.getType().name(), portal.isOpen(), portal.getDestinationId() != null, destination(portal),
                    portal.getOwner(), portal.getOwner().toString()));
            }
        }
        PortalListModel.Page result = PortalListModel.page(rows, filters, page);
        if (result.total() == 0) {
            send(source, OpsMessages.PORTALS_EMPTY, Map.of());
        }
        for (PortalListModel.PortalRow row : result.rows()) {
            send(source, OpsMessages.PORTALS_ROW, Map.of("portal", row.name(), "world", row.world(),
                "state", row.open() ? "open" : "closed", "destination", row.destination()));
        }
        send(source, OpsMessages.PORTALS_PAGE, Map.of("page", result.page(), "pages", result.pages()));
        return result.total();
    }

    private int info(CommandSourceStack source, String query) {
        MinecraftPortal portal = find(source, query);
        if (portal == null) {
            return 0;
        }
        GeometryVector point = portal.getOrigin();
        lines(source, OpsMessages.PORTALS_INFO, Map.of("portal", portal.getName(), "id", portal.getId().toString(),
            "world", portal.getWorldKey(), "value", point.x() + ", " + point.y() + ", " + point.z(),
            "state", portal.isOpen() ? "open" : "closed", "destination", destination(portal), "owner", portal.getOwner().toString()));
        return 1;
    }

    private int unlink(CommandSourceStack source, String query) {
        MinecraftPortal portal = find(source, query);
        if (portal == null) {
            return 0;
        }
        portal.unlink();
        runtime.portals().save(portal);
        send(source, OpsMessages.PORTALS_UNLINKED, Map.of("portal", portal.getName()));
        return 1;
    }

    private int prune(CommandSourceStack source, boolean dry, boolean confirm) {
        List<PortalMaintenance.TunnelView> orphans = PortalMaintenance.orphans(tunnels());
        if (dry) {
            send(source, OpsMessages.PORTALS_PRUNE_DRY_RUN, Map.of("count", orphans.size()));
            return orphans.size();
        }
        if (!confirm) {
            send(source, OpsMessages.BACKUP_CONFIRM_REQUIRED, Map.of());
            return 0;
        }
        for (PortalMaintenance.TunnelView orphan : orphans) {
            MinecraftPortal portal = runtime.portals().get(orphan.portalId());
            portal.unlink();
            runtime.portals().save(portal);
        }
        send(source, OpsMessages.PORTALS_PRUNED, Map.of("count", orphans.size()));
        return orphans.size();
    }

    private int rename(CommandSourceStack source, String previous, String replacement, boolean confirm) {
        if (!confirm) {
            send(source, OpsMessages.BACKUP_CONFIRM_REQUIRED, Map.of());
            return 0;
        }
        int changed = 0;
        for (PortalMaintenance.TunnelView tunnel : PortalMaintenance.onServer(tunnels(), previous)) {
            MinecraftPortal portal = runtime.portals().get(tunnel.portalId());
            if (portal.linkRemote(replacement.trim(), portal.getDestinationId())) {
                runtime.portals().save(portal);
                changed++;
            }
        }
        send(source, OpsMessages.PORTALS_RENAMED_SERVER, Map.of("count", changed, "name", previous, "server", replacement));
        return changed;
    }

    private int teleport(CommandSourceStack source, String query) throws CommandSyntaxException {
        MinecraftPortal portal = find(source, query);
        if (portal == null) {
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = runtime.portals().resolveLevel(portal);
        if (level == null) {
            send(source, OpsMessages.PORTALS_NOT_FOUND, Map.of("name", query));
            return 0;
        }
        GeometryVector center = portal.getOrigin();
        Direction normal = portal.getFrame().getNormal();
        GeometryVector front = center.add(new GeometryVector(normal.x() * 1.5D, 0, normal.z() * 1.5D));
        String key = portal.getWorldKey();
        RtpWorld world = new RtpWorld(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), key,
            level.getMinY(), level.getMaxY(), level.getSeaLevel());
        RtpDestination destination = new RtpDestination(key, front.getBlockX(), front.getBlockY(), front.getBlockZ(), 0L, 0);
        RtpService.SearchRequest request = new RtpService.SearchRequest(portal.getId(), 0, RtpSettings.defaults(world), destination);
        MinecraftRtpCandidateLoader loader = new MinecraftRtpCandidateLoader(runtime);
        loader.exact(request, RtpValidationRequest.EntityEnvelope.baseline()).whenCompleteAsync((candidate, failure) -> {
            try {
                if (!runtime.running() || player.hasDisconnected() || runtime.portals().get(portal.getId()) != portal) {
                    return;
                }
                if (failure != null) {
                    LOGGER.error("Could not choose a safe operator landing at portal {}", portal.getId(), failure);
                }
                boolean safe = candidate != null && new RtpSafetyValidator().validate(candidate.validationRequest()).join().safe();
                GeometryVector point = safe ? front : center;
                if (!safe) {
                    send(source, OpsMessages.PORTALS_TELEPORT_UNSAFE, Map.of("portal", portal.getName()));
                }
                if (player.teleportTo(level, point.x(), point.y(), point.z(), Set.of(), player.getYRot(), player.getXRot(), true)) {
                    send(source, OpsMessages.PORTALS_TELEPORTED, Map.of("portal", portal.getName()));
                }
            } finally {
                if (candidate != null) {
                    candidate.retention().close();
                }
                loader.close();
            }
        }, runtime.server());
        return 1;
    }

    private List<PortalMaintenance.TunnelView> tunnels() {
        List<PortalMaintenance.TunnelView> tunnels = new ArrayList<>();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            String server = portal.getDestinationServer();
            UUID destination = portal.getDestinationId();
            boolean known = server == null || runtime.network().manager().peers().stream()
                .anyMatch(peer -> peer.name.equalsIgnoreCase(server));
            tunnels.add(new PortalMaintenance.TunnelView(portal.getId(), portal.getName(), destination != null,
                destination != null && runtime.portals().get(destination) != null, server, known));
        }
        return tunnels;
    }

    private MinecraftPortal find(CommandSourceStack source, String query) {
        List<PortalLocator.Candidate> candidates = new ArrayList<>();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            candidates.add(new PortalLocator.Candidate(portal.getId(), portal.getName()));
        }
        PortalLocator.Resolution resolution = PortalLocator.resolve(candidates, query);
        if (!resolution.found()) {
            send(source, resolution.ambiguous() ? OpsMessages.PORTALS_AMBIGUOUS : OpsMessages.PORTALS_NOT_FOUND,
                resolution.ambiguous() ? Map.of("name", query, "count", resolution.matches()) : Map.of("name", query));
            return null;
        }
        return runtime.portals().get(resolution.id());
    }

    private boolean allowed(CommandSourceStack source, String permission) {
        return runtime.access().permission(source, permission);
    }

    private void send(CommandSourceStack source, TextKey message, Map<String, ?> values) {
        source.sendSuccess(() -> runtime.localization().text(source.getPlayer(), message, values), false);
    }

    private void lines(CommandSourceStack source, LinesKey message, Map<String, ?> values) {
        for (Component line : MinecraftMenuText.lines(runtime.localization().snapshot(source.getPlayer()), message, values)) {
            source.sendSuccess(() -> line, false);
        }
    }

    private static String destination(MinecraftPortal portal) {
        UUID id = portal.getDestinationId();
        return id == null ? "-" : portal.getDestinationServer() == null ? id.toString() : portal.getDestinationServer() + ":" + id;
    }
}
