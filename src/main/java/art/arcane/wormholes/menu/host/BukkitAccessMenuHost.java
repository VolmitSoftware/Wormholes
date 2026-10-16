package art.arcane.wormholes.menu.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Server;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.access.AccessEdit;
import art.arcane.wormholes.menu.access.AccessKeyOutcome;
import art.arcane.wormholes.menu.access.AccessMenuHost;
import art.arcane.wormholes.menu.access.AccessMenuView;
import art.arcane.wormholes.portal.LocalPortal;

public final class BukkitAccessMenuHost implements AccessMenuHost {
    private final PortalManager portals;
    private final BukkitPlayerNames names;

    public BukkitAccessMenuHost(PortalManager portals, Server server) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.names = new BukkitPlayerNames(server);
    }

    @Override
    public Optional<AccessMenuView> access(UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId).flatMap(portal -> extension(portal).map(access -> view(portal, access)));
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId).map(portal -> portal.canManage(viewer.id(), viewer.administrator())).orElse(false);
    }

    @Override
    public Optional<String> playerName(UUID playerId) {
        return names.name(playerId);
    }

    @Override
    public CompletableFuture<Optional<UUID>> resolvePlayer(String name) {
        return names.resolve(name);
    }

    @Override
    public boolean edit(MenuViewer viewer, UUID portalId, AccessEdit edit) {
        Optional<LocalPortal> found = BukkitLocalPortals.local(portals, portalId);
        Optional<AccessPortalExtension> extension = found.flatMap(BukkitAccessMenuHost::extension);
        if (extension.isEmpty()) {
            return false;
        }
        AccessPortalExtension access = extension.get();
        boolean changed = switch (edit) {
            case AccessEdit.SetRole role -> {
                access.setRole(role.playerId(), role.role());
                yield true;
            }
            case AccessEdit.RemoveRole role -> access.removeRole(role.playerId());
            case AccessEdit.Listed listed -> {
                access.setListed(listed.listed());
                yield true;
            }
            case AccessEdit.AddGroup group -> access.addGroup(group.node());
            case AccessEdit.ClearGroups _ -> access.clearGroups();
        };
        if (changed) {
            found.get().invalidateMenus();
        }
        return changed;
    }

    @Override
    public AccessKeyOutcome setPermissionKey(MenuViewer viewer, UUID portalId, String key) {
        Optional<LocalPortal> found = BukkitLocalPortals.local(portals, portalId);
        Optional<AccessPortalExtension> extension = found.flatMap(BukkitAccessMenuHost::extension);
        if (extension.isEmpty()) {
            return AccessKeyOutcome.INVALID;
        }
        AccessKeyOutcome outcome = switch (extension.get().setPermissionKey(key)) {
            case INVALID -> AccessKeyOutcome.INVALID;
            case TAKEN -> AccessKeyOutcome.TAKEN;
            case SET -> AccessKeyOutcome.SET;
            case UNCHANGED -> AccessKeyOutcome.UNCHANGED;
        };
        if (outcome == AccessKeyOutcome.SET) {
            found.get().invalidateMenus();
        }
        return outcome;
    }

    private static Optional<AccessPortalExtension> extension(LocalPortal portal) {
        return Optional.ofNullable(portal.extension(AccessPortalExtension.class));
    }

    private static AccessMenuView view(LocalPortal portal, AccessPortalExtension access) {
        Map<UUID, PortalRole> roles = access.roles();
        List<AccessMenuView.Role> entries = new ArrayList<>(roles.size());
        for (Map.Entry<UUID, PortalRole> entry : roles.entrySet()) {
            entries.add(new AccessMenuView.Role(entry.getKey(), entry.getValue()));
        }
        return new AccessMenuView(portal.getId(), portal.getName(), portal.getType(), portal.isMirrorMode(), portal.getRouter(true),
            portal.getOwner(), access.permissionKey(), access.groups().size(), access.listed(), entries);
    }
}
