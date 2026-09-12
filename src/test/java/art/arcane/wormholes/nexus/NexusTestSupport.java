package art.arcane.wormholes.nexus;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Cuboid;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Proxied Bukkit worlds and real {@link LocalPortal} instances for the nexus lane tests. */
final class NexusTestSupport {
    private NexusTestSupport() {
    }

    static World world(String name) {
        UUID worldId = UUID.nameUUIDFromBytes(("nexus-world-" + name).getBytes(StandardCharsets.UTF_8));
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
            case "toString" -> "NexusTestWorld[" + name + "]";
            default -> defaultValue(method.getReturnType());
        };
        return (World) Proxy.newProxyInstance(NexusTestSupport.class.getClassLoader(), new Class<?>[] {World.class}, handler);
    }

    static LocalPortal portal(World world, String name) {
        return portal(world, name, 0.0D, 0.0D);
    }

    static LocalPortal portal(World world, String name, double x, double z) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, x, 64.0D, z), new Location(world, x, 66.0D, z + 2.0D)));
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
        portal.setAmbientAttended(false);
        portal.setName(name);
        return portal;
    }

    /** A read-only portal stand-in for checks that only need identity, world and tunnel. */
    static ILocalPortal linkedPortal(UUID portalId, String name, World world, ITunnel tunnel, boolean gateway) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 0.0D, 66.0D, 2.0D)));
        InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "getId" -> portalId;
            case "getName" -> name;
            case "getWorld" -> world;
            case "getStructure" -> structure;
            case "getTunnel" -> tunnel;
            case "hasTunnel" -> Boolean.valueOf(tunnel != null && tunnel.getDestination() != null);
            case "isGateway" -> Boolean.valueOf(gateway);
            case "getType" -> gateway ? PortalType.GATEWAY : PortalType.PORTAL;
            case "isDestroyed" -> Boolean.FALSE;
            case "equals" -> Boolean.valueOf(proxy == arguments[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> "NexusTestPortal[" + name + "]";
            default -> defaultValue(method.getReturnType());
        };
        return (ILocalPortal) Proxy.newProxyInstance(NexusTestSupport.class.getClassLoader(),
                new Class<?>[] {ILocalPortal.class}, handler);
    }

    static Entity traveler(UUID entityId, World world, double x, double y, double z) {
        Location location = new Location(world, x, y, z);
        InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "getUniqueId" -> entityId;
            case "getLocation" -> location.clone();
            case "getWorld" -> world;
            case "isSneaking" -> Boolean.FALSE;
            case "equals" -> Boolean.valueOf(proxy == arguments[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> "NexusTestTraveler[" + entityId + "]";
            default -> defaultValue(method.getReturnType());
        };
        return (Entity) Proxy.newProxyInstance(NexusTestSupport.class.getClassLoader(), new Class<?>[] {Entity.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return Character.valueOf((char) 0);
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
        return null;
    }
}
