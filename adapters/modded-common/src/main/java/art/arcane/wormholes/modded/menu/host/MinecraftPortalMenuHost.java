package art.arcane.wormholes.modded.menu.host;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.access.PortalPermissionKey;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.portal.FrameOperation;
import art.arcane.wormholes.menu.portal.NetworkNumberField;
import art.arcane.wormholes.menu.portal.PortalEdit;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.modded.MinecraftItemEncoding;
import art.arcane.wormholes.modded.MinecraftMenuText;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalText;
import art.arcane.wormholes.modded.MinecraftShortTitles;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftCapturedItem;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ExactItemPayment;
import art.arcane.wormholes.portal.PortalAccessPolicy;
import art.arcane.wormholes.portal.PortalSurfaceSkins;
import art.arcane.wormholes.portal.PortalTravelMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinecraftPortalMenuHost implements PortalMenuHost, AutoCloseable {
    private static final long SHORT_TITLE_EXPIRY_INTERVAL_TICKS = 200L;
    private static final String GRAY_BOLD = "§7§l";
    private static final String REMOTE_TUNNEL = "UNIVERSAL";
    private static final String TRAVEL_COST = "travelCost";
    private static final String VANILLA_COST = "VANILLA";
    private static final String VAULT_COST = "VAULT";
    private static final String SKIN_PERMISSION = "wormholes.admin";

    private final WormholesModRuntime runtime;
    private final MinecraftShortTitles shortTitles;
    private final Map<UUID, DirectionPrompt> directions = new HashMap<>();
    private long ticks;

    public MinecraftPortalMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        shortTitles = new MinecraftShortTitles(() -> runtime.server().getTickCount());
    }

    public static boolean packetPunch(ServerPlayer player) {
        WormholesModRuntime runtime = interacting(player);
        return runtime != null && runtime.attackAir(player);
    }

    public static boolean packetUse(ServerPlayer player, InteractionHand hand) {
        WormholesModRuntime runtime = interacting(player);
        return runtime != null && runtime.useItem(player, hand);
    }

    public static void directionInput(ServerPlayer player, boolean cancel) {
        WormholesModRuntime runtime = WormholesModRuntime.forServer(player.level().getServer());
        if (runtime != null && runtime.running()) {
            runtime.portalMenuHost().chooseDirection(player, cancel);
        }
    }

    public void tick() {
        runtime.requireServerThread();
        ticks++;
        showDirectionTitles();
        if (ticks % SHORT_TITLE_EXPIRY_INTERVAL_TICKS == 0L) {
            shortTitles.expire();
        }
    }

    public void playerDisconnected(ServerPlayer player) {
        directions.remove(player.getUUID());
    }

    @Override
    public void close() {
        shortTitles.clear();
        directions.clear();
    }

    public MinecraftShortTitles shortTitles() {
        return shortTitles;
    }

    public boolean choosingDirection(ServerPlayer player) {
        return directions.containsKey(player.getUUID());
    }

    public void chooseDirection(ServerPlayer player, boolean cancel) {
        DirectionPrompt prompt = directions.remove(player.getUUID());
        if (prompt == null) {
            return;
        }
        boolean cancelled = cancel || player.isShiftKeyDown();
        if (cancelled || applyDirection(player, prompt.portalId())) {
            player.sendSystemMessage(MinecraftMenuText.text(player,
                cancelled ? WormholesMessages.PORTAL_DIRECTION_CANCELLED : WormholesMessages.PORTAL_DIRECTION_SET, MessageArgs.empty()));
        }
        runtime.menus().resume(player.getUUID());
    }

    public Optional<ItemStack> template(String templateRef) {
        UUID portalId;
        try {
            portalId = UUID.fromString(templateRef);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null || !(portal.setting(TRAVEL_COST) instanceof Map<?, ?> price) || !VANILLA_COST.equals(price.get("type"))) {
            return Optional.empty();
        }
        try {
            return Optional.of(MinecraftItemEncoding.decode(Objects.toString(price.get("item"), ""), runtime.server().registryAccess()));
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }

    public boolean applySurfaceSkin(ServerPlayer player, MinecraftPortal portal, String skin) {
        if (!runtime.access().permission(player, SKIN_PERMISSION)) {
            MinecraftMenuText.notice(player, MinecraftMenuText.text(player, WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty()));
            return false;
        }
        if (runtime.portals().get(portal.getId()) != portal || !runtime.portals().canManage(player, portal)) {
            MinecraftMenuText.notice(player, MinecraftMenuText.text(player, WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty()));
            player.closeContainer();
            return false;
        }
        String previous = portal.getSurfaceSkin();
        if (runtime.portals().update(player, portal.getId(), target -> target.setSurfaceSkin(skin))) {
            invalidate(portal.getId());
        }
        String current = portal.getSurfaceSkin();
        if (!previous.equals(current)) {
            MinecraftPortalText.notifySetting(player, portal, WormholesMessages.PORTAL_NETWORK_VALUE_CHANGED, MinecraftPortalText.arguments(
                "label", MinecraftPortalText.localized(player, WormholesMessages.PORTAL_NETWORK_LABEL_SURFACE_SKIN),
                "value", current.isEmpty() ? MinecraftPortalText.localized(player, WormholesMessages.PORTAL_LABEL_SKIN_NONE) : current));
        }
        return true;
    }

    @Override
    public Optional<PortalMenuView> portal(UUID portalId) {
        return Optional.ofNullable(runtime.portals().get(portalId)).map(this::view);
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        return portal != null && runtime.portals().canManage(MinecraftMenuViewer.player(viewer), portal);
    }

    @Override
    public boolean ownerOrAdmin(MenuViewer viewer, UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        return portal != null && PortalAccessPolicy.canManage(portal.getId(), portal.getOwner(), viewer.id(), viewer.administrator());
    }

    @Override
    public boolean typeAllowed(MenuViewer viewer, PortalType type) {
        return viewer.administrator() || viewer.hasPermission(type.permission());
    }

    @Override
    public boolean canEditSurfaceSkin(MenuViewer viewer, UUID portalId) {
        return viewer.hasPermission(SKIN_PERMISSION) && canManage(viewer, portalId);
    }

    @Override
    public PortalEditOutcome edit(MenuViewer viewer, UUID portalId, PortalEdit edit) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null) {
            return denied();
        }
        Optional<PortalEditOutcome> refusal = refusal(portal, edit);
        if (refusal.isPresent()) {
            return refusal.get();
        }
        if (!runtime.portals().update(MinecraftMenuViewer.player(viewer), portalId, mutation(portal, edit))) {
            return denied();
        }
        invalidate(portalId);
        return PortalEditOutcome.applied();
    }

    @Override
    public void destroy(MenuViewer viewer, UUID portalId) {
        runtime.portals().remove(MinecraftMenuViewer.player(viewer), portalId);
    }

    @Override
    public void announce(UUID portalId, TextKey key, MessageArgs arguments, boolean success) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal != null) {
            MinecraftPortalText.notifyNearby(runtime, portal, key, arguments, success);
        }
    }

    @Override
    public int globalActivationRange() {
        return (int) Math.round(runtime.configuration().settings().getProjection().range);
    }

    @Override
    public boolean beginDirectionPrompt(MenuViewer viewer, UUID portalId) {
        if (runtime.portals().get(portalId) == null) {
            return false;
        }
        ServerPlayer player = MinecraftMenuViewer.player(viewer);
        for (Component line : MinecraftMenuText.lines(runtime.localization().snapshot(player), WormholesMessages.PORTAL_PROMPT_DIRECTION, Map.of())) {
            player.sendSystemMessage(line);
        }
        directions.put(player.getUUID(), new DirectionPrompt(player, portalId));
        return true;
    }

    @Override
    public String routerTitle(UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        return portal == null ? "" : MinecraftPortalText.router(runtime, portal, true);
    }

    private boolean applyDirection(ServerPlayer player, UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null) {
            return false;
        }
        Vec3 look = player.getLookAngle();
        Frame frame = Frame.fromDirectionAndLook(Face.closest(look.x, look.y, look.z), new Vec3d(look.x, look.y, look.z));
        if (!runtime.portals().update(player, portalId, target -> target.setFrame(frame))) {
            return false;
        }
        invalidate(portalId);
        MinecraftPortalText.notifyNearby(runtime, portal, WormholesMessages.PORTAL_DIRECTION_CHANGED, MinecraftPortalText.arguments(
            "portal", portal.getName(), "direction", MinecraftPortalText.directionLabel(player, portal.getDirection())), true);
        return true;
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
            shortTitles.send(player, prompt.portalId(), GRAY_BOLD + MinecraftPortalText.directionLabel(player, Face.closest(look.x, look.y, look.z)));
        }
    }

    private void invalidate(UUID portalId) {
        runtime.menus().invalidate(new MenuScope.Portal(portalId));
    }

    private Optional<PortalEditOutcome> refusal(MinecraftPortal portal, PortalEdit edit) {
        return switch (edit) {
            case PortalEdit.Projection _ when portal.getDimensionalKind().isReceiverOnly(twoWayEnd()) ->
                refused(WormholesMessages.PORTAL_PROJECTION_RECEIVER_INACTIVE, MessageArgs.empty());
            case PortalEdit.MirrorRotation _ when !portal.isMirrorMode() ->
                refused(WormholesMessages.PORTAL_MIRROR_SELECT_FIRST, MessageArgs.empty());
            case PortalEdit.TravelMode _ when portal.getDimensionalKind().isManagedPortal() ->
                refused(WormholesMessages.PORTAL_TRAVEL_MANAGED, MessageArgs.empty());
            case PortalEdit.TravelMode _ when portal.isMirrorMode() ->
                refused(WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED, MessageArgs.empty());
            case PortalEdit.ApertureShape shape when !portal.acceptsApertureShape(shape.shape()) ->
                refused(WormholesMessages.PORTAL_APERTURE_SHAPE_TOO_SMALL, MinecraftPortalText.arguments("shape", shape.shape().format()));
            default -> Optional.empty();
        };
    }

    private Consumer<MinecraftPortal> mutation(MinecraftPortal portal, PortalEdit edit) {
        return switch (edit) {
            case PortalEdit.Name name -> target -> target.setName(name.name());
            case PortalEdit.Projection projection -> target -> target.setProjectionMode(projectionMode(portal.getDimensionalKind(), projection.mode()));
            case PortalEdit.Type type -> target -> target.setType(type.type());
            case PortalEdit.Mirror mirror -> target -> target.setMirrorMode(mirror.enabled());
            case PortalEdit.MirrorRotation rotation -> target -> target.setMirrorRotation(rotation.clockwise()
                ? target.getMirrorRotation().clockwiseFor(target.getFrame())
                : target.getMirrorRotation().counterClockwiseFor(target.getFrame()));
            case PortalEdit.FrameChange change -> target -> target.setFrame(frame(target.getFrame(), change.operation()));
            case PortalEdit.PermissionMode mode -> target -> target.setPermissionMode(mode.mode());
            case PortalEdit.TravelMode mode -> target -> {
                target.setOutgoingTraversalsEnabled(mode.mode().allowsOutgoing());
                target.setIncomingTraversalsEnabled(mode.mode().allowsIncoming());
            };
            case PortalEdit.Quality quality -> target -> target.setNetworkViewQuality(quality.quality());
            case PortalEdit.NetworkNumber number -> target -> networkNumber(target, number.field(), number.value());
            case PortalEdit.SettingsSync sync -> target -> target.setSettingsSyncEnabled(sync.enabled());
            case PortalEdit.Blackout blackout -> target -> target.setBlackoutBackground(blackout.enabled());
            case PortalEdit.BlackoutColorChoice color -> target -> target.setBlackoutColor(color.color());
            case PortalEdit.ActivationRange range -> target -> target.setActivationRange(range.blocks());
            case PortalEdit.RenderMode mode -> target -> target.setRenderMode(mode.mode());
            case PortalEdit.AmbientStyle style -> target -> target.setAmbientStyle(style.style());
            case PortalEdit.AmbientColor color -> target -> target.setAmbientColor(color.rgb());
            case PortalEdit.ApertureShape shape -> target -> target.setApertureShape(shape.shape());
            case PortalEdit.SurfaceSkin skin -> target -> target.setSurfaceSkin(skin.skin());
            case PortalEdit.FallbackBlock block -> target -> target.setNetworkViewFallbackBlock(block.blockState());
            case PortalEdit.PublicLookLabel label -> target -> target.setPublicLookLabel(label.enabled());
        };
    }

    private ProjectionMode projectionMode(DimensionalPortalKind kind, ProjectionMode requested) {
        ProjectionMode managed = kind.managedProjection(twoWayEnd());
        return managed != null ? managed : requested;
    }

    private boolean twoWayEnd() {
        return runtime.configuration().settings().getMain().twoWayEndPortals;
    }

    private static void networkNumber(MinecraftPortal portal, NetworkNumberField field, int value) {
        switch (field) {
            case DEPTH -> portal.setNetworkViewDepth(value);
            case HEARTBEAT_TICKS -> portal.setNetworkViewHeartbeatTicks(value);
            case ENTITY_INTERVAL_TICKS -> portal.setNetworkViewEntityIntervalTicks(value);
            case UNSUBSCRIBE_GRACE_SECONDS -> portal.setNetworkViewUnsubscribeGraceSeconds(value);
        }
    }

    private static Frame frame(Frame frame, FrameOperation operation) {
        return switch (operation) {
            case FLIP_FACE -> frame.flipNormal();
            case ROTATE_CLOCKWISE -> frame.rotateClockwise();
            case ROTATE_COUNTER_CLOCKWISE -> frame.rotateCounterClockwise();
        };
    }

    private PortalMenuView view(MinecraftPortal portal) {
        return PortalMenuView.builder(portal.getId(), portal.getName())
            .owner(portal.getOwner())
            .type(portal.getType())
            .mirror(portal.isMirrorMode())
            .mirrorRotation(portal.getMirrorRotation())
            .frame(portal.getFrame())
            .dimensionalKind(portal.getDimensionalKind())
            .twoWayEnd(twoWayEnd())
            .projection(portal.getProjectionMode())
            .destination(destination(portal))
            .routerTitle(MinecraftPortalText.router(runtime, portal, true))
            .permissionMode(portal.getPermissionMode())
            .permissionNode(PortalPermissionKey.node(PortalPermissionKey.sanitize(portal.getName())))
            .travelMode(PortalTravelMode.from(portal.isOutgoingTraversalsEnabled(), portal.isIncomingTraversalsEnabled()))
            .quality(portal.getNetworkViewQuality())
            .networkViewDepth(portal.getNetworkViewDepth())
            .networkViewHeartbeatTicks(portal.getNetworkViewHeartbeatTicks())
            .networkViewEntityIntervalTicks(portal.getNetworkViewEntityIntervalTicks())
            .networkViewUnsubscribeGraceSeconds(portal.getNetworkViewUnsubscribeGraceSeconds())
            .settingsSync(portal.isSettingsSyncEnabled())
            .blackout(portal.isBlackoutBackground())
            .blackoutColor(portal.getBlackoutColor())
            .activationRange(portal.getActivationRange())
            .renderMode(portal.getRenderMode())
            .ambientStyle(portal.getAmbientStyle())
            .ambientColor(portal.getAmbientColor())
            .apertureShape(portal.getApertureShape())
            .openCellCount(portal.getGeometry().getBlockPositions().size())
            .surfaceSkin(portal.getSurfaceSkin())
            .surfaceSkinItemId(surfaceSkinItemId(portal.getSurfaceSkin()))
            .fallbackBlock(portal.getNetworkViewFallbackBlock())
            .publicLookLabel(portal.isPublicLookLabel())
            .cost(cost(portal))
            .accessibleCount(accessibleCount(portal.getType()))
            .rtp(rtp(portal))
            .build();
    }

    private PortalMenuView.Destination destination(MinecraftPortal portal) {
        String name = MinecraftPortalText.linkedDestinationName(runtime, portal);
        if (name == null) {
            return null;
        }
        boolean remote = REMOTE_TUNNEL.equals(portal.getTunnelType());
        return new PortalMenuView.Destination(name, remote, remote ? portal.getDestinationServer() : null);
    }

    private PortalMenuView.Cost cost(MinecraftPortal portal) {
        if (!(portal.setting(TRAVEL_COST) instanceof Map<?, ?> price)) {
            return PortalMenuView.Cost.FREE;
        }
        if (VAULT_COST.equals(price.get("type"))) {
            return new PortalMenuView.Cost(PortalMenuView.CostMode.VAULT, null, null, 0, Objects.toString(price.get("amount"), ""), null);
        }
        if (!VANILLA_COST.equals(price.get("type"))) {
            return PortalMenuView.Cost.FREE;
        }
        int quantity = ExactItemPayment.clampQuantity(price.get("quantity") instanceof Number number ? number.intValue() : 1);
        Optional<ItemStack> template = template(portal.getId().toString());
        String itemId = template.map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).orElse(null);
        String label = template.map(stack -> MinecraftCapturedItem.label(stack.getItem())).orElse(null);
        return new PortalMenuView.Cost(PortalMenuView.CostMode.VANILLA, itemId, label, quantity, null, portal.getId().toString());
    }

    private PortalMenuView.Rtp rtp(MinecraftPortal portal) {
        if (portal.getType() != PortalType.RTP) {
            return null;
        }
        RtpSettings settings = runtime.rtp().settings(portal);
        return settings == null ? null : new PortalMenuView.Rtp(settings.getAllocationMode(), settings.getRotationMode(), settings.getCycleDurationMillis());
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

    private static String surfaceSkinItemId(String skin) {
        return BuiltInRegistries.ITEM.getKey(surfaceSkinItem(skin)).toString();
    }

    private static Item surfaceSkinItem(String skin) {
        if (skin.isEmpty()) {
            return Items.GLASS;
        }
        if (PortalSurfaceSkins.isFluid(skin)) {
            return skin.contains("lava") ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
        }
        try {
            Item item = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, skin, false).blockState().getBlock().asItem();
            return item == Items.AIR ? Items.GLASS : item;
        } catch (CommandSyntaxException invalid) {
            return Items.GLASS;
        }
    }

    private static Optional<PortalEditOutcome> refused(TextKey reason, MessageArgs arguments) {
        return Optional.of(PortalEditOutcome.refused(reason, arguments));
    }

    private static PortalEditOutcome denied() {
        return PortalEditOutcome.refused(WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty());
    }

    private static WormholesModRuntime interacting(ServerPlayer player) {
        WormholesModRuntime runtime = WormholesModRuntime.forServer(player.level().getServer());
        if (runtime == null || !runtime.running() || player.isSpectator() || runtime.portalMenuHost().choosingDirection(player)) {
            return null;
        }
        return runtime;
    }

    private record DirectionPrompt(ServerPlayer player, UUID portalId) {
    }
}
