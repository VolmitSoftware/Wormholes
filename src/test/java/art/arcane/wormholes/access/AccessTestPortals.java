package art.arcane.wormholes.access;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Bukkit-free portals for the access-lane tests. */
public final class AccessTestPortals {
    private AccessTestPortals() {
    }

    public static World world(String name) {
        UUID worldId = UUID.nameUUIDFromBytes(("access-world-" + name).getBytes(StandardCharsets.UTF_8));
        NamespacedKey key = new NamespacedKey("minecraft", name);
        InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "getUID" -> worldId;
            case "getKey" -> key;
            case "getName" -> name;
            case "getMinHeight" -> Integer.valueOf(-64);
            case "getMaxHeight" -> Integer.valueOf(320);
            case "getSeaLevel" -> Integer.valueOf(63);
            case "getEnvironment" -> World.Environment.NORMAL;
            case "equals" -> Boolean.valueOf(proxy == arguments[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> "AccessTestWorld[" + name + "]";
            default -> defaultValue(method.getReturnType());
        };
        return (World) Proxy.newProxyInstance(AccessTestPortals.class.getClassLoader(), new Class<?>[] {World.class}, handler);
    }

    public static LocalPortal portal(World world) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 0.0D, 66.0D, 2.0D)));
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
        portal.setAmbientAttended(false);
        return portal;
    }

    public static Player player(String name, boolean operator, Set<String> permissions) {
        UUID playerId = UUID.randomUUID();
        InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "getName" -> name;
            case "isOp" -> Boolean.valueOf(operator);
            case "isOnline" -> Boolean.TRUE;
            case "hasPermission" -> Boolean.valueOf(operator || permissions.contains(String.valueOf(arguments[0])));
            case "getEffectivePermissions" -> effectivePermissions((Permissible) proxy, permissions);
            case "equals" -> Boolean.valueOf(proxy == arguments[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> "AccessTestPlayer[" + name + "]";
            default -> defaultValue(method.getReturnType());
        };
        return (Player) Proxy.newProxyInstance(AccessTestPortals.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    private static Set<PermissionAttachmentInfo> effectivePermissions(Permissible holder, Set<String> permissions) {
        Set<PermissionAttachmentInfo> granted = new LinkedHashSet<>();
        for (String node : permissions) {
            granted.add(new PermissionAttachmentInfo(holder, node, null, true));
        }
        return granted;
    }

    public static Entity mob() {
        UUID entityId = UUID.randomUUID();
        InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "getUniqueId" -> entityId;
            case "getName" -> "Cow";
            case "equals" -> Boolean.valueOf(proxy == arguments[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> "AccessTestEntity";
            default -> defaultValue(method.getReturnType());
        };
        return (Entity) Proxy.newProxyInstance(AccessTestPortals.class.getClassLoader(), new Class<?>[] {Entity.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == double.class) {
            return Double.valueOf(0.0D);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        return Character.valueOf('\0');
    }
}
