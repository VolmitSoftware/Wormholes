package art.arcane.wormholes.menu.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.cosmetics.CosmeticsMenuHost;
import art.arcane.wormholes.menu.cosmetics.DyeSwatch;
import art.arcane.wormholes.menu.extensions.ExtensionsMenuHost;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.menu.portal.NetworkNumberField;
import art.arcane.wormholes.menu.portal.PortalEdit;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;
import art.arcane.wormholes.menu.portal.PortalMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.portal.PortalType;

public final class FakePortalHosts implements PortalMenuHost, CosmeticsMenuHost, ExtensionsMenuHost {
    public static final UUID PORTAL = UUID.fromString("00000000-0000-0000-0000-00000000a1fa");
    public static final List<DyeSwatch> PALETTE = List.of(
        new DyeSwatch("white", "minecraft:white_wool", 0xF9FFFE),
        new DyeSwatch("orange", "minecraft:orange_wool", 0xF9801D),
        new DyeSwatch("magenta", "minecraft:magenta_wool", 0xC74EBD),
        new DyeSwatch("light_blue", "minecraft:light_blue_wool", 0x3AB3DA),
        new DyeSwatch("yellow", "minecraft:yellow_wool", 0xFED83D),
        new DyeSwatch("lime", "minecraft:lime_wool", 0x80C71F),
        new DyeSwatch("pink", "minecraft:pink_wool", 0xF38BAA),
        new DyeSwatch("gray", "minecraft:gray_wool", 0x474F52),
        new DyeSwatch("light_gray", "minecraft:light_gray_wool", 0x9D9D97),
        new DyeSwatch("cyan", "minecraft:cyan_wool", 0x169C9C),
        new DyeSwatch("purple", "minecraft:purple_wool", 0x8932B8),
        new DyeSwatch("blue", "minecraft:blue_wool", 0x3C44AA),
        new DyeSwatch("brown", "minecraft:brown_wool", 0x835432),
        new DyeSwatch("green", "minecraft:green_wool", 0x5E7C16),
        new DyeSwatch("red", "minecraft:red_wool", 0xB02E26),
        new DyeSwatch("black", "minecraft:black_wool", 0x1D1D21));

    public final List<String> edits = new ArrayList<>();
    public final Set<UUID> managers = new HashSet<>();
    public final Set<UUID> skinEditors = new HashSet<>();
    public final Map<UUID, PortalMenuView> others = new HashMap<>();
    public PortalMenuView view = base().build();
    public boolean present = true;
    public PortalEditOutcome nextOutcome;
    public List<MenuExtensionEntry> extensions = List.of();
    public int globalRange = 64;

    public static PortalMenuView.Builder base() {
        return base(PORTAL, "Alpha");
    }

    public static PortalMenuView.Builder base(UUID portalId, String name) {
        return PortalMenuView.builder(portalId, name)
            .routerTitle("§0§l" + name)
            .permissionNode("wormholes.portal." + name.toLowerCase(Locale.ROOT))
            .networkViewDepth(NetworkViewQuality.STANDARD.getDepth())
            .networkViewHeartbeatTicks(NetworkViewQuality.STANDARD.getHeartbeatTicks())
            .networkViewEntityIntervalTicks(NetworkViewQuality.STANDARD.getEntityIntervalTicks())
            .networkViewUnsubscribeGraceSeconds(NetworkViewQuality.STANDARD.getUnsubscribeGraceSeconds())
            .ambientColor(0xB969FF)
            .openCellCount(12)
            .surfaceSkinItemId("minecraft:glass");
    }

    @Override
    public Optional<PortalMenuView> portal(UUID portalId) {
        if (!present) {
            return Optional.empty();
        }
        return portalId.equals(PORTAL) ? Optional.of(view) : Optional.ofNullable(others.get(portalId));
    }

    @Override
    public List<DyeSwatch> dyePalette() {
        return PALETTE;
    }

    @Override
    public List<MenuExtensionEntry> entries() {
        return extensions;
    }

    @Override
    public boolean activate(MenuViewer viewer, UUID portalId, String entryId, MenuInteraction interaction) {
        throw new UnsupportedOperationException("extension entries are activated by the extensions family");
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        return viewer.administrator() || managers.contains(viewer.id());
    }

    @Override
    public boolean ownerOrAdmin(MenuViewer viewer, UUID portalId) {
        return canManage(viewer, portalId);
    }

    @Override
    public boolean typeAllowed(MenuViewer viewer, PortalType type) {
        return viewer.administrator();
    }

    @Override
    public boolean canEditSurfaceSkin(MenuViewer viewer, UUID portalId) {
        return skinEditors.contains(viewer.id()) && canManage(viewer, portalId);
    }

    @Override
    public PortalEditOutcome edit(MenuViewer viewer, UUID portalId, PortalEdit edit) {
        edits.add(String.valueOf(edit));
        if (nextOutcome != null) {
            PortalEditOutcome outcome = nextOutcome;
            nextOutcome = null;
            return outcome;
        }
        view = apply(edit);
        return PortalEditOutcome.applied();
    }

    @Override
    public void destroy(MenuViewer viewer, UUID portalId) {
        throw new UnsupportedOperationException("destroy is not part of the settings families");
    }

    @Override
    public void announce(UUID portalId, TextKey key, MessageArgs arguments, boolean success) {
        throw new UnsupportedOperationException("announcements are not part of the settings families");
    }

    @Override
    public int globalActivationRange() {
        return globalRange;
    }

    @Override
    public boolean beginDirectionPrompt(MenuViewer viewer, UUID portalId) {
        throw new UnsupportedOperationException("direction prompts are not part of the settings families");
    }

    @Override
    public String routerTitle(UUID portalId) {
        return view.routerTitle();
    }

    private PortalMenuView apply(PortalEdit edit) {
        PortalMenuView.Builder next = view.toBuilder();
        return switch (edit) {
            case PortalEdit.PermissionMode mode -> next.permissionMode(mode.mode()).build();
            case PortalEdit.TravelMode mode -> next.travelMode(mode.mode()).build();
            case PortalEdit.Quality quality -> quality(next, quality.quality()).build();
            case PortalEdit.NetworkNumber number -> number(next, number.field(), clamp(number.field(), number.value()))
                .quality(NetworkViewQuality.CUSTOM).build();
            case PortalEdit.SettingsSync sync -> next.settingsSync(sync.enabled()).build();
            case PortalEdit.Blackout blackout -> next.blackout(blackout.enabled()).build();
            case PortalEdit.BlackoutColorChoice choice -> next.blackoutColor(choice.color()).build();
            case PortalEdit.ActivationRange range -> next.activationRange(range.blocks() <= 0 ? 0 : Math.max(8, Math.min(256, range.blocks()))).build();
            case PortalEdit.RenderMode mode -> next.renderMode(mode.mode()).build();
            case PortalEdit.AmbientStyle style -> next.ambientStyle(style.style()).build();
            case PortalEdit.AmbientColor color -> next.ambientColor(Math.max(0, Math.min(0xFFFFFF, color.rgb()))).build();
            case PortalEdit.ApertureShape shape -> next.apertureShape(shape.shape()).build();
            case PortalEdit.SurfaceSkin skin -> next.surfaceSkin(skin.skin()).surfaceSkinItemId(skin.skin().isEmpty() ? "minecraft:glass" : skin.skin()).build();
            case PortalEdit.FallbackBlock block -> next.fallbackBlock(normalizeBlock(block.blockState())).build();
            case PortalEdit.PublicLookLabel label -> next.publicLookLabel(label.enabled()).build();
            default -> throw new UnsupportedOperationException("portal edit " + edit + " is not part of the settings families");
        };
    }

    private static PortalMenuView.Builder quality(PortalMenuView.Builder next, NetworkViewQuality quality) {
        next.quality(quality);
        if (quality == NetworkViewQuality.CUSTOM) {
            return next;
        }
        return next.networkViewDepth(quality.getDepth())
            .networkViewHeartbeatTicks(quality.getHeartbeatTicks())
            .networkViewEntityIntervalTicks(quality.getEntityIntervalTicks())
            .networkViewUnsubscribeGraceSeconds(quality.getUnsubscribeGraceSeconds());
    }

    private static PortalMenuView.Builder number(PortalMenuView.Builder next, NetworkNumberField field, int value) {
        return switch (field) {
            case DEPTH -> next.networkViewDepth(value);
            case HEARTBEAT_TICKS -> next.networkViewHeartbeatTicks(value);
            case ENTITY_INTERVAL_TICKS -> next.networkViewEntityIntervalTicks(value);
            case UNSUBSCRIBE_GRACE_SECONDS -> next.networkViewUnsubscribeGraceSeconds(value);
        };
    }

    private static int clamp(NetworkNumberField field, int value) {
        return switch (field) {
            case DEPTH -> Math.max(1, Math.min(128, value));
            case HEARTBEAT_TICKS, ENTITY_INTERVAL_TICKS -> Math.max(2, Math.min(600, value));
            case UNSUBSCRIBE_GRACE_SECONDS -> Math.max(5, Math.min(600, value));
        };
    }

    private static String normalizeBlock(String input) {
        String trimmed = input.trim();
        return trimmed.isEmpty() || !trimmed.contains(":") ? "minecraft:air" : trimmed;
    }
}
