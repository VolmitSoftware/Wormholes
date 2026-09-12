package art.arcane.wormholes.transit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Cuboid;

/** Proxy world and portal factory for transit tests; mirrors the portal package support class. */
public final class TransitTestSupport {
    private TransitTestSupport() {
    }

    public static World world(String name) {
        UUID worldId = UUID.nameUUIDFromBytes(("transit-world-" + name).getBytes(StandardCharsets.UTF_8));
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
            case "toString" -> "TransitTestWorld[" + name + "]";
            default -> defaultValue(method.getReturnType());
        };
        return (World) Proxy.newProxyInstance(TransitTestSupport.class.getClassLoader(), new Class<?>[] {World.class}, handler);
    }

    /** A one-block-thick wall portal spanning x 0..0, y 64..66, z 0..2 (normal along X). */
    public static LocalPortal portal(World world) {
        return portal(world, new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 0.0D, 66.0D, 2.0D));
    }

    public static LocalPortal portal(World world, Location min, Location max) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(min, max));
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
        portal.setAmbientAttended(false);
        return portal;
    }

    /** Proxy-backed rig member: vehicle, passengers, leash edges, bounding box, and recorded teleports. */
    public static final class Rig implements InvocationHandler {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private final Entity proxy;
        private final List<Entity> passengers = new ArrayList<Entity>();
        private final List<Location> teleports = new ArrayList<Location>();
        private final double width;
        private final double height;
        private Location location;
        private Entity vehicle;
        private Entity leashHolder;
        private Vector velocity = new Vector();
        private boolean valid = true;

        private Rig(String name, Location location, double width, double height, Class<? extends Entity> type) {
            this.name = name;
            this.location = location.clone();
            this.width = width;
            this.height = height;
            this.proxy = (Entity) Proxy.newProxyInstance(TransitTestSupport.class.getClassLoader(), new Class<?>[] {type}, this);
        }

        public static Rig player(String name, Location location) {
            return new Rig(name, location, 0.6D, 1.8D, Player.class);
        }

        public static Rig mob(String name, Location location, double width, double height) {
            return new Rig(name, location, width, height, LivingEntity.class);
        }

        public static Rig vehicle(String name, Location location, double width, double height) {
            return new Rig(name, location, width, height, Entity.class);
        }

        public Entity entity() {
            return proxy;
        }

        public UUID id() {
            return id;
        }

        public Location location() {
            return location.clone();
        }

        public Vector velocity() {
            return velocity.clone();
        }

        public List<Location> teleports() {
            return teleports;
        }

        public Entity vehicle() {
            return vehicle;
        }

        public List<Entity> passengers() {
            return passengers;
        }

        public Entity leashHolder() {
            return leashHolder;
        }

        public Rig ride(Rig target) {
            this.vehicle = target.proxy;
            target.passengers.add(proxy);
            return this;
        }

        public Rig leashTo(Rig holder) {
            this.leashHolder = holder.proxy;
            return this;
        }

        public void invalidate() {
            valid = false;
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "getLocation" -> location.clone();
                case "getWorld" -> location.getWorld();
                case "getVehicle" -> vehicle;
                case "getPassengers" -> List.copyOf(passengers);
                case "isInsideVehicle" -> Boolean.valueOf(vehicle != null);
                case "leaveVehicle" -> {
                    if (vehicle != null && Proxy.getInvocationHandler(vehicle) instanceof Rig holder) {
                        holder.passengers.remove(proxy);
                    }
                    vehicle = null;
                    yield Boolean.TRUE;
                }
                case "eject" -> {
                    for (Entity passenger : List.copyOf(passengers)) {
                        if (Proxy.getInvocationHandler(passenger) instanceof Rig rider) {
                            rider.vehicle = null;
                        }
                    }
                    passengers.clear();
                    yield Boolean.TRUE;
                }
                case "addPassenger" -> {
                    Entity passenger = (Entity) arguments[0];
                    passengers.add(passenger);
                    if (Proxy.getInvocationHandler(passenger) instanceof Rig rider) {
                        rider.vehicle = proxy;
                    }
                    yield Boolean.TRUE;
                }
                case "isLeashed" -> Boolean.valueOf(leashHolder != null);
                case "getLeashHolder" -> {
                    if (leashHolder == null) {
                        throw new IllegalStateException("not leashed");
                    }
                    yield leashHolder;
                }
                case "setLeashHolder" -> {
                    leashHolder = (Entity) arguments[0];
                    yield Boolean.TRUE;
                }
                case "getBoundingBox" -> new BoundingBox(
                    location.getX() - width / 2.0D, location.getY(), location.getZ() - width / 2.0D,
                    location.getX() + width / 2.0D, location.getY() + height, location.getZ() + width / 2.0D);
                case "getWidth" -> Double.valueOf(width);
                case "getHeight" -> Double.valueOf(height);
                case "getVelocity" -> velocity.clone();
                case "setVelocity" -> {
                    velocity = ((Vector) arguments[0]).clone();
                    yield null;
                }
                case "teleport" -> {
                    Location target = (Location) arguments[0];
                    teleports.add(target.clone());
                    location = target.clone();
                    yield Boolean.TRUE;
                }
                case "isValid" -> Boolean.valueOf(valid);
                case "isOnline" -> Boolean.valueOf(valid);
                case "isDead" -> Boolean.valueOf(!valid);
                case "isOp" -> Boolean.FALSE;
                case "hasPermission" -> Boolean.TRUE;
                case "equals" -> Boolean.valueOf(instance == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(instance));
                case "toString" -> "Rig[" + name + "]";
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    public static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        if (type == double.class) {
            return Double.valueOf(0.0D);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        return null;
    }
}
