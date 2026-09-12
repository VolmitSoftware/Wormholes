package art.arcane.wormholes.atlas;

import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.localization.AtlasMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.nexus.DialMenu;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** The paged, sortable portal list behind {@code /atlas}. */
public final class AtlasMenu {
    private static final int ROW_WIDTH = 9;

    private final AtlasService service;
    private final DialMenu dialMenu;

    public AtlasMenu(AtlasService service, DialMenu dialMenu) {
        this.service = service;
        this.dialMenu = dialMenu;
    }

    public void open(Player viewer, AtlasModel.Filter filter) {
        new Session(viewer, filter).open();
    }

    private final class Session {
        private final Player viewer;
        private final AtlasPlayerState state;
        private final Window window;
        private AtlasModel.Filter filter;
        private AtlasModel.SortMode sortMode = AtlasModel.SortMode.SMART;
        private int page;

        private Session(Player viewer, AtlasModel.Filter filter) {
            this.viewer = viewer;
            this.filter = filter;
            this.state = service.state(viewer);
            window = new UIWindow(Wormholes.instance, viewer)
                    .setResolution(WindowResolution.W9_H6)
                    .setDecorator(new UIPaneDecorator(Material.BLUE_STAINED_GLASS_PANE));
            window.setViewportHeight(6);
        }

        private void open() {
            window.batch(this::populate);
            window.setVisible(true);
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
                    AtlasModel.visible(service.candidates(viewer), state, settings().discoveryRequired, filter),
                    state, sortMode);
            window.setTitle(Wormholes.text().legacy(AtlasMessages.TITLE, AtlasText.args("count", rows.size())));

            int pageCount = AtlasModel.pageCount(rows.size());
            page = AtlasModel.clampPage(page, pageCount);
            int start = AtlasModel.pageStart(page);
            int end = AtlasModel.pageEnd(rows.size(), page);
            for (int index = start; index < end; index++) {
                int slot = index - start;
                window.setElement(slot % ROW_WIDTH - ROW_WIDTH / 2, slot / ROW_WIDTH, rowElement(rows.get(index), index));
            }
            if (rows.isEmpty()) {
                window.setElement(0, 2, AtlasText.element("atlas-empty", WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY,
                        MessageArgs.empty(), Material.BARRIER));
            }
            controls(pageCount, rows.size());
        }

        private void controls(int pageCount, int rowCount) {
            if (page > 0) {
                UIElement previous = AtlasText.element("atlas-previous",
                        WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, MessageArgs.empty(), Material.ARROW);
                previous.onLeftClick(event -> {
                    page--;
                    repopulate();
                });
                window.setElement(-4, 5, previous);
            }
            UIElement sort = AtlasText.element("atlas-sort", WormholesMessages.PORTAL_MENU_DESTINATION_SORT,
                    AtlasText.args("mode", Wormholes.text().plain(sortLabel())), Material.COMPARATOR);
            sort.onLeftClick(event -> {
                sortMode = sortMode.next();
                page = 0;
                repopulate();
            });
            window.setElement(-3, 5, sort);

            UIElement favorites = AtlasText.element("atlas-favorites", AtlasMessages.MENU_FAVORITES,
                    AtlasText.args("state", Boolean.toString(filter == AtlasModel.Filter.FAVORITES)), Material.NETHER_STAR);
            favorites.onLeftClick(event -> setFilter(AtlasModel.Filter.FAVORITES));
            window.setElement(-2, 5, favorites);

            UIElement recents = AtlasText.element("atlas-recents", AtlasMessages.MENU_RECENTS,
                    AtlasText.args("state", Boolean.toString(filter == AtlasModel.Filter.RECENTS)), Material.CLOCK);
            recents.onLeftClick(event -> setFilter(AtlasModel.Filter.RECENTS));
            window.setElement(-1, 5, recents);

            window.setElement(0, 5, AtlasText.element("atlas-page", WormholesMessages.PORTAL_MENU_DESTINATION_PAGE,
                    AtlasText.args("page", page + 1, "pages", pageCount, "count", rowCount), Material.PAPER));

            UUID guided = state.guideTarget();
            if (guided != null) {
                UIElement guide = AtlasText.element("atlas-guide", AtlasMessages.MENU_GUIDE,
                        AtlasText.args("portal", portalName(guided)), Material.COMPASS);
                guide.onLeftClick(event -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> {
                    service.setGuideTarget(viewer, null);
                    AtlasText.send(viewer, AtlasMessages.GUIDE_CLEARED);
                    repopulate();
                }));
                window.setElement(2, 5, guide);
            }
            if (page + 1 < pageCount) {
                UIElement next = AtlasText.element("atlas-next", WormholesMessages.PORTAL_MENU_DESTINATION_NEXT,
                        MessageArgs.empty(), Material.ARROW);
                next.onLeftClick(event -> {
                    page++;
                    repopulate();
                });
                window.setElement(4, 5, next);
            }
        }

        private void setFilter(AtlasModel.Filter requested) {
            filter = filter == requested ? AtlasModel.Filter.ALL : requested;
            page = 0;
            repopulate();
        }

        private UIElement rowElement(AtlasModel.Row row, int index) {
            UIElement element = AtlasText.element("atlas-" + index, AtlasMessages.MENU_ROW, AtlasText.args(
                    "portal", row.name(),
                    "world", row.world(),
                    "destination", row.destination().isEmpty() ? row.address() : row.destination(),
                    "state", Wormholes.text().plain(row.open()
                            ? WormholesMessages.LABEL_OPEN : WormholesMessages.LABEL_CLOSED)),
                    row.isNetworked() ? Material.COMPASS : Material.ENDER_PEARL);
            element.setEnchanted(state.isFavorite(row.portalId()));
            element.onLeftClick(event -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> dial(row)));
            element.onRightClick(event -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> pin(row)));
            element.onShiftLeftClick(event -> FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> guide(row)));
            return element;
        }

        private void dial(AtlasModel.Row row) {
            LocalPortal portal = portal(row.portalId());
            if (portal == null) {
                return;
            }
            NexusPortalExtension state = portal.extension(NexusPortalExtension.class);
            if (state == null || state.networkId() == null) {
                AtlasText.send(viewer, AtlasMessages.ROW, AtlasText.args(
                        "portal", row.name(),
                        "destination", row.destination().isEmpty() ? row.world() : row.destination(),
                        "state", Wormholes.text().plain(row.open()
                                ? WormholesMessages.LABEL_OPEN : WormholesMessages.LABEL_CLOSED)));
                return;
            }
            window.close();
            dialMenu.open(portal, viewer);
        }

        private void pin(AtlasModel.Row row) {
            switch (service.toggleFavorite(viewer, row.portalId())) {
                case ADDED -> AtlasText.send(viewer, AtlasMessages.FAVORITE_ADDED, AtlasText.args("portal", row.name()));
                case REMOVED -> AtlasText.send(viewer, AtlasMessages.FAVORITE_REMOVED, AtlasText.args("portal", row.name()));
                case FULL -> AtlasText.send(viewer, AtlasMessages.FAVORITE_FULL,
                        AtlasText.args("count", settings().favoritesLimit));
            }
            repopulate();
        }

        private void guide(AtlasModel.Row row) {
            service.setGuideTarget(viewer, row.portalId());
            AtlasText.send(viewer, AtlasMessages.GUIDE_SET, AtlasText.args("portal", row.name()));
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

        private AtlasConfig settings() {
            return Wormholes.settings == null ? new AtlasConfig() : Wormholes.settings.getAtlas();
        }

        private String portalName(UUID portalId) {
            ILocalPortal portal = Wormholes.portalManager == null
                    ? null : Wormholes.portalManager.getLocalPortal(portalId);
            return portal == null ? "" : portal.getName();
        }

        private LocalPortal portal(UUID portalId) {
            ILocalPortal found = Wormholes.portalManager == null
                    ? null : Wormholes.portalManager.getLocalPortal(portalId);
            return found instanceof LocalPortal local ? local : null;
        }
    }
}
