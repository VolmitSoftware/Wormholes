package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.access.PortalLookup;
import art.arcane.wormholes.access.PortalOwnershipLimits;
import art.arcane.wormholes.access.PortalPermissionKey;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.players.NameAndId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinecraftAccessService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final String ADMIN_ACCESS = "wormholes.admin.access";
    private final WormholesModRuntime runtime;
    private final List<PermissionProvider> providers = new ArrayList<>();
    private final List<PlacementProvider> placementProviders = new ArrayList<>();
    private long generation;

    public MinecraftAccessService(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    public AutoCloseable register(PermissionProvider provider) {
        runtime.requireServerThread();
        providers.add(Objects.requireNonNull(provider));
        return () -> {
            runtime.requireServerThread();
            providers.remove(provider);
        };
    }

    public AutoCloseable registerPlacement(PlacementProvider provider) {
        runtime.requireServerThread();
        placementProviders.add(Objects.requireNonNull(provider));
        return () -> {
            runtime.requireServerThread();
            placementProviders.remove(provider);
        };
    }

    public boolean canPlace(Placement placement) {
        runtime.requireServerThread();
        if (!runtime.configuration().settings().getAccess().claimCheckOnConstruct) {
            return true;
        }
        for (PlacementProvider provider : placementProviders) {
            try {
                if (provider.allow(placement)) {
                    continue;
                }
            } catch (RuntimeException error) {
                LOGGER.error("Could not authorize {} placement by {} in {}", placement.kind(), placement.player().getUUID(),
                    placement.level().dimension().identifier(), error);
            }
            placement.player().sendSystemMessage(MinecraftMenuText.text(placement.player(), AccessMessages.CONSTRUCT_DENIED,
                Map.of("reason", "the land-claim placement policy denied this location")));
            return false;
        }
        return true;
    }

    public boolean administrator(ServerPlayer player) {
        return permission(player.createCommandSourceStack(), "wormholes.admin");
    }

    public boolean permission(ServerPlayer player, String node) {
        return permission(player.createCommandSourceStack(), node);
    }

    public boolean permission(CommandSourceStack source, String node) {
        Decision explicit = permissionDecision(source, node);
        if (explicit != Decision.UNSET) {
            return explicit == Decision.ALLOW;
        }
        if (Commands.hasPermission(Commands.LEVEL_ADMINS).test(source)) {
            return true;
        }
        for (String parent : parents(node)) {
            Decision inherited = permissionDecision(source, parent);
            if (inherited != Decision.UNSET) {
                return inherited == Decision.ALLOW;
            }
        }
        return node.equals("wormholes.language.self") || node.equals("volmit.language.self") || node.equals("wormholes.atlas");
    }

    private Decision permissionDecision(CommandSourceStack source, String node) {
        if (source.getEntity() instanceof ServerPlayer player) {
            for (PermissionProvider provider : providers) {
                try {
                    Decision decision = Objects.requireNonNull(provider.permission(player, node));
                    if (decision != Decision.UNSET) {
                        return decision;
                    }
                } catch (RuntimeException error) {
                    LOGGER.error("Could not check permission {} for {}", node, player.getUUID(), error);
                    return Decision.DENY;
                }
            }
        }
        Identifier identifier = node.startsWith("wormholes.")
            ? Identifier.tryParse("wormholes:" + node.substring("wormholes.".length())) : Identifier.tryParse(node);
        return identifier != null && source.permissions().hasPermission(Permission.Atom.create(identifier)) ? Decision.ALLOW : Decision.UNSET;
    }

    private static List<String> parents(String node) {
        if (node.startsWith("wormholes.admin.") || node.equals("wormholes.doors.bypass") || node.equals("wormholes.rules.bypass")) {
            return List.of("wormholes.admin", "wormholes.*");
        }
        if (node.equals("wormholes.portals.wormhole") || node.equals("wormholes.portals.portal")) {
            return List.of("wormholes.portals", "wormholes.*");
        }
        return switch (node) {
            case "wormholes.debugdump", "wormholes.language.self", "wormholes.atlas", "wormholes.admin", "wormholes.portals",
                 "wormholes.gateway", "wormholes.doors.craft", "wormholes.doors.place" -> List.of("wormholes.*");
            default -> List.of();
        };
    }

    public int maximum(ServerPlayer player) {
        runtime.requireServerThread();
        List<String> nodes = new ArrayList<>();
        for (PermissionProvider provider : providers) {
            try {
                for (String node : provider.grantedNodes(player)) {
                    if (node != null && permission(player, node)) {
                        nodes.add(node);
                    }
                }
            } catch (RuntimeException error) {
                throw new IllegalStateException("Could not read portal-limit permissions for " + player.getUUID(), error);
            }
        }
        return PortalOwnershipLimits.maximum(administrator(player), runtime.configuration().settings().getAccess().portalLimitDefault, nodes);
    }

    public int owned(UUID owner) {
        int count = 0;
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (owner.equals(portal.getOwner()) && !owner.equals(portal.getId()) && !portal.isManaged()) {
                count++;
            }
        }
        return count;
    }

    public boolean canConstruct(ServerPlayer player) {
        int maximum;
        try {
            maximum = maximum(player);
        } catch (RuntimeException error) {
            LOGGER.error("Could not authorize portal construction for {}", player.getUUID(), error);
            player.sendSystemMessage(MinecraftMenuText.text(player, AccessMessages.CONSTRUCT_DENIED,
                Map.of("reason", "portal ownership permissions could not be checked")));
            return false;
        }
        int count = owned(player.getUUID());
        if (maximum <= 0 || count < maximum) {
            return true;
        }
        player.sendSystemMessage(MinecraftMenuText.text(player, AccessMessages.DENIED_LIMIT, Map.of("count", count, "maximum", maximum)));
        return false;
    }

    public boolean setKey(ServerPlayer actor, MinecraftPortal portal, String requested) {
        if (!runtime.portals().canManage(actor, portal)) {
            return false;
        }
        return setKey(actor.createCommandSourceStack(), portal, requested);
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wormholes").then(Commands.literal("access")
            .requires(source -> permission(source, ADMIN_ACCESS))
            .then(Commands.literal("key").then(Commands.argument("portal", StringArgumentType.string())
                .then(Commands.argument("key", StringArgumentType.word()).executes(context -> {
                    MinecraftPortal portal = find(context.getSource(), StringArgumentType.getString(context, "portal"));
                    return portal != null && setKey(context.getSource(), portal, StringArgumentType.getString(context, "key")) ? 1 : 0;
                }))))
            .then(Commands.literal("transfer").then(Commands.argument("portal", StringArgumentType.string())
                .then(Commands.argument("player", StringArgumentType.word()).executes(context -> transfer(context.getSource(),
                    StringArgumentType.getString(context, "portal"), StringArgumentType.getString(context, "player"))))))
            .then(Commands.literal("limits").executes(context -> limits(context.getSource(), ""))
                .then(Commands.argument("player", StringArgumentType.word()).executes(context ->
                    limits(context.getSource(), StringArgumentType.getString(context, "player")))))));
    }

    @Override
    public void close() {
        generation++;
        providers.clear();
        placementProviders.clear();
    }

    private boolean setKey(CommandSourceStack source, MinecraftPortal portal, String requested) {
        String key = requested.trim().toLowerCase(Locale.ROOT);
        if (!PortalPermissionKey.isValid(key)) {
            send(source, AccessMessages.KEY_INVALID, Map.of());
            return false;
        }
        for (MinecraftPortal other : runtime.portals().snapshot()) {
            if (other != portal && key.equals(other.getPermissionKey())) {
                send(source, AccessMessages.KEY_TAKEN, Map.of("key", key));
                return false;
            }
        }
        portal.setPermissionKey(key);
        runtime.portals().save(portal);
        send(source, AccessMessages.KEY_SET, Map.of("portal", portal.getName(), "key", key));
        return true;
    }

    private int transfer(CommandSourceStack source, String token, String name) {
        MinecraftPortal portal = find(source, token);
        if (portal == null) {
            return 0;
        }
        MinecraftServer server = runtime.server();
        long expected = generation;
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        CompletableFuture<Optional<NameAndId>> resolved = online == null
            ? CompletableFuture.supplyAsync(() -> server.services().nameToIdCache().get(name))
            : CompletableFuture.completedFuture(Optional.of(new NameAndId(online.getGameProfile())));
        resolved.whenComplete((identity, error) -> server.execute(() -> {
            if (generation != expected || !runtime.running() || runtime.portals().get(portal.getId()) != portal) {
                return;
            }
            if (error != null) {
                LOGGER.error("Could not resolve the new owner of portal {}", portal.getId(), error);
                send(source, AccessMessages.PLAYER_NOT_FOUND, Map.of("name", name));
                return;
            }
            if (identity.isEmpty()) {
                send(source, AccessMessages.PLAYER_NOT_FOUND, Map.of("name", name));
                return;
            }
            if (!permission(source, ADMIN_ACCESS)) {
                send(source, WormholesMessages.COMMAND_NO_PERMISSION, Map.of());
                return;
            }
            portal.setOwner(identity.get().id());
            runtime.portals().save(portal);
            send(source, AccessMessages.TRANSFER_DONE, Map.of("portal", portal.getName(), "name", identity.get().name()));
            LOGGER.info("Portal {} transferred to {}", portal.getId(), identity.get().id());
        }));
        return 1;
    }

    private int limits(CommandSourceStack source, String name) {
        ServerPlayer player = name.isBlank() && source.getEntity() instanceof ServerPlayer self
            ? self : runtime.server().getPlayerList().getPlayerByName(name);
        if (player == null) {
            send(source, AccessMessages.PLAYER_NOT_FOUND, Map.of("name", name));
            return 0;
        }
        int maximum = maximum(player);
        send(source, maximum <= 0 ? AccessMessages.LIMITS_UNLIMITED : AccessMessages.LIMITS_REPORT,
            maximum <= 0 ? Map.of("name", player.getGameProfile().name(), "count", owned(player.getUUID()))
                : Map.of("name", player.getGameProfile().name(), "count", owned(player.getUUID()), "maximum", maximum));
        return 1;
    }

    private MinecraftPortal find(CommandSourceStack source, String token) {
        PortalLookup.Match<MinecraftPortal> match = PortalLookup.find(token, runtime.portals().snapshot(), MinecraftPortal::getId, MinecraftPortal::getName);
        if (match.portal() == null) {
            send(source, match.matches() > 1 ? AccessMessages.PORTAL_AMBIGUOUS : AccessMessages.PORTAL_NOT_FOUND,
                match.matches() > 1 ? Map.of("portal", token, "count", match.matches()) : Map.of("portal", token));
        }
        return match.portal();
    }

    private void send(CommandSourceStack source, TextKey key, Map<String, ?> arguments) {
        source.sendSystemMessage(MinecraftMenuText.text(runtime.localization().snapshot(source.getPlayer()), key, arguments));
    }

    public record Placement(ServerPlayer player, ServerLevel level, List<BlockPos> cells, PlacementKind kind) {
        public Placement {
            Objects.requireNonNull(player);
            Objects.requireNonNull(level);
            cells = List.copyOf(cells);
            Objects.requireNonNull(kind);
        }
    }

    public enum PlacementKind {
        WAND,
        RUNE,
        VANILLA
    }

    @FunctionalInterface
    public interface PlacementProvider {
        boolean allow(Placement placement);
    }

    public enum Decision {
        ALLOW,
        DENY,
        UNSET
    }

    public interface PermissionProvider {
        Decision permission(ServerPlayer player, String node);

        default Collection<String> grantedNodes(ServerPlayer player) {
            return List.of();
        }
    }
}
