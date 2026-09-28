package art.arcane.wormholes.modded;

import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.network.PortalSettingsCodec;
import art.arcane.wormholes.portal.LocalPortalDestinationModel;
import art.arcane.wormholes.portal.ExactItemPayment;
import art.arcane.wormholes.portal.TravelCurrencyAmount;
import net.minecraft.world.item.ItemStack;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalTravelMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.MirrorRotation;
import art.arcane.wormholes.util.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.component.ItemLore;
import java.util.Locale;
import art.arcane.wormholes.portal.ProjectionMode;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MinecraftPortalMenus implements AutoCloseable {
    private static final long PROMPT_DURATION_NANOS = 60_000_000_000L;
    private static final Map<MinecraftServer, MinecraftPortalMenus> SERVICES = new HashMap<>();
    private final WormholesModRuntime runtime;
    private final MinecraftRulesMenus rules;
    private final MinecraftRtpMenus rtp;
    private final MinecraftPortalSettingsMenus settingsMenus;
    private final Map<UUID, DirectionPrompt> directions = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Prompt> prompts = new HashMap<>();
    private final Map<UUID, Capture> captures = new HashMap<>();
    private MinecraftServer server;

    public MinecraftPortalMenus(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
        rules = new MinecraftRulesMenus(runtime);
        rtp = new MinecraftRtpMenus(runtime);
        settingsMenus = new MinecraftPortalSettingsMenus(runtime);
    }

    public static boolean packetInteraction(ServerPlayer player, InteractionHand hand, boolean attack) {
        MinecraftPortalMenus service = SERVICES.get(player.level().getServer());
        if (service == null || !service.runtime.running() || player.isSpectator() || service.directions.containsKey(player.getUUID())) {
            return false;
        }
        return attack ? service.runtime.attackAir(player, hand) : service.runtime.useItem(player, hand);
    }

    public static boolean chat(ServerPlayer player, String message) {
        MinecraftPortalMenus service = SERVICES.get(player.level().getServer());
        return service != null && service.acceptChat(player, message);
    }

    public static void directionInput(ServerPlayer player, boolean cancel) {
        MinecraftPortalMenus service = SERVICES.get(player.level().getServer());
        if (service == null) {
            return;
        }
        DirectionPrompt prompt = service.directions.remove(player.getUUID());
        if (prompt == null || prompt.expiresAt() <= System.nanoTime()) {
            return;
        }
        if (cancel) {
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_DIRECTION_CANCELLED, Map.of()));
            return;
        }
        MinecraftPortal portal = service.runtime.portals().get(prompt.portalId());
        if (portal == null || portal.isManaged() || !service.runtime.portals().canManage(player, portal)) {
            return;
        }
        Vec3 look = player.getLookAngle();
        PortalFrame frame = PortalFrame.fromDirectionAndLook(Direction.closest(look.x, look.y, look.z), new GeometryVector(look.x, look.y, look.z));
        if (service.runtime.portals().update(player, portal.getId(), target -> target.setFrame(frame))) {
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_DIRECTION_SET, Map.of()));
        }
    }

    public static boolean captureDroppedItem(ServerPlayer player) {
        MinecraftPortalMenus service = SERVICES.get(player.level().getServer());
        return service != null && service.captureItem(player);
    }

    public void start() {
        runtime.requireServerThread();
        server = runtime.server();
        SERVICES.put(server, this);
    }

    public void tick() {
        runtime.requireServerThread();
        rules.tick();
        rtp.tick();
        settingsMenus.tick();
        long now = System.nanoTime();
        prompts.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        directions.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        captures.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Iterator<Session> active = sessions.values().iterator();
        while (active.hasNext()) {
            Session session = active.next();
            if (session.viewer.containerMenu != session.menu) {
                active.remove();
            } else if (!session.valid()) {
                active.remove();
                session.viewer.closeContainer();
            }
        }
    }

    public void playerDisconnected(ServerPlayer player) {
        rules.disconnected(player);
        runtime.localization().disconnected(player);
        runtime.nexus().menus().disconnected(player);
        rtp.disconnected(player);
        settingsMenus.disconnected(player);
        directions.remove(player.getUUID());
        sessions.remove(player.getUUID());
        prompts.remove(player.getUUID());
        captures.remove(player.getUUID());
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        SERVICES.remove(server, this);
        rules.close();
        rtp.close();
        settingsMenus.close();
        directions.clear();
        for (Session session : sessions.values()) {
            if (session.viewer.containerMenu == session.menu) {
                session.viewer.closeContainer();
            }
        }
        sessions.clear();
        prompts.clear();
        captures.clear();
        server = null;
    }

    public void refresh(UUID portalId) {
        runtime.requireServerThread();
        for (Session session : List.copyOf(sessions.values())) {
            if (portalId.equals(session.portalId) && session.valid() && session.viewer.containerMenu == session.menu) {
                session.menu.refresh();
            }
        }
    }

    public int openSelection(ServerPlayer player) {
        settingsMenus.disconnected(player);
        rtp.disconnected(player);
        runtime.requireServerThread();
        new Session(player, null, Page.SELECT).open();
        return 1;
    }

    public int open(ServerPlayer player, UUID portalId) {
        settingsMenus.disconnected(player);
        rtp.disconnected(player);
        runtime.requireServerThread();
        if (!runtime.portals().canManage(player, runtime.portals().get(portalId))) {
            denied(player);
            return 0;
        }
        new Session(player, portalId, Page.HOME).open();
        return 1;
    }

    public boolean acceptChat(ServerPlayer player, String message) {
        if (runtime.nexus().menus().chat(player, message) || settingsMenus.chat(player, message)) {
            return true;
        }
        runtime.requireServerThread();
        if (rules.acceptChat(player, message)) {
            return true;
        }
        Prompt prompt = prompts.remove(player.getUUID());
        if (prompt == null || prompt.expiresAt() <= System.nanoTime()) {
            return false;
        }
        MinecraftPortal portal = runtime.portals().get(prompt.portalId());
        if (!runtime.running() || !runtime.portals().canManage(player, portal)) {
            denied(player);
            return true;
        }
        String input = message.trim();
        if (!input.equalsIgnoreCase(label(player, WormholesMessages.PORTAL_INPUT_CANCEL))) {
            if (prompt.kind() == Input.NAME && !input.isEmpty()) {
                runtime.portals().update(player, portal.getId(), target -> target.setName(input));
            } else if (prompt.kind() == Input.PLAYER) {
                addPlayer(player, portal, input);
            } else if (prompt.kind() == Input.CURRENCY) {
                try {
                    runtime.portals().update(player, portal.getId(), target -> target.setCurrencyTravelCost(input));
                    player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_COST_VAULT_CHANGED,
                        Map.of("amount", TravelCurrencyAmount.parse(input).toPlainString())));
                } catch (IllegalArgumentException invalid) {
                    player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_COST_VAULT_INVALID,
                        Map.of("maximum", TravelCurrencyAmount.MAX_AMOUNT.toPlainString())));
                }
            } else if (prompt.kind() == Input.INVITE && portal.getType() == PortalType.GATEWAY) {
                runtime.networkTools().importCode(player.createCommandSourceStack(), portal.getId(), input);
            }
        }
        new Session(player, portal.getId(), prompt.kind() == Input.PLAYER ? Page.ACCESS : prompt.kind() == Input.CURRENCY ? Page.COST : Page.HOME).open();
        return true;
    }

    private boolean captureItem(ServerPlayer player) {
        runtime.requireServerThread();
        Capture capture = captures.remove(player.getUUID());
        if (capture == null || capture.expiresAt() <= System.nanoTime()) {
            return false;
        }
        MinecraftPortal portal = runtime.portals().get(capture.portalId());
        if (!runtime.portals().canManage(player, portal)) {
            denied(player);
            return true;
        }
        ItemStack selected = player.getMainHandItem();
        if (selected.isEmpty()) {
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_COST_ITEM_INVALID, Map.of()));
        } else {
            String encoded = MinecraftItemEncoding.encode(selected.copyWithCount(1), server.registryAccess());
            runtime.portals().update(player, portal.getId(), target -> target.setItemTravelCost(encoded));
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_COST_ITEM_SET,
                Map.of("item", selected.getHoverName().getString())));
        }
        new Session(player, portal.getId(), Page.COST).open();
        return true;
    }

    public boolean transfer(ServerPlayer actor, UUID portalId, UUID newOwner) {
        runtime.requireServerThread();
        MinecraftPortal portal = runtime.portals().get(portalId);
        boolean administrator = Commands.hasPermission(Commands.LEVEL_ADMINS).test(actor.createCommandSourceStack());
        if (portal == null || !administrator && !portal.getOwner().equals(actor.getUUID())) {
            denied(actor);
            return false;
        }
        return runtime.portals().update(actor, portalId, target -> target.setOwner(newOwner));
    }

    private void addPlayer(ServerPlayer actor, MinecraftPortal portal, String input) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(input);
        UUID id = online == null ? parseId(input) : online.getUUID();
        if (id == null) {
            actor.sendSystemMessage(MinecraftMenuText.text(actor, AccessMessages.PLAYER_NOT_FOUND, Map.of("name", input)));
        } else if (id.equals(portal.getOwner())) {
            actor.sendSystemMessage(MinecraftMenuText.text(actor, AccessMessages.ROLE_OWNER_ALWAYS, Map.of()));
        } else if (portal.role(id) == null) {
            runtime.portals().update(actor, portal.getId(), target -> target.setRole(id, PortalRole.USER));
        }
    }

    private static UUID parseId(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private void prompt(ServerPlayer viewer, MinecraftPortal portal, Input kind) {
        viewer.closeContainer();
        sessions.remove(viewer.getUUID());
        prompts.put(viewer.getUUID(), new Prompt(portal.getId(), kind, System.nanoTime() + PROMPT_DURATION_NANOS));
        TextKey message = switch (kind) {
            case NAME -> WormholesMessages.PORTAL_PROMPT_NAME;
            case PLAYER -> AccessMessages.PROMPT_PLAYER;
            case INVITE -> WormholesMessages.PORTAL_PROMPT_INVITE;
            case CURRENCY -> WormholesMessages.PORTAL_PROMPT_COST_VAULT;
        };
        viewer.sendSystemMessage(MinecraftMenuText.text(viewer, message, Map.of("cancel", label(viewer, WormholesMessages.PORTAL_INPUT_CANCEL))));
    }

    private static void denied(ServerPlayer player) {
        player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_EDIT_DENIED, Map.of()));
    }

    private String playerName(UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        return player == null ? id.toString() : player.getPlainTextName();
    }

    private static String label(ServerPlayer viewer, TextKey key) {
        return MinecraftMenuText.text(viewer, key, Map.of()).getString();
    }

    private static TextKey travelLabel(PortalTravelMode mode) {
        return switch (mode) {
            case BOTH -> WormholesMessages.PORTAL_LABEL_BOTH_WAYS;
            case OUTBOUND -> WormholesMessages.PORTAL_LABEL_OUTBOUND_ONLY;
            case INBOUND -> WormholesMessages.PORTAL_LABEL_INBOUND_ONLY;
            case LOCKED -> WormholesMessages.PORTAL_LABEL_LOCKED;
        };
    }

    private static TextKey roleLabel(PortalRole role) {
        return switch (role) {
            case OWNER -> AccessMessages.LABEL_OWNER;
            case CO_OWNER -> AccessMessages.LABEL_CO_OWNER;
            case USER -> AccessMessages.LABEL_USER;
            case DENIED -> AccessMessages.LABEL_DENIED;
        };
    }

    private static String enabled(ServerPlayer viewer, boolean value) {
        return label(viewer, value ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF);
    }

    private final class Session {
        private final ServerPlayer viewer;
        private final UUID portalId;
        private final Page page;
        private final Map<Integer, UUID> entries = new HashMap<>();
        private MinecraftInventoryMenu menu;
        private LocalPortalDestinationModel.SortMode sort = LocalPortalDestinationModel.SortMode.SMART;
        private int index;

        private Session(ServerPlayer viewer, UUID portalId, Page page) {
            this.viewer = viewer;
            this.portalId = portalId;
            this.page = page;
        }

        private boolean valid() {
            return runtime.running() && !viewer.hasDisconnected() && sessions.get(viewer.getUUID()) == this
                && (portalId == null || runtime.portals().canManage(viewer, runtime.portals().get(portalId)));
        }

        private void open() {
            prompts.remove(viewer.getUUID());
            captures.remove(viewer.getUUID());
            sessions.put(viewer.getUUID(), this);
            MinecraftPortal portal = portalId == null ? null : runtime.portals().get(portalId);
            MinecraftInventoryMenu.open(viewer, Component.literal(portal == null ? "Portals" : portal.getName()),
                new MinecraftInventoryMenu.Actions(this::valid, this::render, this::click));
        }

        private void render(MinecraftInventoryMenu menu) {
            this.menu = menu;
            entries.clear();
            MinecraftPortal portal = portalId == null ? null : runtime.portals().get(portalId);
            if (portalId != null && portal == null) {
                return;
            }
            switch (page) {
                case SELECT, DESTINATION -> destinations(portal);
                case HOME -> home(portal);
                case SETTINGS -> settings(portal);
                case ACCESS -> access(portal);
                case COST -> costs(portal);
                case MODE -> modes(portal);
                case ORIENTATION -> orientation(portal);
            }
        }

        private void home(MinecraftPortal portal) {
            placard(portal);
            if (portal.getType() == PortalType.RTP) {
                put(11, Items.COMPASS, WormholesMessages.PORTAL_MENU_RTP_DESTINATION,
                    Map.of("rotation", runtime.rtp().settings(portal).getRotationMode().name()));
            } else if (linkable(portal)) {
                MinecraftPortal destination = runtime.portals().get(portal.getDestinationId());
                put(11, Items.ENDER_EYE, WormholesMessages.PORTAL_MENU_DESTINATION,
                    Map.of("destination", destination == null ? label(viewer, WormholesMessages.LABEL_NONE) : destination.getName()));
            }
            put(13, portal.getProjectionMode() == ProjectionMode.ON ? Items.REDSTONE_TORCH : Items.TORCH,
                portal.getProjectionMode() == ProjectionMode.ON ? WormholesMessages.PORTAL_MENU_PROJECTION_ON : WormholesMessages.PORTAL_MENU_PROJECTION_OFF, Map.of());
            put(15, Items.LEVER, WormholesMessages.PORTAL_MENU_SETTINGS_GATEWAY, Map.of("access", permissionLabel(viewer, portal),
                "send", enabled(viewer, portal.isOutgoingTraversalsEnabled()), "receive", enabled(viewer, portal.isIncomingTraversalsEnabled()),
                "depth", portal.getNetworkViewDepth(), "entity", portal.getNetworkViewEntityIntervalTicks()));
            if (!portal.isManaged()) {
                put(22, Items.COMPARATOR, WormholesMessages.PORTAL_MENU_MODE_OPENER,
                    Map.of("mode", modeLabel(portal), "description", modeDescription(portal)));
                put(40, Items.COMPASS, WormholesMessages.PORTAL_MENU_ORIENTATION, Map.of("facing", directionLabel(portal.getDirection()),
                    "up", directionLabel(portal.getFrame().getUp())));
            }
            put(20, Items.NAME_TAG, WormholesMessages.PORTAL_MENU_RENAME, Map.of("portal", portal.getName()));
            put(24, Items.PLAYER_HEAD, AccessMessages.MENU_PLACARD, accessArguments(portal));
            if (portal.getType() == PortalType.GATEWAY) {
                put(29, Items.PAPER, WormholesMessages.PORTAL_MENU_GATEWAY_EXPORT, Map.of());
                put(33, Items.WRITABLE_BOOK, WormholesMessages.PORTAL_MENU_GATEWAY_IMPORT, Map.of());
            }
            if (!portal.isManaged()) {
                put(31, Items.GUNPOWDER, WormholesMessages.PORTAL_MENU_DELETE, Map.of());
            }
            put(49, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK, Map.of());
        }

        private void settings(MinecraftPortal portal) {
            placard(portal);
            put(10, Items.TRIPWIRE_HOOK, WormholesMessages.PORTAL_MENU_PERMISSION, Map.of("mode", permissionLabel(viewer, portal),
                "node", "wormholes.portal." + portal.setting("access.permissionKey"), "description", label(viewer, portal.getPermissionMode() == PortalPermissionMode.WHITELIST
                    ? WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_WHITELIST : WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_BLACKLIST)));
            if (portal.isMirrorMode()) {
                put(12, Items.BARRIER, WormholesMessages.PORTAL_MENU_TRAVEL_MIRROR, Map.of());
            } else if (!portal.isManaged()) {
                PortalTravelMode travel = PortalTravelMode.from(portal.isOutgoingTraversalsEnabled(), portal.isIncomingTraversalsEnabled());
                put(12, Items.RECOVERY_COMPASS, WormholesMessages.PORTAL_MENU_TRAVEL, Map.of("mode", label(viewer, travelLabel(travel)),
                    "outgoing", enabled(viewer, travel.allowsOutgoing()), "incoming", enabled(viewer, travel.allowsIncoming())));
            }
            put(14, Items.COMPARATOR, WormholesMessages.PORTAL_MENU_STREAM_QUALITY,
                Map.of("quality", label(viewer, portal.getNetworkViewQuality().labelKey()), "depth", portal.getNetworkViewDepth(),
                    "entities", portal.getNetworkViewEntityIntervalTicks(), "refresh", portal.getNetworkViewHeartbeatTicks(),
                    "grace", portal.getNetworkViewUnsubscribeGraceSeconds()));
            put(16, Items.REPEATER, WormholesMessages.PORTAL_MENU_SETTINGS_SYNC, Map.of("state", enabled(viewer, portal.isSettingsSyncEnabled())));
            int range = (int) Math.round(runtime.configuration().settings().getProjection().range);
            String rangeLabel = portal.getActivationRange() == 0 ? MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_LABEL_ACTIVATION_GLOBAL,
                Map.of("range", range)).getString() : Integer.toString(portal.getActivationRange());
            put(20, Items.LODESTONE, WormholesMessages.PORTAL_MENU_ACTIVATION_RANGE, Map.of("value", rangeLabel, "step", 8, "large_step", 32));
            put(22, Items.SPYGLASS, WormholesMessages.PORTAL_MENU_RENDER_MODE, Map.of("mode", portal.getRenderMode().displayName()));
            put(24, Items.CHEST, WormholesMessages.PORTAL_MENU_COST_OPENER, costArguments(portal));
            put(28, Items.GLASS, WormholesMessages.PORTAL_MENU_BLACKOUT,
                Map.of("state", enabled(viewer, portal.isBlackoutBackground()), "color", portal.getBlackoutColor().displayName()));
            put(30, Items.BOOK, RulesMessages.MENU_ENTRY, Map.of());
            put(32, Items.SPYGLASS, FidelityMessages.MENU_ENTRY, Map.of());
            put(34, Items.ENDER_PEARL, TransitMessages.MENU_ENTRY, Map.of());
            PortalNetwork network = runtime.nexus().networks().memberOf(portalId);
            put(40, Items.COMPASS, NexusMessages.MENU_ENTRY, Map.of("name", network == null ? "" : network.name(),
                "address", Objects.toString(portal.setting("nexus.address"), "")));
            put(49, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK, Map.of());
        }

        private String directionLabel(Direction direction) {
            return label(viewer, switch (direction) {
                case U -> WormholesMessages.PORTAL_LABEL_DIRECTION_UP;
                case D -> WormholesMessages.PORTAL_LABEL_DIRECTION_DOWN;
                case N -> WormholesMessages.PORTAL_LABEL_DIRECTION_NORTH;
                case S -> WormholesMessages.PORTAL_LABEL_DIRECTION_SOUTH;
                case E -> WormholesMessages.PORTAL_LABEL_DIRECTION_EAST;
                case W -> WormholesMessages.PORTAL_LABEL_DIRECTION_WEST;
            });
        }

        private String modeLabel(MinecraftPortal portal) {
            return portal.isMirrorMode() ? label(viewer, WormholesMessages.PORTAL_LABEL_MIRROR) : typeLabel(portal.getType());
        }

        private String typeLabel(PortalType type) {
            return label(viewer, switch (type) {
                case PORTAL -> WormholesMessages.PORTAL_LABEL_PORTAL;
                case WORMHOLE -> WormholesMessages.PORTAL_LABEL_WORMHOLE;
                case GATEWAY -> WormholesMessages.PORTAL_LABEL_GATEWAY;
                case RTP -> WormholesMessages.PORTAL_LABEL_RTP;
            });
        }

        private String modeDescription(MinecraftPortal portal) {
            return portal.isMirrorMode() ? label(viewer, WormholesMessages.PORTAL_MODE_DESCRIPTION_MIRROR) : typeDescription(portal.getType());
        }

        private String typeDescription(PortalType type) {
            return label(viewer, switch (type) {
                case PORTAL -> WormholesMessages.PORTAL_MODE_DESCRIPTION_PORTAL;
                case WORMHOLE -> WormholesMessages.PORTAL_MODE_DESCRIPTION_WORMHOLE;
                case GATEWAY -> WormholesMessages.PORTAL_MODE_DESCRIPTION_GATEWAY;
                case RTP -> WormholesMessages.PORTAL_MODE_DESCRIPTION_RTP;
            });
        }

        private void modes(MinecraftPortal portal) {
            put(4, Items.COMPARATOR, WormholesMessages.PORTAL_MENU_MODE_PLACARD, Map.of("mode", modeLabel(portal)));
            PortalType[] types = {PortalType.PORTAL, PortalType.WORMHOLE, PortalType.GATEWAY, PortalType.RTP};
            Item[] icons = {Items.ENDER_PEARL, Items.ENDER_EYE, Items.END_CRYSTAL, Items.COMPASS};
            for (int index = 0; index < types.length; index++) {
                boolean selected = !portal.isMirrorMode() && portal.getType() == types[index];
                put(10 + index * 2, icons[index], selected ? WormholesMessages.PORTAL_MENU_MODE_OPTION_SELECTED
                    : WormholesMessages.PORTAL_MENU_MODE_OPTION_AVAILABLE,
                    Map.of("mode", typeLabel(types[index]), "description", typeDescription(types[index])));
                menu.getContainer().getItem(10 + index * 2).set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, selected);
            }
            put(31, Items.COPPER_TORCH, portal.isMirrorMode() ? WormholesMessages.PORTAL_MENU_MIRROR_SELECTED
                : WormholesMessages.PORTAL_MENU_MIRROR_AVAILABLE, Map.of());
            ItemStack mirror = menu.getContainer().getItem(31);
            mirror.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, portal.isMirrorMode());
            if (portal.isMirrorMode()) {
                List<Component> lore = new ArrayList<>(mirror.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
                lore.add(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_MENU_MIRROR_ROTATION,
                    Map.of("degrees", portal.getMirrorRotation().getDegrees())));
                lore.add(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_MENU_MIRROR_ROTATE_CLOCKWISE, Map.of()));
                lore.add(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_MENU_MIRROR_ROTATE_COUNTERCLOCKWISE, Map.of()));
                mirror.set(DataComponents.LORE, new ItemLore(lore));
            }
            put(49, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK, Map.of());
        }

        private void modesClick(MinecraftPortal portal, MinecraftInventoryMenu.Click click) {
            if (click.slot() == 49) {
                MinecraftPortalMenus.this.open(viewer, portalId);
                return;
            }
            if (portal.isManaged()) {
                return;
            }
            if (click.slot() == 31) {
                if (click.right()) {
                    if (!portal.isMirrorMode()) {
                        viewer.sendSystemMessage(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_MIRROR_SELECT_FIRST, Map.of()));
                        return;
                    }
                    MirrorRotation rotation = click.shift() ? portal.getMirrorRotation().counterClockwiseFor(portal.getFrame())
                        : portal.getMirrorRotation().clockwiseFor(portal.getFrame());
                    runtime.portals().update(viewer, portalId, target -> target.setMirrorRotation(rotation));
                } else if (!click.shift()) {
                    runtime.portals().update(viewer, portalId, target -> target.setMirrorMode(true));
                }
                menu.refresh();
                return;
            }
            PortalType target = switch (click.slot()) {
                case 10 -> PortalType.PORTAL;
                case 12 -> PortalType.WORMHOLE;
                case 14 -> PortalType.GATEWAY;
                case 16 -> PortalType.RTP;
                default -> null;
            };
            if (target == null || click.right() || click.shift()) {
                return;
            }
            if (portal.getType() != target && !runtime.access().permission(viewer, "wormholes.portals." + target.name().toLowerCase(Locale.ROOT))) {
                denied(viewer);
                return;
            }
            runtime.portals().update(viewer, portalId, changed -> {
                changed.setMirrorMode(false);
                changed.setType(target);
            });
            menu.refresh();
        }

        private void orientation(MinecraftPortal portal) {
            put(4, Items.COMPASS, WormholesMessages.PORTAL_MENU_ORIENTATION_PLACARD,
                Map.of("facing", directionLabel(portal.getDirection()), "up", directionLabel(portal.getFrame().getUp())));
            put(10, Items.COMPASS, WormholesMessages.PORTAL_MENU_DIRECTION, Map.of("direction", directionLabel(portal.getDirection())));
            put(12, Items.TARGET, WormholesMessages.PORTAL_MENU_FLIP_FACE, Map.of("up", directionLabel(portal.getFrame().getUp())));
            put(14, Items.REPEATER, WormholesMessages.PORTAL_MENU_ROTATE_COUNTERCLOCKWISE, Map.of("up", directionLabel(portal.getFrame().getUp())));
            put(16, Items.LEVER, WormholesMessages.PORTAL_MENU_ROTATE_CLOCKWISE, Map.of("up", directionLabel(portal.getFrame().getUp())));
            put(49, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK, Map.of());
        }

        private void orientationClick(MinecraftPortal portal, MinecraftInventoryMenu.Click click) {
            if (click.slot() == 49) {
                MinecraftPortalMenus.this.open(viewer, portalId);
                return;
            }
            if (portal.isManaged() || click.right() || click.shift()) {
                return;
            }
            if (click.slot() == 10) {
                viewer.closeContainer();
                sessions.remove(viewer.getUUID());
                directions.put(viewer.getUUID(), new DirectionPrompt(portalId, System.nanoTime() + PROMPT_DURATION_NANOS));
                for (Component line : MinecraftMenuText.lines(runtime.localization().snapshot(viewer), WormholesMessages.PORTAL_PROMPT_DIRECTION, Map.of())) {
                    viewer.sendSystemMessage(line);
                }
                return;
            }
            PortalFrame frame = switch (click.slot()) {
                case 12 -> portal.getFrame().flipNormal();
                case 14 -> portal.getFrame().rotateCounterClockwise();
                case 16 -> portal.getFrame().rotateClockwise();
                default -> null;
            };
            if (frame != null) {
                runtime.portals().update(viewer, portalId, target -> target.setFrame(frame));
                menu.refresh();
            }
        }

        private void costs(MinecraftPortal portal) {
            Map<?, ?> price = price(portal);
            put(4, Items.CHEST, WormholesMessages.PORTAL_MENU_COST_PLACARD, costArguments(portal));
            put(11, Items.FEATHER, WormholesMessages.PORTAL_MENU_COST_MODE_FREE, Map.of());
            put(13, Items.HOPPER, WormholesMessages.PORTAL_MENU_COST_MODE_VANILLA, Map.of());
            boolean currency = runtime.costs().currencyAvailable();
            put(15, currency ? Items.EMERALD : Items.REDSTONE, currency ? WormholesMessages.PORTAL_MENU_COST_MODE_VAULT
                : WormholesMessages.PORTAL_MENU_COST_MODE_VAULT_UNAVAILABLE, Map.of());
            if ("VANILLA".equals(price.get("type"))) {
                ItemStack template = itemPrice(price);
                put(21, template.getItem(), WormholesMessages.PORTAL_MENU_COST_ITEM, Map.of("item", template.getHoverName().getString()));
                put(23, Items.CHEST, WormholesMessages.PORTAL_MENU_COST_QUANTITY,
                    Map.of("quantity", quantity(price), "maximum", ExactItemPayment.MAX_QUANTITY));
            } else if ("VAULT".equals(price.get("type"))) {
                put(21, Items.GOLD_INGOT, WormholesMessages.PORTAL_MENU_COST_VAULT_AMOUNT, Map.of("amount", price.get("amount")));
            } else {
                put(21, Items.STAINED_GLASS_PANE.white(), WormholesMessages.PORTAL_MENU_COST_FREE_DETAIL, Map.of());
            }
            put(49, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK, Map.of());
        }

        private Map<String, Object> costArguments(MinecraftPortal portal) {
            Map<?, ?> price = price(portal);
            if ("VANILLA".equals(price.get("type"))) {
                return Map.of("mode", label(viewer, WormholesMessages.PORTAL_LABEL_COST_VANILLA),
                    "cost", quantity(price) + "x " + itemPrice(price).getHoverName().getString());
            }
            if ("VAULT".equals(price.get("type"))) {
                return Map.of("mode", label(viewer, WormholesMessages.PORTAL_LABEL_COST_VAULT), "cost", price.get("amount"));
            }
            return Map.of("mode", label(viewer, WormholesMessages.PORTAL_LABEL_COST_FREE), "cost", label(viewer, WormholesMessages.PORTAL_LABEL_COST_FREE));
        }

        private ItemStack itemPrice(Map<?, ?> price) {
            return MinecraftItemEncoding.decode(Objects.toString(price.get("item"), ""), server.registryAccess());
        }

        private void costsClick(MinecraftPortal portal, MinecraftInventoryMenu.Click click) {
            Map<?, ?> price = price(portal);
            switch (click.slot()) {
                case 11 -> clearCost(portal);
                case 13 -> captureCost(portal);
                case 15 -> currencyPrompt(portal);
                case 21 -> {
                    if (click.right()) {
                        clearCost(portal);
                    } else if ("VANILLA".equals(price.get("type"))) {
                        captureCost(portal);
                    } else if ("VAULT".equals(price.get("type"))) {
                        currencyPrompt(portal);
                    }
                }
                case 23 -> {
                    if ("VANILLA".equals(price.get("type"))) {
                        int quantity = quantity(price) + (click.shift() ? 8 : 1) * (click.right() ? -1 : 1);
                        runtime.portals().update(viewer, portalId, target -> target.setTravelCostQuantity(quantity));
                        menu.refresh();
                    }
                }
                case 49 -> new Session(viewer, portalId, Page.SETTINGS).open();
                default -> { }
            }
        }

        private void currencyPrompt(MinecraftPortal portal) {
            if (!runtime.costs().currencyAvailable()) {
                viewer.sendSystemMessage(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_COST_VAULT_UNAVAILABLE_NOTICE, Map.of()));
                return;
            }
            prompt(viewer, portal, Input.CURRENCY);
        }

        private void clearCost(MinecraftPortal portal) {
            runtime.portals().update(viewer, portal.getId(), MinecraftPortal::clearTravelCost);
            menu.refresh();
            viewer.sendSystemMessage(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_COST_CLEARED, Map.of()));
        }

        private void captureCost(MinecraftPortal portal) {
            viewer.closeContainer();
            sessions.remove(viewer.getUUID());
            captures.put(viewer.getUUID(), new Capture(portal.getId(), System.nanoTime() + PROMPT_DURATION_NANOS));
            viewer.sendSystemMessage(MinecraftMenuText.text(viewer, WormholesMessages.PORTAL_PROMPT_COST_ITEM, Map.of()));
        }

        private void destinations(MinecraftPortal source) {
            List<Target> targets = new ArrayList<>();
            for (MinecraftPortal candidate : runtime.portals().snapshot()) {
                if (source == null ? !runtime.portals().canManage(viewer, candidate) : !destinationAllowed(source, candidate)) {
                    continue;
                }
                double distance = source != null && source.getWorldKey().equals(candidate.getWorldKey())
                    ? distanceSquared(source.getGeometry().getApertureCenter(), candidate.getGeometry().getApertureCenter()) : Double.MAX_VALUE;
                targets.add(new Target(candidate, new LocalPortalDestinationModel.Entry(candidate.getName(), candidate.getWorldKey(), distance,
                    source != null && candidate.getId().equals(source.getDestinationId()), false, candidate.isOpen())));
            }
            targets.sort(Comparator.comparing(Target::entry, LocalPortalDestinationModel.comparator(sort)));
            int pages = LocalPortalDestinationModel.pageCount(targets.size());
            index = LocalPortalDestinationModel.clampPage(index, pages);
            int first = LocalPortalDestinationModel.pageStart(index);
            int last = LocalPortalDestinationModel.pageEnd(targets.size(), index);
            for (int entry = first; entry < last; entry++) {
                MinecraftPortal candidate = targets.get(entry).portal();
                entries.put(entry - first, candidate.getId());
                put(entry - first, Items.ENDER_PEARL, WormholesMessages.PORTAL_MENU_LOCAL_DESTINATION,
                    Map.of("portal", candidate.getName(), "x", candidate.getOrigin().getBlockX(), "y", candidate.getOrigin().getBlockY(),
                        "z", candidate.getOrigin().getBlockZ(), "world", candidate.getWorldKey(), "direction", candidate.getDirection().name()));
                menu.getContainer().getItem(entry - first).set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, targets.get(entry).entry().linked());
            }
            if (targets.isEmpty()) {
                put(22, Items.BARRIER, WormholesMessages.PORTAL_MENU_DESTINATION_EMPTY, Map.of());
            }
            navigation(pages, targets.size());
            TextKey sortLabel = switch (sort) {
                case SMART -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_SMART;
                case NAME -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_NAME;
                case WORLD -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_WORLD;
                case DISTANCE -> WormholesMessages.PORTAL_MENU_DESTINATION_SORT_DISTANCE;
            };
            put(47, Items.COMPARATOR, WormholesMessages.PORTAL_MENU_DESTINATION_SORT, Map.of("mode", label(viewer, sortLabel)));
        }

        private void access(MinecraftPortal portal) {
            List<Map.Entry<UUID, PortalRole>> roles = new ArrayList<>(portal.getRoles().entrySet());
            int pages = LocalPortalDestinationModel.pageCount(roles.size());
            index = LocalPortalDestinationModel.clampPage(index, pages);
            int first = LocalPortalDestinationModel.pageStart(index);
            int last = LocalPortalDestinationModel.pageEnd(roles.size(), index);
            for (int entry = first; entry < last; entry++) {
                Map.Entry<UUID, PortalRole> role = roles.get(entry);
                entries.put(entry - first, role.getKey());
                put(entry - first, Items.PLAYER_HEAD, AccessMessages.MENU_ROLE,
                    Map.of("name", playerName(role.getKey()), "state", label(viewer, roleLabel(role.getValue()))));
            }
            navigation(pages, roles.size());
            put(47, Items.WRITABLE_BOOK, AccessMessages.MENU_ADD, Map.of());
            put(51, Items.NAME_TAG, AccessMessages.MENU_PLACARD, accessArguments(portal));
        }

        private void navigation(int pages, int count) {
            if (index > 0) {
                put(45, Items.ARROW, WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, Map.of());
            }
            if (index + 1 < pages) {
                put(53, Items.ARROW, WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, Map.of());
            }
            put(49, Items.PAPER, WormholesMessages.PORTAL_MENU_DESTINATION_PAGE, Map.of("page", index + 1, "pages", pages, "count", count));
            if (portalId != null) {
                put(48, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK, Map.of());
            }
        }

        private void click(MinecraftInventoryMenu.Click click) {
            if (!valid() || click.menu() != menu || viewer.containerMenu != menu) {
                return;
            }
            MinecraftPortal portal = portalId == null ? null : runtime.portals().get(portalId);
            switch (page) {
                case HOME -> homeClick(portal, click);
                case SETTINGS -> settingsClick(portal, click);
                case COST -> costsClick(portal, click);
                case MODE -> modesClick(portal, click);
                case ORIENTATION -> orientationClick(portal, click);
                case SELECT, DESTINATION, ACCESS -> listClick(portal, click);
            }
        }

        private void homeClick(MinecraftPortal portal, MinecraftInventoryMenu.Click click) {
            switch (click.slot()) {
                case 11 -> {
                    if (portal.getType() == PortalType.RTP) {
                        rtp.open(viewer, portalId);
                    } else if (linkable(portal)) {
                        new Session(viewer, portalId, Page.DESTINATION).open();
                    }
                }
                case 13 -> mutate(Map.of(PortalSettingsCodec.KEY_PROJECTION_ENABLED, Boolean.toString(portal.getProjectionMode() != ProjectionMode.ON)));
                case 15 -> new Session(viewer, portalId, Page.SETTINGS).open();
                case 20 -> prompt(viewer, portal, Input.NAME);
                case 22 -> { if (!portal.isManaged()) { new Session(viewer, portalId, Page.MODE).open(); } }
                case 40 -> { if (!portal.isManaged()) { new Session(viewer, portalId, Page.ORIENTATION).open(); } }
                case 24 -> new Session(viewer, portalId, Page.ACCESS).open();
                case 29 -> {
                    if (portal.getType() == PortalType.GATEWAY) {
                        viewer.closeContainer();
                        runtime.networkTools().exportPortal(viewer.createCommandSourceStack(), portalId);
                    }
                }
                case 33 -> {
                    if (portal.getType() == PortalType.GATEWAY) {
                        prompt(viewer, portal, Input.INVITE);
                    }
                }
                case 31 -> {
                    if (!portal.isManaged() && click.shift() && !click.right() && runtime.portals().remove(viewer, portalId)) {
                        openSelection(viewer);
                    }
                }
                case 49 -> openSelection(viewer);
                default -> { }
            }
        }

        private void settingsClick(MinecraftPortal portal, MinecraftInventoryMenu.Click click) {
            switch (click.slot()) {
                case 10 -> mutate(Map.of(PortalSettingsCodec.KEY_PERMISSION_MODE, portal.getPermissionMode().next().name()));
                case 12 -> {
                    if (!portal.isManaged() && !portal.isMirrorMode()) {
                        PortalTravelMode mode = PortalTravelMode.from(portal.isOutgoingTraversalsEnabled(), portal.isIncomingTraversalsEnabled()).next();
                        mutate(Map.of(PortalSettingsCodec.KEY_OUTGOING_TRAVERSALS, Boolean.toString(mode.allowsOutgoing()),
                            PortalSettingsCodec.KEY_INCOMING_TRAVERSALS, Boolean.toString(mode.allowsIncoming())));
                    }
                }
                case 14 -> {
                    if (click.shift()) {
                        settingsMenus.open(viewer, portalId, MinecraftPortalSettingsMenus.Page.NETWORK);
                    } else if (!click.right()) {
                        runtime.portals().update(viewer, portalId, target -> target.setNetworkViewQuality(target.getNetworkViewQuality().next()));
                        menu.refresh();
                    }
                }
                case 16 -> mutate(Map.of(PortalSettingsCodec.KEY_SETTINGS_SYNC, Boolean.toString(!portal.isSettingsSyncEnabled())));
                case 20 -> {
                    int base = portal.getActivationRange() == 0 ? (int) Math.round(runtime.configuration().settings().getProjection().range) : portal.getActivationRange();
                    int target = base + (click.right() ? -1 : 1) * (click.shift() ? 32 : 8);
                    mutate(Map.of(PortalSettingsCodec.KEY_ACTIVATION_RANGE, Integer.toString(target < 8 ? 0 : target)));
                }
                case 22 -> mutate(Map.of(PortalSettingsCodec.KEY_RENDER_MODE, portal.getRenderMode().next().name()));
                case 24 -> new Session(viewer, portalId, Page.COST).open();
                case 28 -> settingsMenus.open(viewer, portalId, MinecraftPortalSettingsMenus.Page.COSMETICS);
                case 30 -> rules.open(viewer, portalId);
                case 32 -> settingsMenus.open(viewer, portalId, MinecraftPortalSettingsMenus.Page.FIDELITY);
                case 34 -> settingsMenus.open(viewer, portalId, MinecraftPortalSettingsMenus.Page.TRANSIT);
                case 40 -> runtime.nexus().menus().openManagement(viewer, portal);
                case 49 -> MinecraftPortalMenus.this.open(viewer, portalId);
                default -> { }
            }
        }

        private void listClick(MinecraftPortal portal, MinecraftInventoryMenu.Click click) {
            UUID selected = entries.get(click.slot());
            if (selected != null) {
                if (page == Page.SELECT) {
                    MinecraftPortalMenus.this.open(viewer, selected);
                } else if (page == Page.DESTINATION) {
                    MinecraftPortal destination = runtime.portals().get(selected);
                    if (linkable(portal) && destination != null && destinationAllowed(portal, destination)
                        && runtime.portals().link(viewer, portalId, selected.equals(portal.getDestinationId()) ? null : selected)) {
                        MinecraftPortalMenus.this.open(viewer, portalId);
                    }
                } else {
                    PortalRole role = portal.role(selected);
                    if (role != null && !selected.equals(portal.getOwner())) {
                        PortalRole changed = click.shift() && !click.right() ? null : click.right() ? role.previous() : role.next();
                        runtime.portals().update(viewer, portalId, target -> target.setRole(selected, changed));
                        if (valid()) {
                            menu.refresh();
                        } else {
                            viewer.closeContainer();
                        }
                    }
                }
                return;
            }
            switch (click.slot()) {
                case 45 -> { index = Math.max(0, index - 1); menu.refresh(); }
                case 53 -> { index++; menu.refresh(); }
                case 47 -> {
                    if (page == Page.ACCESS) {
                        prompt(viewer, portal, Input.PLAYER);
                    } else {
                        sort = sort.next();
                        index = 0;
                        menu.refresh();
                    }
                }
                case 48 -> { if (portalId != null) { MinecraftPortalMenus.this.open(viewer, portalId); } }
                default -> { }
            }
        }

        private void mutate(Map<String, String> changed) {
            if (runtime.portals().update(viewer, portalId, portal -> PortalSettingsCodec.applyToLocal(portal, changed))) {
                menu.refresh();
            }
        }

        private void placard(MinecraftPortal portal) {
            put(4, Items.NAME_TAG, AccessMessages.MENU_PLACARD, accessArguments(portal));
        }

        private Map<String, Object> accessArguments(MinecraftPortal portal) {
            return Map.of("portal", portal.getName(), "owner", playerName(portal.getOwner()), "count", portal.getRoles().size(),
                "key", String.valueOf(portal.setting("access.permissionKey")));
        }

        private void put(int slot, Item material, LinesKey message, Map<String, ?> arguments) {
            menu.set(slot, MinecraftMenuText.item(viewer, material, message, arguments));
        }
    }

    private static double distanceSquared(GeometryVector first, GeometryVector second) {
        double x = first.x() - second.x();
        double y = first.y() - second.y();
        double z = first.z() - second.z();
        return x * x + y * y + z * z;
    }

    private static boolean linkable(MinecraftPortal portal) {
        return !portal.isManaged() && !portal.isMirrorMode() && portal.getType() != PortalType.RTP;
    }

    private static boolean destinationAllowed(MinecraftPortal source, MinecraftPortal candidate) {
        return !source.getId().equals(candidate.getId()) && candidate.getType() != PortalType.RTP
            && !candidate.isManaged() && !candidate.isMirrorMode()
            && (source.getType() == PortalType.GATEWAY) == (candidate.getType() == PortalType.GATEWAY);
    }

    private static String permissionLabel(ServerPlayer viewer, MinecraftPortal portal) {
        return label(viewer, portal.getPermissionMode() == PortalPermissionMode.WHITELIST ? WormholesMessages.PORTAL_LABEL_WHITELIST
            : WormholesMessages.PORTAL_LABEL_BLACKLIST);
    }

    private static Map<?, ?> price(MinecraftPortal portal) {
        return portal.setting("travelCost") instanceof Map<?, ?> price ? price : Map.of();
    }

    private static int quantity(Map<?, ?> price) {
        return ExactItemPayment.clampQuantity(price.get("quantity") instanceof Number number ? number.intValue() : 1);
    }

    private enum Page { SELECT, HOME, SETTINGS, DESTINATION, ACCESS, COST, MODE, ORIENTATION }
    private enum Input { NAME, PLAYER, INVITE, CURRENCY }
    private record DirectionPrompt(UUID portalId, long expiresAt) { }
    private record Prompt(UUID portalId, Input kind, long expiresAt) { }
    private record Capture(UUID portalId, long expiresAt) { }
    private record Target(MinecraftPortal portal, LocalPortalDestinationModel.Entry entry) { }
}
