package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.access.PortalPermissionKey;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.access.AccessEdit;
import art.arcane.wormholes.menu.access.AccessKeyOutcome;
import art.arcane.wormholes.menu.access.AccessMenuHost;
import art.arcane.wormholes.menu.access.AccessMenuView;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalText;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class MinecraftAccessMenuHost implements AccessMenuHost {
    private final WormholesModRuntime runtime;
    private final MinecraftPlayerNames names;

    public MinecraftAccessMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.names = new MinecraftPlayerNames(runtime);
    }

    @Override
    public Optional<AccessMenuView> access(UUID portalId) {
        return Optional.ofNullable(runtime.portals().get(portalId)).map(this::view);
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        return runtime.portals().canManage(MinecraftMenuViewer.player(viewer), runtime.portals().get(portalId));
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
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null) {
            return false;
        }
        Consumer<MinecraftPortal> mutation = switch (edit) {
            case AccessEdit.SetRole role -> target -> target.setRole(role.playerId(), role.role());
            case AccessEdit.RemoveRole role -> portal.role(role.playerId()) == null ? null : target -> target.setRole(role.playerId(), null);
            case AccessEdit.Listed listed -> target -> target.setListed(listed.listed());
            case AccessEdit.AddGroup group -> group.node().isEmpty() || portal.getGroups().contains(group.node())
                ? null : target -> target.addGroup(group.node());
            case AccessEdit.ClearGroups _ -> portal.getGroups().isEmpty() ? null : MinecraftPortal::clearGroups;
        };
        return mutation != null && update(MinecraftMenuViewer.player(viewer), portalId, mutation);
    }

    @Override
    public AccessKeyOutcome setPermissionKey(MenuViewer viewer, UUID portalId, String key) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null || !PortalPermissionKey.isValid(key)) {
            return AccessKeyOutcome.INVALID;
        }
        if (key.equals(portal.getPermissionKey())) {
            return AccessKeyOutcome.UNCHANGED;
        }
        for (MinecraftPortal other : runtime.portals().snapshot()) {
            if (!other.getId().equals(portalId) && key.equals(other.getPermissionKey())) {
                return AccessKeyOutcome.TAKEN;
            }
        }
        return update(MinecraftMenuViewer.player(viewer), portalId, target -> target.setPermissionKey(key)) ? AccessKeyOutcome.SET : AccessKeyOutcome.INVALID;
    }

    private boolean update(ServerPlayer player, UUID portalId, Consumer<MinecraftPortal> mutation) {
        if (!runtime.portals().update(player, portalId, mutation)) {
            return false;
        }
        runtime.menus().invalidate(new MenuScope.Portal(portalId));
        return true;
    }

    private AccessMenuView view(MinecraftPortal portal) {
        Map<UUID, PortalRole> roles = portal.getRoles();
        List<AccessMenuView.Role> entries = new ArrayList<>(roles.size());
        for (Map.Entry<UUID, PortalRole> entry : roles.entrySet()) {
            entries.add(new AccessMenuView.Role(entry.getKey(), entry.getValue()));
        }
        return new AccessMenuView(portal.getId(), portal.getName(), portal.getType(), portal.isMirrorMode(),
            MinecraftPortalText.router(runtime, portal, true), portal.getOwner(), portal.getPermissionKey(), portal.getGroups().size(),
            portal.isListed(), entries);
    }
}
