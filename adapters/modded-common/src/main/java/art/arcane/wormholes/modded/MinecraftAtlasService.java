package art.arcane.wormholes.modded;

import art.arcane.wormholes.atlas.AtlasGuide;
import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.atlas.AtlasPlayerStore;
import art.arcane.wormholes.atlas.AtlasProximityIndex;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MinecraftAtlasService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private final AtlasProximityIndex<String> index = new AtlasProximityIndex<>();
    private final Map<UUID, CompletableFuture<AtlasPlayerState>> loading = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private AtlasPlayerStore store;
    private ExecutorService storage;
    private int ticks;
    private boolean running;

    public MinecraftAtlasService(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public void start() {
        runtime.requireServerThread();
        store = new AtlasPlayerStore(runtime.server().getServerDirectory().resolve("config/wormholes/atlas/players"),
            MinecraftJsonDocuments.INSTANCE);
        storage = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("wormholes-atlas-storage").factory());
        ticks = 99;
        running = true;
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> command = dispatcher.register(Commands.literal("atlas")
            .executes(context -> open(context.getSource(), AtlasModel.Filter.ALL))
            .then(Commands.literal("favorites").executes(context -> open(context.getSource(), AtlasModel.Filter.FAVORITES)))
            .then(Commands.literal("recents").executes(context -> open(context.getSource(), AtlasModel.Filter.RECENTS)))
            .then(Commands.literal("guide").then(Commands.argument("portal", StringArgumentType.greedyString())
                .executes(context -> guideCommand(context.getSource(), StringArgumentType.getString(context, "portal"))))));
        dispatcher.register(Commands.literal("portals").executes(context -> open(context.getSource(), AtlasModel.Filter.ALL)).redirect(command));
    }

    private int guideCommand(CommandSourceStack source, String name) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!running || !settings().enabled) {
            return 0;
        }
        withState(player, state -> {
            if (name.equalsIgnoreCase("off")) {
                state.setGuideTarget(null);
                player.sendSystemMessage(Component.empty(), true);
                source.sendSuccess(() -> Component.literal("Portal guide cleared."), false);
                return;
            }
            for (AtlasModel.Row row : candidates(player)) {
                if (row.name().equalsIgnoreCase(name)) {
                    state.setGuideTarget(row.portalId());
                    source.sendSuccess(() -> Component.literal("Guiding to " + row.name() + "."), false);
                    return;
                }
            }
            source.sendFailure(Component.literal("Portal not found: " + name));
        });
        return 1;
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
            guide(player, state);
        }
        if (ticks % 200 == 0) {
            storage.execute(store::flushDirty);
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
        sessions.remove(player.getUUID());
        loading.remove(player.getUUID());
        if (running) {
            UUID id = player.getUUID();
            AtlasPlayerStore activeStore = store;
            storage.execute(() -> activeStore.unload(id));
        }
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        if (!running) {
            return;
        }
        running = false;
        for (Session session : List.copyOf(sessions.values())) {
            if (session.player.containerMenu instanceof MinecraftInventoryMenu) {
                session.player.closeContainer();
            }
        }
        sessions.clear();
        loading.clear();
        index.clear();
        storage.execute(store::flushAll);
        storage.close();
    }

    private int open(CommandSourceStack source, AtlasModel.Filter filter) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!running || !settings().enabled) {
            source.sendFailure(Component.literal("The portal atlas is disabled."));
            return 0;
        }
        withState(player, state -> {
            Session session = new Session(player, state, filter);
            sessions.put(player.getUUID(), session);
            MinecraftInventoryMenu.open(player, Component.literal("Portal Atlas"), new MinecraftInventoryMenu.Actions(
                () -> running && settings().enabled && sessions.get(player.getUUID()) == session,
                session::render, session::click));
        });
        return 1;
    }

    private void withState(ServerPlayer player, Consumer<AtlasPlayerState> action) {
        AtlasPlayerState state = state(player);
        if (state != null) {
            action.accept(state);
            return;
        }
        CompletableFuture<AtlasPlayerState> future = loading.get(player.getUUID());
        if (future == null) {
            return;
        }
        MinecraftServer server = runtime.server();
        AtlasPlayerStore activeStore = store;
        future.whenComplete((loaded, error) -> server.execute(() -> {
            if (!running || store != activeStore || player.hasDisconnected()) {
                return;
            }
            if (error != null) {
                player.sendSystemMessage(Component.literal("Your portal atlas could not be loaded."));
                return;
            }
            action.accept(loaded);
        }));
    }

    private AtlasPlayerState state(ServerPlayer player) {
        if (!running) {
            return null;
        }
        AtlasPlayerState state = store.cached(player.getUUID());
        if (state != null) {
            return state;
        }
        UUID id = player.getUUID();
        if (!loading.containsKey(id)) {
            AtlasPlayerStore activeStore = store;
            MinecraftServer server = runtime.server();
            CompletableFuture<AtlasPlayerState> future = CompletableFuture.supplyAsync(() -> activeStore.load(id), storage);
            loading.put(id, future);
            future.whenComplete((loaded, error) -> server.execute(() -> {
                if (running && store == activeStore && loading.remove(id, future) && error != null) {
                    LOGGER.error("Could not load portal atlas for {}", id, error);
                }
            }));
        }
        return null;
    }

    private void rebuildIndex() {
        List<AtlasProximityIndex.Anchor<String>> anchors = new ArrayList<>();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (!portal.isManaged()) {
                GeometryVector center = portal.getGeometry().getApertureCenter();
                anchors.add(new AtlasProximityIndex.Anchor<>(portal.getId(), portal.getWorldKey(), center.getX(), center.getY(), center.getZ()));
            }
        }
        index.rebuild(anchors);
    }

    private List<AtlasModel.Row> candidates(ServerPlayer player) {
        List<AtlasModel.Row> rows = new ArrayList<>();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (portal.isManaged() || !runtime.portals().canDepart(player, portal)) {
                continue;
            }
            GeometryVector center = portal.getGeometry().getApertureCenter();
            double distance = world(player).equals(portal.getWorldKey())
                ? player.distanceToSqr(center.getX(), center.getY(), center.getZ()) : Double.MAX_VALUE;
            MinecraftPortal destination = portal.getDestinationId() == null ? null : runtime.portals().get(portal.getDestinationId());
            rows.add(new AtlasModel.Row(portal.getId(), portal.getName(), portal.getWorldKey(),
                destination == null ? "" : destination.getName(), "", distance, portal.isOpen(), false,
                Boolean.TRUE.equals(portal.setting("publicLookLabel"))));
        }
        return rows;
    }

    private void guide(ServerPlayer player, AtlasPlayerState state) {
        if (!settings().guideEnabled || state.guideTarget() == null) {
            return;
        }
        MinecraftPortal portal = runtime.portals().get(state.guideTarget());
        if (portal == null || !world(player).equals(portal.getWorldKey())) {
            return;
        }
        GeometryVector center = portal.getGeometry().getApertureCenter();
        String bearing = AtlasGuide.bearing(player.getYRot(), center.getX() - player.getX(), center.getZ() - player.getZ());
        player.sendSystemMessage(Component.literal(portal.getName() + " " + bearing), true);
    }

    private AtlasConfig settings() {
        return runtime.configuration().settings().getAtlas();
    }

    private static String world(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }

    private static ItemStack icon(Item item, String title, List<String> lines) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(title));
        List<Component> lore = new ArrayList<>(lines.size());
        for (String line : lines) {
            lore.add(Component.literal(line));
        }
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    private final class Session {
        private final ServerPlayer player;
        private final AtlasPlayerState state;
        private final Map<Integer, UUID> entries = new HashMap<>();
        private AtlasModel.Filter filter;
        private AtlasModel.SortMode sort = AtlasModel.SortMode.SMART;
        private int page;
        private int pages;

        private Session(ServerPlayer player, AtlasPlayerState state, AtlasModel.Filter filter) {
            this.player = player;
            this.state = state;
            this.filter = filter;
        }

        private void render(MinecraftInventoryMenu menu) {
            entries.clear();
            List<AtlasModel.Row> rows = AtlasModel.sorted(AtlasModel.visible(candidates(player), state,
                settings().discoveryRequired, filter), state, sort);
            pages = AtlasModel.pageCount(rows.size());
            page = AtlasModel.clampPage(page, pages);
            int start = AtlasModel.pageStart(page);
            int end = AtlasModel.pageEnd(rows.size(), page);
            for (int i = start; i < end; i++) {
                AtlasModel.Row row = rows.get(i);
                List<String> lore = new ArrayList<>(List.of(row.world(), row.destination(), row.open() ? "Open" : "Closed",
                    "Right click: favorite", "Shift left click: guide"));
                if (settings().showCoordinates) {
                    MinecraftPortal portal = runtime.portals().get(row.portalId());
                    GeometryVector center = portal.getGeometry().getApertureCenter();
                    lore.add((int) center.getX() + ", " + (int) center.getY() + ", " + (int) center.getZ());
                }
                ItemStack item = icon(Items.ENDER_PEARL, row.name(), lore);
                item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, state.isFavorite(row.portalId()));
                menu.set(i - start, item);
                entries.put(i - start, row.portalId());
            }
            if (rows.isEmpty()) {
                menu.set(22, icon(Items.BARRIER, "No portals found", List.of()));
            }
            control(menu, AtlasModel.Control.SORT, Items.COMPARATOR, "Sort: " + sort);
            control(menu, AtlasModel.Control.FAVORITES, Items.NETHER_STAR, "Favorites: " + (filter == AtlasModel.Filter.FAVORITES));
            control(menu, AtlasModel.Control.RECENTS, Items.CLOCK, "Recent: " + (filter == AtlasModel.Filter.RECENTS));
            control(menu, AtlasModel.Control.PAGE, Items.PAPER, "Page " + (page + 1) + "/" + pages + " - " + rows.size() + " portals");
            if (page > 0) {
                control(menu, AtlasModel.Control.PREVIOUS, Items.ARROW, "Previous page");
            }
            if (page + 1 < pages) {
                control(menu, AtlasModel.Control.NEXT, Items.ARROW, "Next page");
            }
            if (state.guideTarget() != null) {
                control(menu, AtlasModel.Control.GUIDE, Items.COMPASS, "Clear guide");
            }
        }

        private void click(MinecraftInventoryMenu.Click click) {
            UUID id = entries.get(click.slot());
            if (id != null) {
                MinecraftPortal portal = runtime.portals().get(id);
                if (portal == null || !runtime.portals().canDepart(player, portal)) {
                    click.menu().refresh();
                    return;
                }
                if (click.right() && !click.shift()) {
                    AtlasPlayerState.FavoriteResult result = state.toggleFavorite(id, settings().favoritesLimit);
                    player.sendSystemMessage(Component.literal(switch (result) {
                        case ADDED -> "Portal added to favorites.";
                        case REMOVED -> "Portal removed from favorites.";
                        case FULL -> "Your favorites list is full.";
                    }));
                } else if (!click.right() && click.shift()) {
                    state.setGuideTarget(id);
                    player.sendSystemMessage(Component.literal("Guiding to " + portal.getName() + "."));
                } else if (!click.right()) {
                    player.sendSystemMessage(Component.literal(portal.getName() + " - " + portal.getWorldKey()
                        + " - " + (portal.isOpen() ? "Open" : "Closed")));
                }
            } else if (!click.right() && !click.shift()) {
                if (click.slot() == AtlasModel.Control.PREVIOUS.slot() && page > 0) {
                    page--;
                } else if (click.slot() == AtlasModel.Control.NEXT.slot() && page + 1 < pages) {
                    page++;
                } else if (click.slot() == AtlasModel.Control.SORT.slot()) {
                    sort = sort.next();
                    page = 0;
                } else if (click.slot() == AtlasModel.Control.FAVORITES.slot()) {
                    filter = filter == AtlasModel.Filter.FAVORITES ? AtlasModel.Filter.ALL : AtlasModel.Filter.FAVORITES;
                    page = 0;
                } else if (click.slot() == AtlasModel.Control.RECENTS.slot()) {
                    filter = filter == AtlasModel.Filter.RECENTS ? AtlasModel.Filter.ALL : AtlasModel.Filter.RECENTS;
                    page = 0;
                } else if (click.slot() == AtlasModel.Control.GUIDE.slot()) {
                    state.setGuideTarget(null);
                    player.sendSystemMessage(Component.empty(), true);
                }
            }
            click.menu().refresh();
        }

        private void control(MinecraftInventoryMenu menu, AtlasModel.Control control, Item item, String label) {
            menu.set(control.slot(), icon(item, label, List.of()));
        }
    }
}
