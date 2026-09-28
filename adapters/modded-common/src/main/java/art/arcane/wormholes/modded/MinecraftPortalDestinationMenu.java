package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.LocalPortalDestinationModel;
import art.arcane.wormholes.portal.LocalPortalDestinationModel.Entry;
import art.arcane.wormholes.portal.LocalPortalDestinationModel.SortMode;
import art.arcane.wormholes.portal.PortalAccessPolicy;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.RemotePortal;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class MinecraftPortalDestinationMenu {
    private static final int ROW_WIDTH = 9;
    private static final String REMOTE_TUNNEL = "UNIVERSAL";

    private final WormholesModRuntime runtime;
    private final MinecraftPortalMenus menus;

    MinecraftPortalDestinationMenu(WormholesModRuntime runtime, MinecraftPortalMenus menus) {
        this.runtime = Objects.requireNonNull(runtime);
        this.menus = Objects.requireNonNull(menus);
    }

    void open(ServerPlayer p, MinecraftPortal portal) {
        if (portal.getType() == PortalType.RTP) {
            MinecraftPortalText.notifySetting(p, portal, WormholesMessages.PORTAL_RTP_CANNOT_LINK);
            return;
        }
        if (portal.isMirrorMode()) {
            MinecraftPortalText.notifySetting(p, portal, WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED);
            return;
        }
        if (portal.isManaged()) {
            MinecraftPortalText.notifySetting(p, portal, WormholesMessages.PORTAL_DIMENSIONAL_LINK_MANAGED);
            return;
        }
        new Session(p, portal).open();
    }

    void openSelection(ServerPlayer p) {
        new Session(p, null).open();
    }

    private static boolean isLinkedToLocal(MinecraftPortal portal, MinecraftPortal target) {
        return !REMOTE_TUNNEL.equals(portal.getTunnelType()) && target.getId().equals(portal.getDestinationId());
    }

    private static boolean isLinkedToRemote(MinecraftPortal portal, RemotePortal target) {
        return REMOTE_TUNNEL.equals(portal.getTunnelType())
            && target.getServer().getName().equals(portal.getDestinationServer())
            && target.getId().equals(portal.getDestinationId());
    }

    private double localDestinationDistanceSquared(MinecraftPortal portal, MinecraftPortal target) {
        if (!portal.getWorldKey().equals(target.getWorldKey())) {
            return Double.MAX_VALUE;
        }
        GeometryVector source = portal.getGeometry().getApertureCenter();
        GeometryVector destination = target.getGeometry().getApertureCenter();
        double x = source.x() - destination.x();
        double y = source.y() - destination.y();
        double z = source.z() - destination.z();
        return x * x + y * y + z * z;
    }

    private record Target(Entry entry, MinecraftPortal local, RemotePortal remote) {
    }

    private final class Session {
        private final ServerPlayer viewer;
        private final MinecraftPortal portal;
        private final MinecraftWindow window;
        private SortMode sortMode;
        private int page;

        private Session(ServerPlayer viewer, MinecraftPortal portal) {
            this.viewer = viewer;
            this.portal = portal;
            sortMode = SortMode.SMART;
            if (portal == null) {
                window = new MinecraftWindow(runtime, viewer);
            } else {
                window = menus.window(viewer, portal);
                window.onClosed(closed -> menus.runEntity(viewer, () -> menus.uiOpenPortalMenu(viewer, portal)));
            }
            window.setDecorator(Items.STAINED_GLASS_PANE.gray());
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
            List<Target> targets = collectTargets();
            targets.sort(Comparator.comparing(Target::entry, LocalPortalDestinationModel.comparator(sortMode)));
            int pageCount = LocalPortalDestinationModel.pageCount(targets.size());
            page = LocalPortalDestinationModel.clampPage(page, pageCount);
            int start = LocalPortalDestinationModel.pageStart(page);
            int end = LocalPortalDestinationModel.pageEnd(targets.size(), page);
            for (int index = start; index < end; index++) {
                Target target = targets.get(index);
                int slot = index - start;
                int row = slot / ROW_WIDTH;
                int position = slot % ROW_WIDTH - ROW_WIDTH / 2;
                window.setElement(position, row, target.local() != null
                    ? localElement(target.local(), index)
                    : remoteElement(target.remote(), index));
            }
            if (targets.isEmpty()) {
                window.setElement(0, 2, MinecraftPortalText.localizedElement(viewer, "destination-empty",
                    WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY, MessageArgs.empty(), Items.BARRIER));
            }
            if (page > 0) {
                MinecraftElement previous = MinecraftPortalText.localizedElement(viewer, "destination-previous",
                    WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, MessageArgs.empty(), Items.ARROW);
                previous.onLeftClick(clicked -> {
                    page--;
                    repopulate();
                });
                window.setElement(-4, 5, previous);
            }
            MinecraftElement sort = MinecraftPortalText.localizedElement(viewer, "destination-sort", WormholesMessages.PORTAL_MENU_DESTINATION_SORT,
                MinecraftPortalText.arguments("mode", MinecraftPortalText.localized(viewer, sortLabel(sortMode))), Items.COMPARATOR);
            sort.onLeftClick(clicked -> {
                sortMode = sortMode.next();
                page = 0;
                repopulate();
            });
            window.setElement(-2, 5, sort);
            MinecraftPortal returnTarget = reciprocalTarget();
            if (returnTarget != null) {
                MinecraftElement linkAndReturn = MinecraftPortalText.localizedElement(viewer, "destination-link-return",
                    NexusMessages.MENU_LINK_AND_RETURN,
                    MinecraftPortalText.arguments("portal", portal.getName(), "destination", returnTarget.getName()), Items.ENDER_EYE);
                linkAndReturn.onLeftClick(clicked -> menus.runEntity(viewer, () -> linkAndReturn(returnTarget)));
                window.setElement(2, 5, linkAndReturn);
            }
            window.setElement(0, 5, MinecraftPortalText.localizedElement(viewer, "destination-page", WormholesMessages.PORTAL_MENU_DESTINATION_PAGE,
                MinecraftPortalText.arguments("page", page + 1, "pages", pageCount, "count", targets.size()), Items.PAPER));
            if (page + 1 < pageCount) {
                MinecraftElement next = MinecraftPortalText.localizedElement(viewer, "destination-next",
                    WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, MessageArgs.empty(), Items.ARROW);
                next.onLeftClick(clicked -> {
                    page++;
                    repopulate();
                });
                window.setElement(4, 5, next);
            }
        }

        private MinecraftPortal reciprocalTarget() {
            if (portal == null || REMOTE_TUNNEL.equals(portal.getTunnelType()) || portal.getDestinationId() == null) {
                return null;
            }
            MinecraftPortal target = runtime.portals().get(portal.getDestinationId());
            if (target == null || target.getId().equals(portal.getId())) {
                return null;
            }
            boolean administrator = runtime.access().administrator(viewer);
            UUID viewerId = viewer.getUUID();
            if (!PortalAccessPolicy.canManage(portal.getId(), portal.getOwner(), viewerId, administrator)
                || !PortalAccessPolicy.canManage(target.getId(), target.getOwner(), viewerId, administrator)) {
                return null;
            }
            return target;
        }

        private void linkAndReturn(MinecraftPortal target) {
            window.close();
            boolean paired;
            try {
                runtime.nexus().pair(viewer, portal, target, true);
                paired = true;
            } catch (IllegalArgumentException denied) {
                paired = false;
            }
            if (paired) {
                menus.refresh(portal.getId());
                menus.refresh(target.getId());
            }
            MinecraftPortalText.notifySetting(viewer, portal, paired ? NexusMessages.RECIPROCAL_PAIRED : NexusMessages.RECIPROCAL_DENIED,
                MinecraftPortalText.arguments("portal", portal.getName(), "destination", target.getName()));
        }

        private List<Target> collectTargets() {
            List<Target> targets = new ArrayList<>();
            for (MinecraftPortal candidate : runtime.portals().snapshot()) {
                if (portal == null) {
                    if (runtime.portals().canManage(viewer, candidate)) {
                        targets.add(new Target(new Entry(candidate.getName(), candidate.getWorldKey(), Double.MAX_VALUE, false, false, true),
                            candidate, null));
                    }
                    continue;
                }
                if (candidate.getId().equals(portal.getId()) || candidate.getType() == PortalType.RTP) {
                    continue;
                }
                if ((candidate.getType() == PortalType.GATEWAY) != (portal.getType() == PortalType.GATEWAY)) {
                    continue;
                }
                if (!candidate.getDimensionalKind().isGenericDestination()) {
                    continue;
                }
                if (runtime.portals().resolveLevel(candidate) == null) {
                    continue;
                }
                targets.add(new Target(new Entry(candidate.getName(), candidate.getWorldKey(), localDestinationDistanceSquared(portal, candidate),
                    isLinkedToLocal(portal, candidate), false, true), candidate, null));
            }
            if (portal != null && portal.getType() == PortalType.GATEWAY) {
                for (RemotePortal candidate : runtime.network().remotePortals().all()) {
                    if (candidate.getType() != PortalType.GATEWAY) {
                        continue;
                    }
                    targets.add(new Target(new Entry(candidate.getName(), candidate.getServer().getName(), Double.MAX_VALUE,
                        isLinkedToRemote(portal, candidate), true, candidate.isOpen()), null, candidate));
                }
            }
            return targets;
        }

        private MinecraftElement localElement(MinecraftPortal target, int index) {
            GeometryVector center = target.getGeometry().getApertureCenter();
            MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "portal-" + index,
                WormholesMessages.PORTAL_MENU_LOCAL_DESTINATION, MinecraftPortalText.arguments(
                    "portal", target.getName(),
                    "x", center.getBlockX(),
                    "y", center.getBlockY(),
                    "z", center.getBlockZ(),
                    "world", target.getWorldKey(),
                    "direction", MinecraftPortalText.directionLabel(viewer, target.getDirection())),
                Items.ENDER_PEARL);
            if (portal == null) {
                element.onLeftClick(clicked -> menus.runEntity(viewer, () -> {
                    window.close();
                    menus.uiOpenPortalMenu(viewer, target);
                }));
                return element;
            }
            element.setEnchanted(isLinkedToLocal(portal, target));
            element.onLeftClick(clicked -> menus.runEntity(viewer, () -> {
                window.close();
                if (isLinkedToLocal(portal, target)) {
                    if (runtime.portals().link(viewer, portal.getId(), null)) {
                        menus.refresh(portal.getId());
                    }
                    MinecraftPortalText.notifyNearby(runtime, portal, WormholesMessages.PORTAL_UNLINKED,
                        MinecraftPortalText.arguments("portal", portal.getName(), "destination", target.getName()), true);
                } else {
                    if (runtime.portals().link(viewer, portal.getId(), target.getId())) {
                        menus.refresh(portal.getId());
                    }
                    MinecraftPortalText.notifyNearby(runtime, portal, WormholesMessages.PORTAL_LINKED,
                        MinecraftPortalText.arguments("portal", portal.getName(), "destination", target.getName()), true);
                }
            }));
            return element;
        }

        private MinecraftElement remoteElement(RemotePortal target, int index) {
            boolean linked = isLinkedToRemote(portal, target);
            MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "remote-portal-" + index,
                WormholesMessages.PORTAL_MENU_REMOTE_DESTINATION, MinecraftPortalText.arguments(
                    "portal", target.getName(),
                    "server", target.getServer().getName(),
                    "x", target.getOrigin().getBlockX(),
                    "y", target.getOrigin().getBlockY(),
                    "z", target.getOrigin().getBlockZ(),
                    "world", target.getServer().getWorld(),
                    "direction", MinecraftPortalText.directionLabel(viewer, target.getDirection()),
                    "state", MinecraftPortalText.localized(viewer, target.isOpen() ? WormholesMessages.LABEL_OPEN : WormholesMessages.LABEL_CLOSED)),
                Items.END_CRYSTAL);
            element.setEnchanted(linked);
            element.onLeftClick(clicked -> menus.runEntity(viewer, () -> {
                window.close();
                if (isLinkedToRemote(portal, target)) {
                    if (runtime.portals().link(viewer, portal.getId(), null)) {
                        menus.refresh(portal.getId());
                    }
                    MinecraftPortalText.notifyNearby(runtime, portal, WormholesMessages.PORTAL_UNLINKED,
                        MinecraftPortalText.arguments("portal", portal.getName(), "destination", target.getName()), true);
                } else {
                    menus.update(viewer, portal, source -> source.linkRemote(target.getServer().getName(), target.getId()));
                    MinecraftPortalText.notifyNearby(runtime, portal, WormholesMessages.PORTAL_LINKED_REMOTE, MinecraftPortalText.arguments(
                        "portal", portal.getName(),
                        "destination", target.getName(),
                        "server", target.getServer().getName()), true);
                }
            }));
            return element;
        }

        private TextKey sortLabel(SortMode mode) {
            return switch (mode) {
                case SMART -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_SMART;
                case NAME -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_NAME;
                case WORLD -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_WORLD;
                case DISTANCE -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_DISTANCE;
            };
        }
    }
}
