package art.arcane.wormholes.menu.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.portal.PortalType;

final class FakeAccessHost implements AccessMenuHost {
    static final UUID PORTAL = UUID.fromString("00000000-0000-0000-0000-00000000a1fa");
    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID BEN = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID CY = UUID.fromString("c0ffee00-0000-0000-0000-0000000000a3");
    static final UUID EVE = UUID.fromString("00000000-0000-0000-0000-0000000000a5");

    final Set<UUID> managers = new HashSet<>();
    final Map<UUID, PortalRole> roles = new LinkedHashMap<>();
    final Map<UUID, String> names = new HashMap<>();
    final Map<String, UUID> known = new HashMap<>();
    final List<String> edits = new ArrayList<>();
    final List<String> lookups = new ArrayList<>();
    final List<String> groups = new ArrayList<>();
    String permissionKey = "alpha-key";
    boolean listed;
    boolean present = true;
    boolean editsApply = true;
    AccessKeyOutcome keyOutcome = AccessKeyOutcome.SET;
    CompletableFuture<Optional<UUID>> pendingLookup;

    FakeAccessHost() {
        roles.put(ADA, PortalRole.CO_OWNER);
        roles.put(BEN, PortalRole.USER);
        roles.put(CY, PortalRole.DENIED);
        names.put(OWNER, "Olga");
        names.put(ADA, "Ada");
        names.put(BEN, "Ben");
        names.put(EVE, "Eve");
        known.put("Ada", ADA);
        known.put("Olga", OWNER);
        known.put("Eve", EVE);
        groups.add("group.one");
        groups.add("group.two");
    }

    @Override
    public Optional<AccessMenuView> access(UUID portalId) {
        if (!present || !portalId.equals(PORTAL)) {
            return Optional.empty();
        }
        List<AccessMenuView.Role> entries = new ArrayList<>(roles.size());
        for (Map.Entry<UUID, PortalRole> entry : roles.entrySet()) {
            entries.add(new AccessMenuView.Role(entry.getKey(), entry.getValue()));
        }
        return Optional.of(new AccessMenuView(PORTAL, "Alpha", PortalType.PORTAL, false, "§0§lAlpha", OWNER, permissionKey, groups.size(),
            listed, entries));
    }

    @Override
    public boolean canManage(MenuViewer viewer, UUID portalId) {
        return viewer.administrator() || managers.contains(viewer.id());
    }

    @Override
    public Optional<String> playerName(UUID playerId) {
        return Optional.ofNullable(names.get(playerId));
    }

    @Override
    public CompletableFuture<Optional<UUID>> resolvePlayer(String name) {
        lookups.add(name);
        if (pendingLookup != null) {
            return pendingLookup;
        }
        return CompletableFuture.completedFuture(Optional.ofNullable(known.get(name)));
    }

    @Override
    public boolean edit(MenuViewer viewer, UUID portalId, AccessEdit edit) {
        edits.add(edit.toString());
        if (!editsApply) {
            return false;
        }
        return switch (edit) {
            case AccessEdit.SetRole set -> {
                roles.put(set.playerId(), set.role());
                yield true;
            }
            case AccessEdit.RemoveRole remove -> roles.remove(remove.playerId()) != null;
            case AccessEdit.Listed change -> {
                listed = change.listed();
                yield true;
            }
            case AccessEdit.AddGroup add -> !groups.contains(add.node()) && groups.add(add.node());
            case AccessEdit.ClearGroups _ -> {
                boolean had = !groups.isEmpty();
                groups.clear();
                yield had;
            }
        };
    }

    @Override
    public AccessKeyOutcome setPermissionKey(MenuViewer viewer, UUID portalId, String key) {
        edits.add("key:" + key);
        if (keyOutcome == AccessKeyOutcome.SET) {
            permissionKey = key;
        }
        return keyOutcome;
    }
}
