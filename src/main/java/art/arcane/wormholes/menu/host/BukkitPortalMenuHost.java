package art.arcane.wormholes.menu.host;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import art.arcane.optics.frame.Frame;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.portal.FrameOperation;
import art.arcane.wormholes.menu.portal.NetworkNumberField;
import art.arcane.wormholes.menu.portal.PortalEdit;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalAccessPolicy;
import art.arcane.wormholes.portal.PortalSurfaceSkins;
import art.arcane.wormholes.portal.PortalTravelCost;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.PortalTypeAccess;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.portal.VanillaTravelCost;
import art.arcane.wormholes.portal.VaultTravelCost;
import art.arcane.wormholes.portal.rtp.RtpSettings;

public final class BukkitPortalMenuHost implements PortalMenuHost {
    private final PortalManager portals;

    public BukkitPortalMenuHost(PortalManager portals) {
        this.portals = Objects.requireNonNull(portals, "portals");
    }

    @Override
    public Optional<PortalMenuView> portal(UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId).map(this::view);
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId).map(portal -> portal.canManage(viewer.id(), viewer.administrator())).orElse(false);
    }

    @Override
    public boolean ownerOrAdmin(MenuViewer viewer, UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId)
            .map(portal -> PortalAccessPolicy.canManage(portal.getId(), portal.getOwner(), viewer.id(), viewer.administrator()))
            .orElse(false);
    }

    @Override
    public boolean typeAllowed(MenuViewer viewer, PortalType type) {
        return PortalTypeAccess.allows(BukkitMenuViewer.player(viewer), type);
    }

    @Override
    public boolean canEditSurfaceSkin(MenuViewer viewer, UUID portalId) {
        return viewer.hasPermission(PortalTypeAccess.ADMIN) && canManage(viewer, portalId);
    }

    @Override
    public PortalEditOutcome edit(MenuViewer viewer, UUID portalId, PortalEdit edit) {
        Optional<LocalPortal> found = BukkitLocalPortals.local(portals, portalId);
        if (found.isEmpty()) {
            return PortalEditOutcome.refused(WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty());
        }
        LocalPortal portal = found.get();
        return switch (edit) {
            case PortalEdit.Name name -> applied(() -> portal.setName(name.name()));
            case PortalEdit.Projection projection -> projection(portal, projection);
            case PortalEdit.Type type -> applied(() -> portal.setType(type.type()));
            case PortalEdit.Mirror mirror -> applied(() -> portal.setMirrorMode(mirror.enabled()));
            case PortalEdit.MirrorRotation rotation -> mirrorRotation(portal, rotation);
            case PortalEdit.FrameChange change -> applied(() -> portal.setFrame(frame(portal.getFrame(), change.operation())));
            case PortalEdit.PermissionMode mode -> applied(() -> portal.setPermissionMode(mode.mode()));
            case PortalEdit.TravelMode mode -> travelMode(portal, mode);
            case PortalEdit.Quality quality -> applied(() -> portal.setNetworkViewQuality(quality.quality()));
            case PortalEdit.NetworkNumber number -> applied(() -> networkNumber(portal, number.field(), number.value()));
            case PortalEdit.SettingsSync sync -> applied(() -> portal.setSettingsSyncEnabled(sync.enabled()));
            case PortalEdit.Blackout blackout -> applied(() -> portal.setBlackoutBackground(blackout.enabled()));
            case PortalEdit.BlackoutColorChoice color -> applied(() -> portal.setBlackoutColor(color.color()));
            case PortalEdit.ActivationRange range -> applied(() -> portal.setActivationRange(range.blocks()));
            case PortalEdit.RenderMode mode -> applied(() -> portal.setRenderMode(mode.mode()));
            case PortalEdit.AmbientStyle style -> applied(() -> portal.setAmbientStyle(style.style()));
            case PortalEdit.AmbientColor color -> applied(() -> portal.setAmbientColor(color.rgb()));
            case PortalEdit.ApertureShape shape -> portal.setApertureShape(shape.shape())
                ? PortalEditOutcome.applied()
                : PortalEditOutcome.refused(WormholesMessages.PORTAL_APERTURE_SHAPE_TOO_SMALL,
                    MessageArgs.builder().untrusted("shape", shape.shape().format()).build());
            case PortalEdit.SurfaceSkin skin -> applied(() -> portal.setSurfaceSkin(skin.skin()));
            case PortalEdit.FallbackBlock block -> applied(() -> portal.setNetworkViewFallbackBlock(block.blockState()));
            case PortalEdit.PublicLookLabel label -> applied(() -> portal.setPublicLookLabel(label.enabled()));
        };
    }

    @Override
    public void destroy(MenuViewer viewer, UUID portalId) {
        BukkitLocalPortals.local(portals, portalId).ifPresent(LocalPortal::destroy);
    }

    @Override
    public void announce(UUID portalId, TextKey key, MessageArgs arguments, boolean success) {
        Optional<LocalPortal> found = BukkitLocalPortals.local(portals, portalId);
        if (found.isEmpty()) {
            return;
        }
        String message = Wormholes.text().legacy(key, arguments);
        Location center = found.get().getStructure().getCenter();
        if (success) {
            Wormholes.effectManager.playNotificationSuccess(message, center);
            return;
        }
        Wormholes.effectManager.playNotificationFail(message, center);
    }

    @Override
    public int globalActivationRange() {
        return (int) Math.round(Settings.PROJECTION_RANGE);
    }

    @Override
    public boolean beginDirectionPrompt(MenuViewer viewer, UUID portalId) {
        Optional<LocalPortal> found = BukkitLocalPortals.local(portals, portalId);
        if (found.isEmpty()) {
            return false;
        }
        found.get().uiChangeDirection(BukkitMenuViewer.player(viewer));
        return true;
    }

    @Override
    public String routerTitle(UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId).map(portal -> portal.getRouter(true)).orElse("");
    }

    public Optional<ItemStack> template(String templateRef) {
        UUID portalId;
        try {
            portalId = UUID.fromString(templateRef);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        return BukkitLocalPortals.local(portals, portalId)
            .map(LocalPortal::getTravelCost)
            .filter(VanillaTravelCost.class::isInstance)
            .map(cost -> ((VanillaTravelCost) cost).getTemplate());
    }

    private PortalMenuView view(LocalPortal portal) {
        return PortalMenuView.builder(portal.getId(), portal.getName())
            .owner(portal.getOwner())
            .type(portal.getType())
            .mirror(portal.isMirrorMode())
            .mirrorRotation(portal.getMirrorRotation())
            .frame(portal.getFrame())
            .dimensionalKind(portal.getDimensionalPortalKind())
            .twoWayEnd(Settings.TWO_WAY_END_PORTALS)
            .projection(portal.getProjectionMode())
            .destination(destination(portal.getTunnel()))
            .routerTitle(portal.getRouter(true))
            .permissionMode(portal.getPermissionMode())
            .permissionNode(portal.getPermissionNode())
            .travelMode(portal.getTravelMode())
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
            .openCellCount(portal.getStructure().getBlockPositions().size())
            .surfaceSkin(portal.getSurfaceSkin())
            .surfaceSkinItemId(surfaceSkinItemId(portal))
            .fallbackBlock(portal.getNetworkViewFallbackBlock())
            .publicLookLabel(portal.isPublicLookLabel())
            .cost(cost(portal))
            .accessibleCount(portals.getAccessableCount(portal.getType()))
            .rtp(rtp(portal))
            .build();
    }

    private static PortalMenuView.Destination destination(ITunnel tunnel) {
        IPortal destination = tunnel == null ? null : tunnel.getDestination();
        if (destination == null) {
            return null;
        }
        if (tunnel instanceof UniversalTunnel universal) {
            return new PortalMenuView.Destination(destination.getName(), true, universal.getServerName());
        }
        return new PortalMenuView.Destination(destination.getName(), false, null);
    }

    private static PortalMenuView.Cost cost(LocalPortal portal) {
        PortalTravelCost cost = portal.getTravelCost();
        if (cost instanceof VanillaTravelCost vanilla) {
            return new PortalMenuView.Cost(PortalMenuView.CostMode.VANILLA, vanilla.getMaterial().getKey().toString(), vanilla.getItemLabel(),
                vanilla.getQuantity(), null, portal.getId().toString());
        }
        if (cost instanceof VaultTravelCost vault) {
            return new PortalMenuView.Cost(PortalMenuView.CostMode.VAULT, null, null, 0, vault.getPlainAmount(), null);
        }
        return PortalMenuView.Cost.FREE;
    }

    private static PortalMenuView.Rtp rtp(LocalPortal portal) {
        RtpSettings settings = portal.getRtpSettings();
        if (portal.getType() != PortalType.RTP || settings == null) {
            return null;
        }
        return new PortalMenuView.Rtp(settings.getAllocationMode(), settings.getRotationMode(), settings.getCycleDurationMillis());
    }

    private static String surfaceSkinItemId(LocalPortal portal) {
        if (!portal.hasSurfaceSkin()) {
            return Material.GLASS.getKey().toString();
        }
        String skin = portal.getSurfaceSkin();
        if (PortalSurfaceSkins.isFluid(skin)) {
            return (skin.contains("lava") ? Material.LAVA_BUCKET : Material.WATER_BUCKET).getKey().toString();
        }
        try {
            Material material = Bukkit.createBlockData(skin).getMaterial();
            return (material.isItem() ? material : Material.GLASS).getKey().toString();
        } catch (IllegalArgumentException invalid) {
            return Material.GLASS.getKey().toString();
        }
    }

    private static PortalEditOutcome projection(LocalPortal portal, PortalEdit.Projection projection) {
        if (portal.getDimensionalPortalKind().isReceiverOnly(Settings.TWO_WAY_END_PORTALS)) {
            return PortalEditOutcome.refused(WormholesMessages.PORTAL_PROJECTION_RECEIVER_INACTIVE, MessageArgs.empty());
        }
        portal.setProjectionMode(projection.mode());
        return PortalEditOutcome.applied();
    }

    private static PortalEditOutcome mirrorRotation(LocalPortal portal, PortalEdit.MirrorRotation rotation) {
        if (!portal.isMirrorMode()) {
            return PortalEditOutcome.refused(WormholesMessages.PORTAL_MIRROR_SELECT_FIRST, MessageArgs.empty());
        }
        Frame frame = portal.getFrame();
        portal.setMirrorRotation(rotation.clockwise()
            ? portal.getMirrorRotation().clockwiseFor(frame)
            : portal.getMirrorRotation().counterClockwiseFor(frame));
        return PortalEditOutcome.applied();
    }

    private static PortalEditOutcome travelMode(LocalPortal portal, PortalEdit.TravelMode mode) {
        if (portal.getDimensionalPortalKind().isManagedPortal()) {
            return PortalEditOutcome.refused(WormholesMessages.PORTAL_TRAVEL_MANAGED, MessageArgs.empty());
        }
        if (portal.isMirrorMode()) {
            return PortalEditOutcome.refused(WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED, MessageArgs.empty());
        }
        portal.setTravelMode(mode.mode());
        return PortalEditOutcome.applied();
    }

    private static void networkNumber(LocalPortal portal, NetworkNumberField field, int value) {
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

    private static PortalEditOutcome applied(Runnable mutation) {
        mutation.run();
        return PortalEditOutcome.applied();
    }
}
