package art.arcane.wormholes.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

/** Stateful block data proxies for the render transform tests. */
final class RenderTestSupport {
    static final Set<BlockFace> HORIZONTAL_FACES =
        Set.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);

    private RenderTestSupport() {
    }

    static Rotatable rotatable(BlockFace rotation) {
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("rotation", rotation);
        return (Rotatable) blockData(Rotatable.class, state);
    }

    static Rail rail(Rail.Shape shape) {
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("shape", shape);
        state.put("shapes", Set.of(Rail.Shape.values()));
        return (Rail) blockData(Rail.class, state);
    }

    static Stairs stairs(BlockFace facing, Stairs.Shape shape) {
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("facing", facing);
        state.put("faces", HORIZONTAL_FACES);
        state.put("shape", shape);
        return (Stairs) blockData(Stairs.class, state);
    }

    static Door door(BlockFace facing, Door.Hinge hinge) {
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("facing", facing);
        state.put("faces", HORIZONTAL_FACES);
        state.put("hinge", hinge);
        return (Door) blockData(Door.class, state);
    }

    static Chest chest(BlockFace facing, Chest.Type type) {
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("facing", facing);
        state.put("faces", HORIZONTAL_FACES);
        state.put("type", type);
        return (Chest) blockData(Chest.class, state);
    }

    static Wall wall(Map<BlockFace, Wall.Height> heights) {
        Map<String, Object> state = new HashMap<String, Object>();
        for (Map.Entry<BlockFace, Wall.Height> entry : heights.entrySet()) {
            state.put("height:" + entry.getKey(), entry.getValue());
        }
        return (Wall) blockData(Wall.class, state);
    }

    static RedstoneWire redstoneWire(Map<BlockFace, RedstoneWire.Connection> connections) {
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("allowedFaces", HORIZONTAL_FACES);
        for (Map.Entry<BlockFace, RedstoneWire.Connection> entry : connections.entrySet()) {
            state.put("face:" + entry.getKey(), entry.getValue());
        }
        return (RedstoneWire) blockData(RedstoneWire.class, state);
    }

    /** A three by three aperture standing in the z = 5 plane. */
    static final class ApertureStructure extends PortalStructure {
        @Override
        public AxisAlignedBB getArea() {
            return new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D);
        }

        @Override
        public Location getCenter() {
            return new Location(null, 1.5D, 1.5D, 5.0D);
        }

        @Override
        public List<AxisAlignedBB> getCachedApertureFaces(Direction face) {
            KList<AxisAlignedBB> faces = new KList<AxisAlignedBB>();
            faces.add(new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
            return faces;
        }
    }

    /**
     * Runs the body with a stub server installed, which is what the PacketEvents Spigot bridge needs
     * before it will initialise at all.
     */
    static void withBukkitServer(Runnable body) {
        synchronized (Bukkit.class) {
            Object previous;
            Field serverField;
            try {
                serverField = Bukkit.class.getDeclaredField("server");
                serverField.setAccessible(true);
                previous = serverField.get(null);
                serverField.set(null, stubServer());
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("cannot install the stub server", error);
            }
            try {
                body.run();
            } finally {
                try {
                    serverField.set(null, previous);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("cannot restore the previous server", error);
                }
            }
        }
    }

    private static Server stubServer() {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                if ("createBlockData".equals(method.getName()) && args != null && args.length == 1
                    && args[0] instanceof Material material) {
                    Map<String, Object> blockState = new HashMap<String, Object>();
                    blockState.put("material", material);
                    return stateful(BlockData.class, blockState);
                }
                return switch (method.getName()) {
                    case "getName", "toString" -> "RenderTestServer";
                    case "getVersion", "getBukkitVersion" -> "26.2-R0.1-SNAPSHOT";
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "equals" -> Boolean.valueOf(proxy == args[0]);
                    default -> defaultValue(method);
                };
            });
    }

    static World world(String key, List<Entity> nearbyEntities) {
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("uID", UUID.randomUUID());
        state.put("key", NamespacedKey.minecraft(key));
        state.put("name", key);
        state.put("nearbyEntities", nearbyEntities);
        return (World) stateful(World.class, state);
    }

    static ILocalPortal portal(World world, Vector origin, PortalFrame frame) {
        return portal(portalState(world, origin, frame));
    }

    static Map<String, Object> portalState(World world, Vector origin, PortalFrame frame) {
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("id", UUID.randomUUID());
        state.put("world", world);
        state.put("origin", origin);
        state.put("frame", frame);
        state.put("center", new Location(world, origin.getX(), origin.getY(), origin.getZ()));
        state.put("name", "portal");
        return state;
    }

    static ILocalPortal portal(Map<String, Object> state) {
        return (ILocalPortal) stateful(ILocalPortal.class, state);
    }

    static ITunnel tunnel(IPortal destination) {
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("destination", destination);
        return (ITunnel) stateful(ITunnel.class, state);
    }

    static Map<String, Object> entityState(UUID id, World world, EntityType type, double x, double y, double z, double height) {
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("uniqueId", id);
        state.put("world", world);
        state.put("type", type);
        state.put("height", Double.valueOf(height));
        state.put("location", new Location(world, x, y, z));
        state.put("boundingBox", new BoundingBox(x - 0.5D, y, z - 0.5D, x + 0.5D, y + height, z + 0.5D));
        state.put("velocity", new Vector(0.0D, 0.0D, 0.0D));
        state.put("passengers", List.of());
        state.put("valid", Boolean.TRUE);
        state.put("visibleByDefault", Boolean.TRUE);
        state.put("dead", Boolean.FALSE);
        state.put("onGround", Boolean.TRUE);
        state.put("leashed", Boolean.FALSE);
        return state;
    }

    static <T extends Entity> T entity(Class<T> type, Map<String, Object> state) {
        return type.cast(stateful(type, state));
    }

    private static BlockData blockData(Class<?> type, Map<String, Object> state) {
        return (BlockData) stateful(type, state);
    }

    private static Object stateful(Class<?> type, Map<String, Object> state) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                String name = method.getName();
                switch (name) {
                    case "clone":
                        return proxy;
                    case "getMaterial":
                        return state.getOrDefault("material", Material.STONE);
                    case "toString":
                    case "getAsString":
                        return type.getSimpleName() + "#" + System.identityHashCode(proxy);
                    case "hashCode":
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "equals":
                        return Boolean.valueOf(proxy == args[0]);
                    default:
                        break;
                }
                if ("getLocation".equals(name) && args != null && args.length == 1 && args[0] instanceof Location target) {
                    Location source = (Location) state.get("location");
                    target.setX(source.getX());
                    target.setY(source.getY());
                    target.setZ(source.getZ());
                    target.setYaw(source.getYaw());
                    target.setPitch(source.getPitch());
                    return target;
                }
                if (name.startsWith("set") && args != null && args.length >= 1) {
                    state.put(property(name, 3, args, args.length - 1), args[args.length - 1]);
                    return null;
                }
                if (name.startsWith("get") || name.startsWith("is")) {
                    int prefix = name.startsWith("is") ? 2 : 3;
                    Object value = state.get(property(name, prefix, args, args == null ? 0 : args.length));
                    return value == null ? defaultValue(method) : value;
                }
                Object named = state.get(name);
                return named == null ? defaultValue(method) : named;
            });
    }

    private static String property(String methodName, int prefixLength, Object[] args, int keyArguments) {
        String name = Character.toLowerCase(methodName.charAt(prefixLength)) + methodName.substring(prefixLength + 1);
        return keyArguments == 1 ? name + ":" + args[0] : name;
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
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
        return null;
    }
}
