package art.arcane.wormholes.menu.portal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.portal.PortalType;

final class FakePortalMenuHost implements PortalMenuHost {
    static final UUID PORTAL = UUID.fromString("00000000-0000-0000-0000-00000000a1fa");
    static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-00000000b2fb");

    final List<String> edits = new ArrayList<>();
    final List<String> announcements = new ArrayList<>();
    final Set<UUID> managers = new HashSet<>();
    final Map<UUID, PortalMenuView> others = new HashMap<>();
    PortalMenuView portal = base().build();
    boolean present = true;
    boolean directionPrompt = true;
    PortalEditOutcome nextOutcome;

    static PortalMenuView.Builder base() {
        return base(PORTAL, "Alpha");
    }

    static PortalMenuView.Builder base(UUID id, String name) {
        return PortalMenuView.builder(id, name)
            .routerTitle("§0§l" + name)
            .frame(Frame.canonical(Face.S))
            .accessibleCount(2)
            .networkViewDepth(32)
            .networkViewEntityIntervalTicks(4)
            .permissionNode("wormholes.portal.alpha");
    }

    @Override
    public Optional<PortalMenuView> portal(UUID portalId) {
        if (!present) {
            return Optional.empty();
        }
        return portalId.equals(PORTAL) ? Optional.of(portal) : Optional.ofNullable(others.get(portalId));
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
        return viewer.administrator() || viewer.hasPermission(type.permission());
    }

    @Override
    public boolean canEditSurfaceSkin(MenuViewer viewer, UUID portalId) {
        return viewer.administrator();
    }

    @Override
    public PortalEditOutcome edit(MenuViewer viewer, UUID portalId, PortalEdit edit) {
        edits.add(String.valueOf(edit));
        if (nextOutcome != null) {
            PortalEditOutcome outcome = nextOutcome;
            nextOutcome = null;
            return outcome;
        }
        portal = switch (edit) {
            case PortalEdit.Name name -> portal.toBuilder().name(name.name()).build();
            case PortalEdit.Projection projection -> portal.toBuilder().projection(projection.mode()).build();
            case PortalEdit.Type type -> portal.toBuilder().type(type.type()).build();
            case PortalEdit.Mirror mirror -> portal.toBuilder().mirror(mirror.enabled()).build();
            case PortalEdit.MirrorRotation rotation -> portal.toBuilder().mirrorRotation(rotation.clockwise()
                ? portal.mirrorRotation().clockwiseFor(portal.frame())
                : portal.mirrorRotation().counterClockwiseFor(portal.frame())).build();
            case PortalEdit.FrameChange change -> portal.toBuilder().frame(switch (change.operation()) {
                case FLIP_FACE -> portal.frame().flipNormal();
                case ROTATE_CLOCKWISE -> portal.frame().rotateClockwise();
                case ROTATE_COUNTER_CLOCKWISE -> portal.frame().rotateCounterClockwise();
            }).build();
            default -> portal;
        };
        return PortalEditOutcome.applied();
    }

    @Override
    public void destroy(MenuViewer viewer, UUID portalId) {
        edits.add("destroy");
        present = false;
    }

    @Override
    public void announce(UUID portalId, TextKey key, MessageArgs arguments, boolean success) {
        announcements.add(key.id());
    }

    @Override
    public int globalActivationRange() {
        return 64;
    }

    @Override
    public boolean beginDirectionPrompt(MenuViewer viewer, UUID portalId) {
        edits.add("direction-prompt");
        return directionPrompt;
    }

    @Override
    public String routerTitle(UUID portalId) {
        return portal.routerTitle();
    }
}
