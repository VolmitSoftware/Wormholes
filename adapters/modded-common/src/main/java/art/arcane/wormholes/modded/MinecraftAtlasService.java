package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.atlas.AtlasGuide;
import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.atlas.AtlasPlayerStore;
import art.arcane.wormholes.atlas.AtlasProximityIndex;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.localization.AtlasMessages;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.Visibility;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class MinecraftAtlasService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private final MinecraftAtlasMenu menu;
    private final AtlasProximityIndex<String> index = new AtlasProximityIndex<>();
    private final Map<UUID, CompletableFuture<AtlasPlayerState>> loading = new HashMap<>();
    private AtlasPlayerStore store;
    private int ticks;
    private boolean running;

    public MinecraftAtlasService(WormholesModRuntime runtime) {
        this.runtime = runtime;
        menu = new MinecraftAtlasMenu(runtime, this);
    }

    public void start() {
        runtime.requireServerThread();
        store = new AtlasPlayerStore(runtime.server().getServerDirectory().resolve("config/wormholes/atlas/players"),
            MinecraftJsonDocuments.INSTANCE);
        ticks = 99;
        running = true;
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        new MinecraftAtlasCommand(runtime, this).register(dispatcher);
    }

    public void tick() {
        runtime.requireServerThread();
        if (!running || !settings().enabled || ++ticks % 20 != 0) {
            return;
        }
        if (ticks % 100 == 0) {
            rebuildIndex();
        }
        for (ServerPlayer player : runtime.server().getPlayerList().getPlayers()) {
            AtlasPlayerState state = state(player);
            if (state == null) {
                continue;
            }
            if (settings().discoveryRequired) {
                for (UUID id : index.near(world(player), player.getX(), player.getY(), player.getZ(), settings().discoveryRadius)) {
                    state.discover(id);
                }
            }
            publishGuide(player, state);
        }
        if (ticks % 200 == 0) {
            store.flushDirtyAsync();
        }
    }

    public void departed(ServerPlayer player, MinecraftPortal portal) {
        runtime.requireServerThread();
        AtlasPlayerState state = state(player);
        if (state != null && !portal.isManaged()) {
            state.discover(portal.getId());
            state.recordRecent(portal.getId(), settings().recentLimit);
        }
    }

    public void forget(UUID portalId) {
        runtime.requireServerThread();
        if (!running) {
            return;
        }
        for (ServerPlayer player : runtime.server().getPlayerList().getPlayers()) {
            AtlasPlayerState state = store.cached(player.getUUID());
            if (state != null) {
                state.forget(portalId);
            }
        }
    }

    public void playerDisconnected(ServerPlayer player) {
        runtime.requireServerThread();
        loading.remove(player.getUUID());
        if (running) {
            store.unloadAsync(player.getUUID());
        }
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        if (!running) {
            return;
        }
        running = false;
        loading.clear();
        index.clear();
        store.close();
    }

    boolean enabled() {
        return running && settings().enabled;
    }

    void open(ServerPlayer player, AtlasModel.Filter filter) {
        withState(player, state -> menu.open(player, state, filter));
    }

    void withState(ServerPlayer player, Consumer<AtlasPlayerState> action) {
        if (!running) {
            return;
        }
        MinecraftServer server = runtime.server();
        AtlasPlayerStore activeStore = store;
        load(player).whenComplete((loaded, error) -> server.execute(() -> {
            if (running && store == activeStore && error == null && !player.hasDisconnected()) {
                action.accept(loaded);
            }
        }));
    }

    AtlasPlayerState.FavoriteResult toggleFavorite(AtlasPlayerState state, UUID portalId) {
        return state.toggleFavorite(portalId, settings().favoritesLimit);
    }

    void setGuideTarget(ServerPlayer player, AtlasPlayerState state, UUID portalId) {
        state.setGuideTarget(portalId);
        if (portalId == null) {
            MinecraftMenuText.notice(player, Component.empty());
        }
    }

    List<AtlasModel.Row> candidates(ServerPlayer player) {
        List<AtlasModel.Row> rows = new ArrayList<>();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (portal.isManaged() || !runtime.portals().canDepart(player, portal)) {
                continue;
            }
            rows.add(row(player, portal));
        }
        return rows;
    }

    AtlasConfig settings() {
        return runtime.configuration().settings().getAtlas();
    }

    void send(ServerPlayer player, TextKey key, MessageArgs arguments) {
        player.sendSystemMessage(MinecraftMenuText.text(player, key, arguments));
    }

    static UUID networkId(MinecraftPortal portal) {
        return portal.setting("nexus.networkId") instanceof String id && !id.isBlank() ? UUID.fromString(id) : null;
    }

    private AtlasModel.Row row(ServerPlayer player, MinecraftPortal portal) {
        Vec3d center = portal.getGeometry().getApertureCenter();
        double distance = world(player).equals(portal.getWorldKey())
            ? player.distanceToSqr(center.getX(), center.getY(), center.getZ()) : Double.MAX_VALUE;
        String address = "";
        boolean listed = Boolean.TRUE.equals(portal.setting("publicLookLabel"));
        UUID networkId = networkId(portal);
        if (networkId != null) {
            address = portal.setting("nexus.address") instanceof String value ? value : "";
            PortalNetwork network = runtime.nexus().networks().byId(networkId);
            listed |= network != null && network.visibility() == Visibility.PUBLIC;
        }
        MinecraftPortal destination = portal.getDestinationId() == null ? null : runtime.portals().get(portal.getDestinationId());
        return new AtlasModel.Row(portal.getId(), portal.getName(), portal.getWorldKey(),
            destination == null ? "" : destination.getName(), address, distance, portal.isOpen() && runtime.effects().ready(portal), false, listed);
    }

    private AtlasPlayerState state(ServerPlayer player) {
        if (!running) {
            return null;
        }
        AtlasPlayerState state = store.cached(player.getUUID());
        if (state != null) {
            return state;
        }
        load(player);
        return store.cached(player.getUUID());
    }

    private CompletableFuture<AtlasPlayerState> load(ServerPlayer player) {
        UUID id = player.getUUID();
        AtlasPlayerState cached = store.cached(id);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<AtlasPlayerState> pending = loading.get(id);
        if (pending != null) {
            return pending;
        }
        AtlasPlayerStore activeStore = store;
        MinecraftServer server = runtime.server();
        CompletableFuture<AtlasPlayerState> future = activeStore.loadAsync(id);
        loading.put(id, future);
        future.whenComplete((loaded, error) -> server.execute(() -> {
            if (running && store == activeStore && loading.remove(id, future) && error != null) {
                LOGGER.error("Could not load portal atlas for {}", id, error);
            }
        }));
        return future;
    }

    private void rebuildIndex() {
        List<AtlasProximityIndex.Anchor<String>> anchors = new ArrayList<>();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (!portal.isManaged()) {
                Vec3d center = portal.getGeometry().getApertureCenter();
                anchors.add(new AtlasProximityIndex.Anchor<>(portal.getId(), portal.getWorldKey(), center.getX(), center.getY(), center.getZ()));
            }
        }
        index.rebuild(anchors);
    }

    private void publishGuide(ServerPlayer player, AtlasPlayerState state) {
        if (!settings().guideEnabled || state.guideTarget() == null) {
            return;
        }
        MinecraftPortal portal = runtime.portals().get(state.guideTarget());
        if (portal == null || !world(player).equals(portal.getWorldKey())) {
            return;
        }
        Vec3d center = portal.getGeometry().getApertureCenter();
        String bearing = AtlasGuide.bearing(player.getYRot(), center.getX() - player.getX(), center.getZ() - player.getZ());
        MinecraftMenuText.notice(player, MinecraftMenuText.text(player, AtlasMessages.GUIDE_BEARING,
            MinecraftPortalText.arguments("portal", portal.getName(), "value", bearing)));
    }

    private static String world(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }
}
