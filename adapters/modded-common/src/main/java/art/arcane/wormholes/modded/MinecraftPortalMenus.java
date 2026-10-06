package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.optics.math.Face;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinecraftPortalMenus implements AutoCloseable {
    private static final Map<MinecraftServer, MinecraftPortalMenus> SERVICES = new HashMap<>();
    private static final long SHORT_TITLE_EXPIRY_INTERVAL_TICKS = 200L;
    private static final String GRAY_BOLD = "§7§l";

    private final WormholesModRuntime runtime;
    private final MinecraftRulesMenus rules;
    private final MinecraftPortalCosmeticsMenu cosmeticsMenu;
    private final MinecraftPortalSettingsMenu settingsMenu;
    private final MinecraftPortalCostMenu costMenu;
    private final MinecraftPortalExtensionsMenu extensionsMenu;
    private final MinecraftPortalDestinationMenu destinationMenu;
    private final MinecraftRtpMenus rtpEditor;
    private final MinecraftShortTitles shortTitles;
    private final Map<UUID, DirectionPrompt> directions = new HashMap<>();
    private final Map<UUID, PortalWindow> openMenus = new HashMap<>();
    private final Map<UUID, PortalWindow> windows = new HashMap<>();
    private MinecraftServer server;
    private long ticks;

    public MinecraftPortalMenus(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
        rules = new MinecraftRulesMenus(runtime);
        cosmeticsMenu = new MinecraftPortalCosmeticsMenu(this);
        settingsMenu = new MinecraftPortalSettingsMenu(runtime, this);
        costMenu = new MinecraftPortalCostMenu(runtime, this);
        extensionsMenu = new MinecraftPortalExtensionsMenu(this, List.of(
            new MinecraftAccessMenuEntry(runtime),
            new MinecraftRulesMenuEntry(runtime),
            new MinecraftNetworkMenuEntry(runtime),
            new MinecraftTransitMenuEntry(runtime),
            new MinecraftFidelityMenuEntry(runtime)));
        destinationMenu = new MinecraftPortalDestinationMenu(runtime, this);
        rtpEditor = new MinecraftRtpMenus(runtime);
        shortTitles = new MinecraftShortTitles(() -> runtime.server().getTickCount());
    }

    public static boolean packetPunch(ServerPlayer player) {
        MinecraftPortalMenus service = packetService(player);
        return service != null && service.runtime.attackAir(player);
    }

    public static boolean packetUse(ServerPlayer player, InteractionHand hand) {
        MinecraftPortalMenus service = packetService(player);
        return service != null && service.runtime.useItem(player, hand);
    }

    public static void directionInput(ServerPlayer player, boolean cancel) {
        MinecraftPortalMenus service = SERVICES.get(player.level().getServer());
        if (service != null) {
            service.chooseDirection(player, cancel);
        }
    }

    public static boolean captureDroppedItem(ServerPlayer player) {
        MinecraftPortalMenus service = SERVICES.get(player.level().getServer());
        return service != null && service.costMenu.captureDroppedItem(player);
    }

    public void start() {
        runtime.requireServerThread();
        server = runtime.server();
        SERVICES.put(server, this);
    }

    public void tick() {
        runtime.requireServerThread();
        ticks++;
        rtpEditor.tick();
        closeRevokedWindows();
        showDirectionTitles();
        if (ticks % SHORT_TITLE_EXPIRY_INTERVAL_TICKS == 0L) {
            shortTitles.expire();
        }
    }

    public void playerDisconnected(ServerPlayer player) {
        rtpEditor.disconnected(player);
        costMenu.disconnected(player);
        directions.remove(player.getUUID());
        openMenus.remove(player.getUUID());
        windows.remove(player.getUUID());
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        SERVICES.remove(server, this);
        rtpEditor.close();
        costMenu.close();
        shortTitles.clear();
        directions.clear();
        openMenus.clear();
        windows.clear();
        server = null;
    }

    public MinecraftRulesMenus rules() {
        return rules;
    }

    MinecraftShortTitles shortTitles() {
        return shortTitles;
    }

    boolean choosingDirection(ServerPlayer player) {
        return directions.containsKey(player.getUUID());
    }

    public void refresh(UUID portalId) {
        runtime.requireServerThread();
        for (Map.Entry<UUID, PortalWindow> entry : List.copyOf(openMenus.entrySet())) {
            PortalWindow open = entry.getValue();
            if (!open.portalId().equals(portalId)) {
                continue;
            }
            UUID viewerId = entry.getKey();
            boolean scheduled = runtime.schedule(() -> {
                MinecraftPortal portal = runtime.portals().get(portalId);
                ServerPlayer viewer = open.window().getViewer();
                if (portal == null || viewer.hasDisconnected() || !open.window().isVisible()) {
                    openMenus.remove(viewerId, open);
                    return;
                }
                rebuildPortalMenuElements(open.window(), viewer, portal);
            }, 1L);
            if (!scheduled) {
                openMenus.remove(viewerId, open);
            }
        }
    }

    public int openSelection(ServerPlayer player) {
        runtime.requireServerThread();
        destinationMenu.openSelection(player);
        return 1;
    }

    public int open(ServerPlayer player, UUID portalId) {
        runtime.requireServerThread();
        return uiOpenPortalMenu(player, runtime.portals().get(portalId)) ? 1 : 0;
    }

    public boolean transfer(ServerPlayer actor, UUID portalId, UUID newOwner) {
        runtime.requireServerThread();
        MinecraftPortal portal = runtime.portals().get(portalId);
        boolean administrator = Commands.hasPermission(Commands.LEVEL_ADMINS).test(actor.createCommandSourceStack());
        if (portal == null || !administrator && !portal.getOwner().equals(actor.getUUID())) {
            actor.sendSystemMessage(MinecraftMenuText.text(actor, WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty()));
            return false;
        }
        return update(actor, portal, target -> target.setOwner(newOwner));
    }

    MinecraftPortalSettingsMenu settings() {
        return settingsMenu;
    }

    MinecraftPortalCosmeticsMenu cosmetics() {
        return cosmeticsMenu;
    }

    MinecraftPortalCostMenu costs() {
        return costMenu;
    }

    MinecraftPortalExtensionsMenu extensions() {
        return extensionsMenu;
    }

    boolean ensureCanManage(ServerPlayer player, MinecraftPortal portal) {
        if (player == null) {
            return false;
        }
        if (portal != null && runtime.portals().get(portal.getId()) == portal && runtime.portals().canManage(player, portal)) {
            return true;
        }
        MinecraftMenuText.notice(player, MinecraftMenuText.text(player, WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty()));
        player.closeContainer();
        return false;
    }

    boolean update(ServerPlayer viewer, MinecraftPortal portal, Consumer<MinecraftPortal> mutation) {
        boolean changed = runtime.portals().update(viewer, portal.getId(), mutation);
        if (changed) {
            refresh(portal.getId());
        }
        return changed;
    }

    boolean ensureCanEditSurfaceSkin(ServerPlayer player, MinecraftPortal portal) {
        if (player == null) {
            return false;
        }
        if (!runtime.access().permission(player, "wormholes.admin")) {
            MinecraftMenuText.notice(player, MinecraftMenuText.text(player, WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty()));
            return false;
        }
        return ensureCanManage(player, portal);
    }

    void runEntity(ServerPlayer viewer, Runnable task) {
        runtime.schedule(() -> {
            if (!viewer.hasDisconnected()) {
                task.run();
            }
        }, 1L);
    }

    MinecraftWindow window(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftWindow window = new MinecraftWindow(runtime, viewer);
        window.setTitle(MinecraftPortalText.router(runtime, portal, true));
        windows.put(viewer.getUUID(), new PortalWindow(portal.getId(), window));
        return window;
    }

    boolean uiOpenPortalMenu(ServerPlayer viewer, MinecraftPortal portal) {
        if (!ensureCanManage(viewer, portal)) {
            return false;
        }
        uiCreatePortalMenu(viewer, portal).open();
        return true;
    }

    MinecraftWindow uiCreatePortalMenu(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftWindow window = window(viewer, portal);
        window.setViewportHeight(4);
        window.setDecorator(Items.STAINED_GLASS_PANE.gray());
        PortalWindow open = new PortalWindow(portal.getId(), window);
        window.onClosed(closed -> openMenus.remove(viewer.getUUID(), open));
        rebuildPortalMenuElements(window, viewer, portal);
        openMenus.put(viewer.getUUID(), open);
        return window;
    }

    void uiChangeName(ServerPlayer viewer, MinecraftPortal portal) {
        viewer.closeContainer();
        viewer.sendSystemMessage(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_PROMPT_NAME,
            MinecraftPortalText.arguments("cancel", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_INPUT_CANCEL))));
        runtime.chatInput().await(viewer, input -> {
            if (input == null || MinecraftPortalText.isCancelInput(viewer, input)) {
                uiOpenPortalMenu(viewer, portal);
                return;
            }
            update(viewer, portal, target -> target.setName(input));
            uiOpenPortalMenu(viewer, portal);
        });
    }

    void uiChooseDestination(ServerPlayer viewer, MinecraftPortal portal) {
        destinationMenu.open(viewer, portal);
    }

    void uiChooseMode(ServerPlayer viewer, MinecraftPortal portal) {
        if (portal.isManaged()) {
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_MANAGED_MODE);
            return;
        }
        MinecraftWindow window = window(viewer, portal);
        window.setViewportHeight(3);
        window.setDecorator(Items.STAINED_GLASS_PANE.gray());
        window.onClosed(closed -> runEntity(viewer, () -> uiOpenPortalMenu(viewer, portal)));
        window.setElement(0, 0, modePlacardElement(viewer, portal));
        window.setElement(-4, 1, modeOption(PortalType.PORTAL, viewer, window, portal));
        window.setElement(-2, 1, modeOption(PortalType.WORMHOLE, viewer, window, portal));
        window.setElement(0, 1, modeOption(PortalType.GATEWAY, viewer, window, portal));
        window.setElement(2, 1, modeOption(PortalType.RTP, viewer, window, portal));
        window.setElement(4, 1, mirrorModeOption(viewer, window, portal));
        window.setElement(0, 2, backToPortalMenuElement(window, viewer, portal));
        window.open();
    }

    void uiChangeDirection(ServerPlayer viewer, MinecraftPortal portal) {
        for (Component line : MinecraftMenuText.lines(runtime.localization().snapshot(viewer), WormholesMessages.PORTAL_PROMPT_DIRECTION, Map.of())) {
            viewer.sendSystemMessage(line);
        }
        directions.put(viewer.getUUID(), new DirectionPrompt(viewer, portal.getId()));
    }

    void uiOpenGatewayPairMenu(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftWindow window = window(viewer, portal);
        window.setViewportHeight(3);
        window.setDecorator(Items.STAINED_GLASS_PANE.black());
        window.setElement(0, 0, gatewayPairPlacardElement(viewer, portal));
        window.setElement(-2, 1, exportPortalElement(window, viewer, portal));
        MinecraftElement chooseDestination = MinecraftPortalText.localizedElement(viewer, "choose-gateway-destination",
            WormholesMessages.PORTAL_MENU_GATEWAY_CHOOSE, MessageArgs.empty(), Items.END_CRYSTAL);
        chooseDestination.onLeftClick(element -> {
            window.close();
            uiChooseDestination(viewer, portal);
        });
        window.setElement(0, 1, chooseDestination);
        window.setElement(2, 1, importPortalElement(window, viewer, portal));
        window.setElement(0, 2, backToPortalMenuElement(window, viewer, portal));
        window.open();
    }

    MinecraftElement backToPortalMenuElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "back-to-portal", WormholesMessages.PORTAL_MENU_BACK,
            MessageArgs.empty(), Items.ARROW);
        element.onLeftClick(clicked -> runEntity(viewer, () -> {
            window.close();
            uiOpenPortalMenu(viewer, portal);
        }));
        return element;
    }

    private void rebuildPortalMenuElements(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        window.batch(() -> {
            window.clearElements();
            window.setElement(0, 0, portalPlacardElement(viewer, portal));
            window.setElement(-2, 1, destinationElement(window, viewer, portal));
            MinecraftElement rename = MinecraftPortalText.localizedElement(viewer, "set-name", WormholesMessages.PORTAL_MENU_RENAME,
                MinecraftPortalText.arguments("portal", portal.getName()), Items.NAME_TAG);
            rename.onLeftClick(element -> uiChangeName(viewer, portal));
            window.setElement(-2, 2, rename);
            window.setElement(0, 1, projectionsElement(window, viewer, portal));
            window.setElement(2, 1, settingsOpenerElement(window, viewer, portal));
            window.setElement(0, 2, orientationOpenerElement(window, viewer, portal));
            window.setElement(2, 2, modeOpenerElement(window, viewer, portal));
            MinecraftElement destroy = MinecraftPortalText.localizedElement(viewer, "destroy", WormholesMessages.PORTAL_MENU_DELETE,
                MessageArgs.empty(), Items.GUNPOWDER);
            destroy.onShiftLeftClick(element -> {
                if (!ensureCanManage(viewer, portal)) {
                    return;
                }
                window.close();
                runtime.portals().remove(viewer, portal.getId());
            });
            window.setElement(0, 3, destroy);
        });
    }

    private MinecraftElement destinationElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        RtpSettings rtpSettings = portal.getType() == PortalType.RTP ? runtime.rtp().settings(portal) : null;
        boolean rtp = rtpSettings != null;
        boolean gateway = portal.getType() == PortalType.GATEWAY;
        String linkedDestination = MinecraftPortalText.linkedDestinationName(runtime, portal);
        MinecraftElement destination = new MinecraftElement("set-destination");
        LinesKey destinationKey = rtp
            ? WormholesMessages.PORTAL_MENU_RTP_DESTINATION
            : gateway ? WormholesMessages.PORTAL_MENU_GATEWAY_DESTINATION : WormholesMessages.PORTAL_MENU_DESTINATION;
        String destinationLabel = rtp
            ? MinecraftPortalText.rtpRotationSummary(viewer, rtpSettings)
            : linkedDestination != null ? linkedDestination : MinecraftPortalText.localized(viewer, WormholesMessages.LABEL_NONE);
        String destinationArgument = rtp ? "rotation" : "destination";
        MinecraftLegacyText.apply(viewer, destination, destinationKey, MinecraftPortalText.arguments(destinationArgument, destinationLabel));
        destination.setMaterial(rtp ? Items.COMPASS : gateway ? Items.END_CRYSTAL : Items.ENDER_EYE);
        destination.setCount(rtp ? 1 : Math.max(1, accessibleCount(portal.getType()) - 1));
        destination.onLeftClick(element -> {
            if (portal.isManaged()) {
                MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_DIMENSIONAL_LINK_MANAGED);
                return;
            }
            if (rtp) {
                window.close();
                rtpEditor.open(viewer, portal.getId());
            } else if (gateway) {
                window.close();
                uiOpenGatewayPairMenu(viewer, portal);
            } else {
                uiChooseDestination(viewer, portal);
            }
        });
        return destination;
    }

    private int accessibleCount(PortalType type) {
        int total = 0;
        int gateways = 0;
        for (MinecraftPortal candidate : runtime.portals().snapshot()) {
            total++;
            if (candidate.getType() == PortalType.GATEWAY) {
                gateways++;
            }
        }
        return type == PortalType.GATEWAY ? gateways : total - gateways;
    }

    private MinecraftElement portalPlacardElement(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("portal-placard");
        element.setMaterial(Items.BOOK);
        String linkedDestination = MinecraftPortalText.linkedDestinationName(runtime, portal);
        String facing = MinecraftPortalText.directionLabel(viewer, portal.getDirection());
        String type = MinecraftPortalText.currentModeLabel(viewer, portal);
        if (portal.getType() == PortalType.RTP) {
            RtpSettings rtpSettings = runtime.rtp().settings(portal);
            MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_PLACARD_RTP, MinecraftPortalText.arguments(
                "portal", portal.getName(),
                "type", type,
                "facing", facing,
                "allocation", MinecraftPortalText.rtpAllocationLabel(viewer, rtpSettings.getAllocationMode()),
                "rotation", MinecraftPortalText.rtpRotationSummary(viewer, rtpSettings)));
        } else if (linkedDestination != null) {
            MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_PLACARD_LINKED, MinecraftPortalText.arguments(
                "portal", portal.getName(),
                "type", type,
                "facing", facing,
                "destination", linkedDestination));
        } else {
            MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_PLACARD_UNLINKED, MinecraftPortalText.arguments(
                "portal", portal.getName(),
                "type", type,
                "facing", facing,
                "none", MinecraftPortalText.localized(viewer, WormholesMessages.LABEL_NONE)));
        }
        return element;
    }

    private MinecraftElement modePlacardElement(ServerPlayer viewer, MinecraftPortal portal) {
        return MinecraftPortalText.localizedElement(viewer, "mode-placard", WormholesMessages.PORTAL_MENU_MODE_PLACARD,
            MinecraftPortalText.arguments("mode", MinecraftPortalText.currentModeLabel(viewer, portal)), Items.BEACON);
    }

    private MinecraftElement modeOpenerElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        String description = portal.isMirrorMode()
            ? MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_MODE_DESCRIPTION_MIRROR)
            : MinecraftPortalText.modeDescription(viewer, portal.getType());
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "set-mode", WormholesMessages.PORTAL_MENU_MODE_OPENER,
            MinecraftPortalText.arguments("description", description, "mode", MinecraftPortalText.currentModeLabel(viewer, portal)),
            portal.isMirrorMode() ? Items.COPPER_TORCH : MinecraftPortalText.modeIcon(portal.getType()));
        element.setEnchanted(true);
        element.onLeftClick(clicked -> runEntity(viewer, () -> {
            window.close();
            uiChooseMode(viewer, portal);
        }));
        return element;
    }

    private MinecraftElement orientationOpenerElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "portal-orientation", WormholesMessages.PORTAL_MENU_ORIENTATION,
            orientationArguments(viewer, portal), Items.COMPASS);
        element.onLeftClick(clicked -> runEntity(viewer, () -> {
            window.close();
            uiOpenOrientationMenu(viewer, portal);
        }));
        return element;
    }

    private void uiOpenOrientationMenu(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftWindow window = window(viewer, portal);
        window.setViewportHeight(3);
        window.setDecorator(Items.STAINED_GLASS_PANE.blue());
        window.setElement(0, 0, MinecraftPortalText.localizedElement(viewer, "orientation-placard",
            WormholesMessages.PORTAL_MENU_ORIENTATION_PLACARD, orientationArguments(viewer, portal), Items.COMPASS));
        window.setElement(-3, 1, directionElement(window, viewer, portal));
        window.setElement(-1, 1, frameElement(window, viewer, portal, FrameControl.FLIP));
        window.setElement(1, 1, frameElement(window, viewer, portal, FrameControl.COUNTER_CLOCKWISE));
        window.setElement(3, 1, frameElement(window, viewer, portal, FrameControl.CLOCKWISE));
        window.setElement(0, 2, backToPortalMenuElement(window, viewer, portal));
        window.open();
    }

    private MessageArgs orientationArguments(ServerPlayer viewer, MinecraftPortal portal) {
        return MinecraftPortalText.arguments("facing", MinecraftPortalText.directionLabel(viewer, portal.getDirection()),
            "up", MinecraftPortalText.directionLabel(viewer, portal.getFrame().getUp()));
    }

    private MinecraftElement gatewayPairPlacardElement(ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("gateway-pair-placard");
        element.setMaterial(Items.RESPAWN_ANCHOR);
        String linkedDestination = MinecraftPortalText.linkedDestinationName(runtime, portal);
        if (linkedDestination == null) {
            MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_GATEWAY_UNPAIRED, MessageArgs.empty());
            return element;
        }
        MinecraftLegacyText.apply(viewer, element, WormholesMessages.PORTAL_MENU_GATEWAY_PAIRED,
            MinecraftPortalText.arguments("destination", linkedDestination));
        String peer = portal.getDestinationServer();
        if ("UNIVERSAL".equals(portal.getTunnelType()) && peer != null) {
            NetworkManager network = runtime.network().manager();
            String transport = MinecraftPortalText.localized(viewer, network.isSidebandOnlyPeer(peer)
                ? WormholesMessages.PORTAL_LABEL_SIDEBAND : WormholesMessages.PORTAL_LABEL_DIRECT);
            String state = network.isPeerReady(peer) ? transport : MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_LABEL_RECONNECTING);
            element.addLore(MinecraftLegacyText.text(viewer, WormholesMessages.PORTAL_MENU_GATEWAY_SERVER, MinecraftPortalText.arguments("server", peer)));
            element.addLore(MinecraftLegacyText.text(viewer, WormholesMessages.PORTAL_MENU_GATEWAY_LINK, MinecraftPortalText.arguments("state", state)));
        }
        return element;
    }

    private MinecraftElement exportPortalElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "export-portal", WormholesMessages.PORTAL_MENU_GATEWAY_EXPORT,
            MessageArgs.empty(), Items.PAPER);
        element.onLeftClick(clicked -> runEntity(viewer, () -> {
            window.close();
            runtime.networkTools().exportPortal(viewer.createCommandSourceStack(), portal.getId());
        }));
        return element;
    }

    private MinecraftElement importPortalElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "import-portal", WormholesMessages.PORTAL_MENU_GATEWAY_IMPORT,
            MessageArgs.empty(), Items.WRITABLE_BOOK);
        element.onLeftClick(clicked -> runEntity(viewer, () -> {
            window.close();
            viewer.closeContainer();
            viewer.sendSystemMessage(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_PROMPT_INVITE,
                MinecraftPortalText.arguments("cancel", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_INPUT_CANCEL))));
            runtime.chatInput().await(viewer, input -> {
                if (input == null || MinecraftPortalText.isCancelInput(viewer, input)) {
                    uiOpenGatewayPairMenu(viewer, portal);
                    return;
                }
                runtime.networkTools().importCode(viewer.createCommandSourceStack(), portal.getId(), input);
            });
        }));
        return element;
    }

    private MinecraftElement projectionsElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("toggle-projections");
        element.onLeftClick(clicked -> {
            ProjectionMode previous = portal.getProjectionMode();
            DimensionalPortalKind kind = portal.getDimensionalKind();
            if (kind.isReceiverOnly()) {
                MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_PROJECTION_RECEIVER_INACTIVE);
                return;
            }
            ProjectionMode next = kind == DimensionalPortalKind.END_SOURCE || kind == DimensionalPortalKind.END_EXIT ? ProjectionMode.ON : previous.next();
            update(viewer, portal, target -> target.setProjectionMode(next));
            applyProjectionMode(viewer, element, portal);
            window.updateInventory();
            if (previous != portal.getProjectionMode()) {
                MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_PROJECTION_CHANGED,
                    MinecraftPortalText.arguments("mode", MinecraftPortalText.projectionModeLabel(viewer, portal.getProjectionMode())));
            }
        });
        applyProjectionMode(viewer, element, portal);
        return element;
    }

    private void applyProjectionMode(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        boolean on = portal.getProjectionMode() == ProjectionMode.ON;
        MinecraftLegacyText.apply(viewer, element, on ? WormholesMessages.PORTAL_MENU_PROJECTION_ON : WormholesMessages.PORTAL_MENU_PROJECTION_OFF,
            MessageArgs.empty());
        element.setEnchanted(on);
        element.setMaterial(on ? Items.REDSTONE_TORCH : Items.TORCH);
    }

    private MinecraftElement settingsOpenerElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MessageArgs arguments = MinecraftPortalText.arguments(
            "access", MinecraftPortalText.permissionModeLabel(viewer, portal.getPermissionMode()),
            "send", MinecraftPortalText.onOffLabel(viewer, portal.isOutgoingTraversalsEnabled()),
            "receive", MinecraftPortalText.onOffLabel(viewer, portal.isIncomingTraversalsEnabled()),
            "depth", portal.getNetworkViewDepth(),
            "entity", portal.getNetworkViewEntityIntervalTicks());
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "portal-settings", WormholesMessages.PORTAL_MENU_SETTINGS_GATEWAY,
            arguments, Items.LEVER);
        element.onLeftClick(clicked -> runEntity(viewer, () -> {
            window.close();
            settingsMenu.open(viewer, portal);
        }));
        return element;
    }

    private MinecraftElement modeOption(PortalType target, ServerPlayer viewer, MinecraftWindow window, MinecraftPortal portal) {
        boolean current = portal.getType() == target && !portal.isMirrorMode();
        String label = MinecraftPortalText.portalTypeLabel(viewer, target);
        MinecraftElement element = new MinecraftElement("mode-" + target.name().toLowerCase(Locale.ROOT));
        MinecraftLegacyText.apply(viewer, element,
            current ? WormholesMessages.PORTAL_MENU_MODE_OPTION_SELECTED : WormholesMessages.PORTAL_MENU_MODE_OPTION_AVAILABLE,
            MinecraftPortalText.arguments("mode", label, "description", MinecraftPortalText.modeDescription(viewer, target)));
        element.setMaterial(MinecraftPortalText.modeIcon(target));
        element.setEnchanted(current);
        element.onLeftClick(clicked -> runEntity(viewer, () -> selectMode(target, label, viewer, window, portal)));
        return element;
    }

    private void selectMode(PortalType target, String label, ServerPlayer viewer, MinecraftWindow window, MinecraftPortal portal) {
        if (portal.getType() != target && !typeAllowed(viewer, target)) {
            MinecraftPortalText.notifyNearby(runtime, portal, WormholesMessages.COMMAND_NO_PERMISSION, MessageArgs.empty(), false);
            window.close();
            return;
        }
        boolean mirror = portal.isMirrorMode();
        boolean retype = portal.getType() != target;
        if ((mirror || retype) && update(viewer, portal, changed -> {
            if (mirror) {
                changed.setMirrorMode(false);
            }
            if (retype) {
                changed.setType(target);
            }
        })) {
            MinecraftPortalText.notifyNearby(runtime, portal, WormholesMessages.PORTAL_MODE_CHANGED,
                MinecraftPortalText.arguments("portal", portal.getName(), "mode", label), true);
        }
        window.close();
    }

    private boolean typeAllowed(ServerPlayer viewer, PortalType type) {
        if (runtime.access().administrator(viewer)) {
            return true;
        }
        return runtime.access().permission(viewer, type.permission());
    }

    private MinecraftElement mirrorModeOption(ServerPlayer viewer, MinecraftWindow window, MinecraftPortal portal) {
        MinecraftElement element = new MinecraftElement("mode-mirror");
        element.onLeftClick(clicked -> runEntity(viewer, () -> {
            if (!portal.isMirrorMode() && update(viewer, portal, target -> target.setMirrorMode(true))) {
                MinecraftPortalText.notifyNearby(runtime, portal, WormholesMessages.PORTAL_MODE_CHANGED, MinecraftPortalText.arguments(
                    "portal", portal.getName(), "mode", MinecraftPortalText.localized(viewer, WormholesMessages.PORTAL_LABEL_MIRROR)), true);
            }
            window.close();
        }));
        element.onRightClick(clicked -> rotateMirrorImage(element, window, viewer, portal,
            runtime.clientViews().nativeMesh(viewer) ? portal.getMirrorRotation().clockwise() : portal.getMirrorRotation().clockwiseFor(portal.getFrame())));
        element.onShiftRightClick(clicked -> rotateMirrorImage(element, window, viewer, portal,
            runtime.clientViews().nativeMesh(viewer) ? portal.getMirrorRotation().counterClockwise() : portal.getMirrorRotation().counterClockwiseFor(portal.getFrame())));
        applyMirrorModeOption(viewer, element, portal);
        return element;
    }

    private void applyMirrorModeOption(ServerPlayer viewer, MinecraftElement element, MinecraftPortal portal) {
        boolean current = portal.isMirrorMode();
        MinecraftLegacyText.apply(viewer, element,
            current ? WormholesMessages.PORTAL_MENU_MIRROR_SELECTED : WormholesMessages.PORTAL_MENU_MIRROR_AVAILABLE, MessageArgs.empty());
        element.setMaterial(Items.COPPER_TORCH);
        element.setEnchanted(current);
        if (!current) {
            return;
        }
        List<String> lore = element.getLore();
        lore.add(MinecraftLegacyText.text(viewer, WormholesMessages.PORTAL_MENU_MIRROR_ROTATION,
            MinecraftPortalText.arguments("degrees", mirrorRotation(viewer, portal).getDegrees())));
        if (runtime.clientViews().nativeMesh(viewer) || QuarterTurn.supportsQuarterTurns(portal.getFrame())) {
            lore.add(MinecraftLegacyText.text(viewer, WormholesMessages.PORTAL_MENU_MIRROR_ROTATE_CLOCKWISE));
            lore.add(MinecraftLegacyText.text(viewer, WormholesMessages.PORTAL_MENU_MIRROR_ROTATE_COUNTERCLOCKWISE));
            return;
        }
        lore.addAll(MinecraftLegacyText.lines(viewer, WormholesMessages.PORTAL_MENU_MIRROR_FLIP, MessageArgs.empty()));
    }

    private QuarterTurn mirrorRotation(ServerPlayer viewer, MinecraftPortal portal) {
        return runtime.clientViews().nativeMesh(viewer) ? portal.getMirrorRotation() : portal.getMirrorRotation().coherentFor(portal.getFrame());
    }

    private void rotateMirrorImage(MinecraftElement element, MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal,
                                   QuarterTurn rotation) {
        if (!portal.isMirrorMode()) {
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_MIRROR_SELECT_FIRST);
            return;
        }
        update(viewer, portal, target -> target.setMirrorRotation(rotation));
        applyMirrorModeOption(viewer, element, portal);
        window.updateInventory();
        MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_MIRROR_ROTATION_CHANGED,
            MinecraftPortalText.arguments("degrees", mirrorRotation(viewer, portal).getDegrees()));
    }

    private MinecraftElement directionElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, "set-direction", WormholesMessages.PORTAL_MENU_DIRECTION,
            MinecraftPortalText.arguments("direction", MinecraftPortalText.directionLabel(viewer, portal.getDirection())), Items.COMPASS);
        element.onLeftClick(clicked -> {
            window.close();
            uiChangeDirection(viewer, portal);
        });
        return element;
    }

    private MinecraftElement frameElement(MinecraftWindow window, ServerPlayer viewer, MinecraftPortal portal, FrameControl control) {
        MinecraftElement element = MinecraftPortalText.localizedElement(viewer, control.id(), control.label(),
            MinecraftPortalText.arguments("up", MinecraftPortalText.directionLabel(viewer, portal.getFrame().getUp())), control.icon());
        element.onLeftClick(clicked -> runEntity(viewer, () -> {
            Frame frame = control.apply(portal.getFrame());
            if (update(viewer, portal, target -> target.setFrame(frame))) {
                MessageArgs arguments = control == FrameControl.FLIP
                    ? MinecraftPortalText.arguments("portal", portal.getName(), "direction", MinecraftPortalText.directionLabel(viewer, portal.getDirection()))
                    : MinecraftPortalText.arguments("portal", portal.getName());
                MinecraftPortalText.notifyNearby(runtime, portal, control.notice(), arguments, true);
            }
            window.close();
            uiOpenPortalMenu(viewer, portal);
        }));
        return element;
    }

    private void chooseDirection(ServerPlayer player, boolean cancel) {
        DirectionPrompt prompt = directions.remove(player.getUUID());
        if (prompt == null) {
            return;
        }
        boolean cancelled = cancel || player.isShiftKeyDown();
        if (!cancelled) {
            MinecraftPortal portal = runtime.portals().get(prompt.portalId());
            if (portal == null) {
                return;
            }
            Vec3 look = player.getLookAngle();
            Frame frame = Frame.fromDirectionAndLook(Face.closest(look.x, look.y, look.z),
                new art.arcane.optics.math.Vec3(look.x, look.y, look.z));
            if (!update(player, portal, target -> target.setFrame(frame))) {
                return;
            }
            MinecraftPortalText.notifyNearby(runtime, portal, WormholesMessages.PORTAL_DIRECTION_CHANGED, MinecraftPortalText.arguments(
                "portal", portal.getName(), "direction", MinecraftPortalText.directionLabel(player, portal.getDirection())), true);
        }
        player.sendSystemMessage(MinecraftMenuText.text(player,
            cancelled ? WormholesMessages.PORTAL_DIRECTION_CANCELLED : WormholesMessages.PORTAL_DIRECTION_SET, MessageArgs.empty()));
    }

    private void showDirectionTitles() {
        Iterator<DirectionPrompt> iterator = directions.values().iterator();
        while (iterator.hasNext()) {
            DirectionPrompt prompt = iterator.next();
            ServerPlayer player = prompt.player();
            if (player.hasDisconnected()) {
                iterator.remove();
                continue;
            }
            Vec3 look = player.getLookAngle();
            shortTitles.send(player, prompt.portalId(),
                GRAY_BOLD + MinecraftPortalText.directionLabel(player, Face.closest(look.x, look.y, look.z)));
        }
    }

    private void closeRevokedWindows() {
        Iterator<PortalWindow> iterator = windows.values().iterator();
        while (iterator.hasNext()) {
            PortalWindow tracked = iterator.next();
            if (!tracked.window().isVisible()) {
                iterator.remove();
                continue;
            }
            MinecraftPortal portal = runtime.portals().get(tracked.portalId());
            if (portal == null || !runtime.portals().canManage(tracked.window().getViewer(), portal)) {
                iterator.remove();
                tracked.window().close();
            }
        }
    }

    private static MinecraftPortalMenus packetService(ServerPlayer player) {
        MinecraftPortalMenus service = SERVICES.get(player.level().getServer());
        if (service == null || !service.runtime.running() || player.isSpectator() || service.directions.containsKey(player.getUUID())) {
            return null;
        }
        return service;
    }

    private enum FrameControl {
        FLIP("flip-face", WormholesMessages.PORTAL_MENU_FLIP_FACE, WormholesMessages.PORTAL_FACE_FLIPPED),
        COUNTER_CLOCKWISE("rotate-counter-clockwise", WormholesMessages.PORTAL_MENU_ROTATE_COUNTERCLOCKWISE,
            WormholesMessages.PORTAL_ROTATED_COUNTERCLOCKWISE),
        CLOCKWISE("rotate-clockwise", WormholesMessages.PORTAL_MENU_ROTATE_CLOCKWISE, WormholesMessages.PORTAL_ROTATED_CLOCKWISE);

        private final String id;
        private final LinesKey label;
        private final TextKey notice;

        FrameControl(String id, LinesKey label, TextKey notice) {
            this.id = id;
            this.label = label;
            this.notice = notice;
        }

        String id() {
            return id;
        }

        LinesKey label() {
            return label;
        }

        TextKey notice() {
            return notice;
        }

        Item icon() {
            return switch (this) {
                case FLIP -> Items.TARGET;
                case COUNTER_CLOCKWISE -> Items.REPEATER;
                case CLOCKWISE -> Items.LEVER;
            };
        }

        Frame apply(Frame frame) {
            return switch (this) {
                case FLIP -> frame.flipNormal();
                case COUNTER_CLOCKWISE -> frame.rotateCounterClockwise();
                case CLOCKWISE -> frame.rotateClockwise();
            };
        }
    }

    private record DirectionPrompt(ServerPlayer player, UUID portalId) {
    }

    private record PortalWindow(UUID portalId, MinecraftWindow window) {
    }
}
