package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.localization.AtlasMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class MinecraftAtlasMenu {
    private static final int ROW_WIDTH = 9;

    private final WormholesModRuntime runtime;
    private final MinecraftAtlasService service;

    MinecraftAtlasMenu(WormholesModRuntime runtime, MinecraftAtlasService service) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.service = Objects.requireNonNull(service, "service");
    }

    void open(ServerPlayer viewer, AtlasPlayerState state, AtlasModel.Filter filter) {
        new Session(viewer, state, filter).open();
    }

    private final class Session {
        private final ServerPlayer viewer;
        private final AtlasPlayerState state;
        private final MinecraftWindow window;
        private AtlasModel.Filter filter;
        private AtlasModel.SortMode sortMode = AtlasModel.SortMode.SMART;
        private int page;

        private Session(ServerPlayer viewer, AtlasPlayerState state, AtlasModel.Filter filter) {
            this.viewer = viewer;
            this.state = state;
            this.filter = filter;
            window = new MinecraftWindow(runtime, viewer).setDecorator(Items.STAINED_GLASS_PANE.blue());
            window.setViewportHeight(6);
        }

        private void open() {
            window.batch(this::populate);
            window.open();
        }

        private void repopulate() {
            window.batch(() -> {
                window.clearElements();
                populate();
            });
            window.updateInventory();
        }

        private void populate() {
            List<AtlasModel.Row> rows = AtlasModel.sorted(
                AtlasModel.visible(service.candidates(viewer), state, service.settings().discoveryRequired, filter),
                state, sortMode);
            window.setTitle(MinecraftLegacyText.text(viewer, AtlasMessages.TITLE, MinecraftPortalText.arguments("count", rows.size())));

            int pageCount = AtlasModel.pageCount(rows.size());
            page = AtlasModel.clampPage(page, pageCount);
            int start = AtlasModel.pageStart(page);
            int end = AtlasModel.pageEnd(rows.size(), page);
            for (int index = start; index < end; index++) {
                int slot = index - start;
                window.setElement(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH, rowElement(rows.get(index), index));
            }
            if (rows.isEmpty()) {
                window.setElement(0, 2, element("atlas-empty", WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY,
                    MessageArgs.empty(), Items.BARRIER));
            }
            controls(pageCount, rows.size());
        }

        private void controls(int pageCount, int rowCount) {
            if (page > 0) {
                MinecraftElement previous = element("atlas-previous", WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS,
                    MessageArgs.empty(), Items.ARROW);
                previous.onLeftClick(clicked -> {
                    page--;
                    repopulate();
                });
                control(AtlasModel.Control.PREVIOUS, previous);
            }
            MinecraftElement sort = element("atlas-sort", WormholesMessages.PORTAL_MENU_DESTINATION_SORT,
                MinecraftPortalText.arguments("mode", plain(sortLabel())), Items.COMPARATOR);
            sort.onLeftClick(clicked -> {
                sortMode = sortMode.next();
                page = 0;
                repopulate();
            });
            control(AtlasModel.Control.SORT, sort);

            MinecraftElement favorites = element("atlas-favorites", AtlasMessages.MENU_FAVORITES,
                MinecraftPortalText.arguments("state", Boolean.toString(filter == AtlasModel.Filter.FAVORITES)), Items.NETHER_STAR);
            favorites.onLeftClick(clicked -> setFilter(AtlasModel.Filter.FAVORITES));
            control(AtlasModel.Control.FAVORITES, favorites);

            MinecraftElement recents = element("atlas-recents", AtlasMessages.MENU_RECENTS,
                MinecraftPortalText.arguments("state", Boolean.toString(filter == AtlasModel.Filter.RECENTS)), Items.CLOCK);
            recents.onLeftClick(clicked -> setFilter(AtlasModel.Filter.RECENTS));
            control(AtlasModel.Control.RECENTS, recents);

            control(AtlasModel.Control.PAGE, element("atlas-page", WormholesMessages.PORTAL_MENU_DESTINATION_PAGE,
                MinecraftPortalText.arguments("page", page + 1, "pages", pageCount, "count", rowCount), Items.PAPER));

            UUID guided = state.guideTarget();
            if (guided != null) {
                MinecraftElement guide = element("atlas-guide", AtlasMessages.MENU_GUIDE,
                    MinecraftPortalText.arguments("portal", portalName(guided)), Items.COMPASS);
                guide.onLeftClick(clicked -> runtime.schedule(() -> {
                    service.setGuideTarget(viewer, state, null);
                    service.send(viewer, AtlasMessages.GUIDE_CLEARED, MessageArgs.empty());
                    repopulate();
                }, 1L));
                control(AtlasModel.Control.GUIDE, guide);
            }
            if (page + 1 < pageCount) {
                MinecraftElement next = element("atlas-next", WormholesMessages.PORTAL_MENU_DESTINATION_NEXT,
                    MessageArgs.empty(), Items.ARROW);
                next.onLeftClick(clicked -> {
                    page++;
                    repopulate();
                });
                control(AtlasModel.Control.NEXT, next);
            }
        }

        private void control(AtlasModel.Control control, MinecraftElement element) {
            window.setElement(control.slot() % ROW_WIDTH - ROW_WIDTH / 2, control.slot() / ROW_WIDTH, element);
        }

        private void setFilter(AtlasModel.Filter requested) {
            filter = filter == requested ? AtlasModel.Filter.ALL : requested;
            page = 0;
            repopulate();
        }

        private MinecraftElement rowElement(AtlasModel.Row row, int index) {
            MinecraftElement element = element("atlas-" + index, AtlasMessages.MENU_ROW, MinecraftPortalText.arguments(
                    "portal", row.name(),
                    "world", row.world(),
                    "destination", row.destination().isEmpty() ? row.address() : row.destination(),
                    "state", plain(row.open() ? WormholesMessages.LABEL_OPEN : WormholesMessages.LABEL_CLOSED)),
                row.isNetworked() ? Items.COMPASS : Items.ENDER_PEARL);
            element.setEnchanted(state.isFavorite(row.portalId()));
            element.onLeftClick(clicked -> runtime.schedule(() -> dial(row), 1L));
            element.onRightClick(clicked -> runtime.schedule(() -> pin(row), 1L));
            element.onShiftLeftClick(clicked -> runtime.schedule(() -> guide(row), 1L));
            return element;
        }

        private void dial(AtlasModel.Row row) {
            MinecraftPortal portal = runtime.portals().get(row.portalId());
            if (portal == null) {
                return;
            }
            if (MinecraftAtlasService.networkId(portal) == null) {
                service.send(viewer, AtlasMessages.ROW, MinecraftPortalText.arguments(
                    "portal", row.name(),
                    "destination", row.destination().isEmpty() ? row.world() : row.destination(),
                    "state", plain(row.open() ? WormholesMessages.LABEL_OPEN : WormholesMessages.LABEL_CLOSED)));
                return;
            }
            window.close();
            runtime.nexus().menus().open(viewer, portal);
        }

        private void pin(AtlasModel.Row row) {
            switch (service.toggleFavorite(state, row.portalId())) {
                case ADDED -> service.send(viewer, AtlasMessages.FAVORITE_ADDED, MinecraftPortalText.arguments("portal", row.name()));
                case REMOVED -> service.send(viewer, AtlasMessages.FAVORITE_REMOVED, MinecraftPortalText.arguments("portal", row.name()));
                case FULL -> service.send(viewer, AtlasMessages.FAVORITE_FULL,
                    MinecraftPortalText.arguments("count", service.settings().favoritesLimit));
            }
            repopulate();
        }

        private void guide(AtlasModel.Row row) {
            service.setGuideTarget(viewer, state, row.portalId());
            service.send(viewer, AtlasMessages.GUIDE_SET, MinecraftPortalText.arguments("portal", row.name()));
            repopulate();
        }

        private TextKey sortLabel() {
            return switch (sortMode) {
                case SMART -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_SMART;
                case NAME -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_NAME;
                case WORLD -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_WORLD;
                case DISTANCE -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_DISTANCE;
            };
        }

        private String portalName(UUID portalId) {
            MinecraftPortal portal = runtime.portals().get(portalId);
            return portal == null ? "" : portal.getName();
        }

        private String plain(TextKey key) {
            return MinecraftMenuText.text(viewer, key, MessageArgs.empty()).getString();
        }

        private MinecraftElement element(String id, LinesKey key, MessageArgs arguments, Item material) {
            MinecraftElement element = new MinecraftElement(id);
            element.setMaterial(material);
            MinecraftLegacyText.apply(viewer, element, key, arguments);
            return element;
        }
    }
}
