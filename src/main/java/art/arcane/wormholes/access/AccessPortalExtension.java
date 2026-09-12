package art.arcane.wormholes.access;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-portal access state: the stable permission key, per-player roles, permission groups that grant
 * access, and whether the portal shows up in the public directory. Replicates only the key and the
 * directory flag to linked portals.
 */
public final class AccessPortalExtension implements PortalExtension {
    public static final String KEY = "access";

    private static final String JSON_PERMISSION_KEY = KEY + ".permissionKey";
    private static final String JSON_ROLES = KEY + ".roles";
    private static final String JSON_GROUPS = KEY + ".groups";
    private static final String JSON_LISTED = KEY + ".listed";
    private static final String JSON_TRANSFERRED_FROM = KEY + ".transferredFrom";

    private final LocalPortal portal;
    private final Map<UUID, PortalRole> roles = new LinkedHashMap<>();
    private final List<String> groups = new ArrayList<>();
    private volatile String permissionKey;
    private volatile boolean listed = true;
    private volatile UUID transferredFrom;

    AccessPortalExtension(LocalPortal portal) {
        this.portal = Objects.requireNonNull(portal, "portal");
    }

    @Override
    public String key() {
        return KEY;
    }

    /** Seeded from the portal name on first read and stored, so a rename never moves the node. */
    public String permissionKey() {
        String current = permissionKey;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (permissionKey == null) {
                permissionKey = PermissionKeys.sanitize(portal.getName());
            }
            return permissionKey;
        }
    }

    public String permissionNode() {
        return PermissionKeys.node(permissionKey());
    }

    /** True while the key still matches the sanitized portal name, so the legacy node is the same node. */
    public boolean matchesNameDerivedNode() {
        return permissionKey().equals(PermissionKeys.sanitize(portal.getName()));
    }

    public KeyResult setPermissionKey(String candidate) {
        return setPermissionKey(candidate, loadedPortals());
    }

    KeyResult setPermissionKey(String candidate, Iterable<? extends ILocalPortal> knownPortals) {
        String requested = candidate == null ? null : candidate.trim();
        if (!PermissionKeys.isValid(requested)) {
            return KeyResult.INVALID;
        }
        if (requested.equals(permissionKey())) {
            return KeyResult.UNCHANGED;
        }
        if (PermissionKeys.isTaken(requested, portal.getId(), knownPortals)) {
            return KeyResult.TAKEN;
        }
        permissionKey = requested;
        portal.save();
        return KeyResult.SET;
    }

    /** Insertion-ordered so the menu lists players in the order they were added. */
    public Map<UUID, PortalRole> roles() {
        synchronized (roles) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(roles));
        }
    }

    public PortalRole role(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        synchronized (roles) {
            return roles.get(playerId);
        }
    }

    public void setRole(UUID playerId, PortalRole role) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(role, "role");
        synchronized (roles) {
            if (role.equals(roles.get(playerId))) {
                return;
            }
            roles.put(playerId, role);
        }
        portal.save();
    }

    public boolean removeRole(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        synchronized (roles) {
            if (roles.remove(playerId) == null) {
                return false;
            }
        }
        portal.save();
        return true;
    }

    /** One trusted entry turns the portal into a whitelist; DENIED entries alone do not. */
    public boolean whitelistOnly() {
        synchronized (roles) {
            for (PortalRole role : roles.values()) {
                if (role == PortalRole.USER || role == PortalRole.CO_OWNER || role == PortalRole.OWNER) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<String> groups() {
        synchronized (groups) {
            return List.copyOf(groups);
        }
    }

    public boolean addGroup(String node) {
        String requested = node == null ? "" : node.trim();
        if (requested.isEmpty()) {
            return false;
        }
        synchronized (groups) {
            if (groups.contains(requested)) {
                return false;
            }
            groups.add(requested);
        }
        portal.save();
        return true;
    }

    public boolean clearGroups() {
        synchronized (groups) {
            if (groups.isEmpty()) {
                return false;
            }
            groups.clear();
        }
        portal.save();
        return true;
    }

    public boolean listed() {
        return listed;
    }

    public void setListed(boolean value) {
        if (listed == value) {
            return;
        }
        listed = value;
        portal.save();
    }

    public UUID transferredFrom() {
        return transferredFrom;
    }

    /** Ownership moved: remember where the portal came from and drop the new owner's old role. */
    public void recordTransfer(UUID previousOwner, UUID newOwner) {
        transferredFrom = previousOwner;
        synchronized (roles) {
            roles.remove(newOwner);
        }
        portal.save();
    }

    @Override
    public void save(JSONObject portalJson) {
        portalJson.put(JSON_PERMISSION_KEY, permissionKey());
        portalJson.put(JSON_LISTED, listed);
        JSONObject encodedRoles = new JSONObject();
        synchronized (roles) {
            for (Map.Entry<UUID, PortalRole> entry : roles.entrySet()) {
                encodedRoles.put(entry.getKey().toString(), entry.getValue().name());
            }
        }
        portalJson.put(JSON_ROLES, encodedRoles);
        JSONArray encodedGroups = new JSONArray();
        synchronized (groups) {
            for (String node : groups) {
                encodedGroups.put(node);
            }
        }
        portalJson.put(JSON_GROUPS, encodedGroups);
        UUID previousOwner = transferredFrom;
        if (previousOwner != null) {
            portalJson.put(JSON_TRANSFERRED_FROM, previousOwner.toString());
        }
    }

    @Override
    public void load(JSONObject portalJson) {
        String storedKey = portalJson.optString(JSON_PERMISSION_KEY, "");
        permissionKey = PermissionKeys.isValid(storedKey) ? storedKey : null;
        listed = portalJson.optBoolean(JSON_LISTED, true);
        transferredFrom = parseUuid(portalJson.optString(JSON_TRANSFERRED_FROM, ""));
        JSONObject encodedRoles = portalJson.optJSONObject(JSON_ROLES);
        synchronized (roles) {
            roles.clear();
            if (encodedRoles != null) {
                for (String encodedId : encodedRoles.keySet()) {
                    UUID playerId = parseUuid(encodedId);
                    PortalRole role = PortalRole.fromName(encodedRoles.optString(encodedId, ""));
                    if (playerId != null && role != null) {
                        roles.put(playerId, role);
                    }
                }
            }
        }
        JSONArray encodedGroups = portalJson.optJSONArray(JSON_GROUPS);
        synchronized (groups) {
            groups.clear();
            if (encodedGroups != null) {
                for (int index = 0; index < encodedGroups.length(); index++) {
                    String node = encodedGroups.optString(index, "");
                    if (!node.isBlank() && !groups.contains(node)) {
                        groups.add(node);
                    }
                }
            }
        }
    }

    @Override
    public void collectSync(Map<String, String> settings) {
        settings.put(JSON_PERMISSION_KEY, permissionKey());
        settings.put(JSON_LISTED, Boolean.toString(listed));
    }

    @Override
    public void applySync(Map<String, String> settings) {
        String mirroredKey = settings.get(JSON_PERMISSION_KEY);
        if (PermissionKeys.isValid(mirroredKey)) {
            permissionKey = mirroredKey;
        }
        String mirroredListed = settings.get(JSON_LISTED);
        if (mirroredListed != null) {
            listed = Boolean.parseBoolean(mirroredListed);
        }
    }

    private static Iterable<? extends ILocalPortal> loadedPortals() {
        return Wormholes.portalManager == null ? List.of() : Wormholes.portalManager.getLocalPortals();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Outcome of a permission-key edit, so the menu and the command can explain the refusal. */
    public enum KeyResult {
        SET,
        UNCHANGED,
        INVALID,
        TAKEN
    }
}
