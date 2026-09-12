package art.arcane.wormholes.rules;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Cuboid;

/**
 * Bukkit proxies for the rules tests. {@code LocalPortalTestSupport} is package-private in {@code portal},
 * so the world and portal builders are mirrored here rather than opened up.
 */
final class RulesTestSupport {
    private RulesTestSupport() {
    }

    static World world(String name) {
        UUID worldId = UUID.nameUUIDFromBytes(("rules-world-" + name).getBytes(StandardCharsets.UTF_8));
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
            case "toString" -> "RulesTestWorld[" + name + "]";
            default -> defaultValue(method.getReturnType());
        };
        return (World) Proxy.newProxyInstance(RulesTestSupport.class.getClassLoader(), new Class<?>[] {World.class}, handler);
    }

    static LocalPortal portal(World world, PortalType type) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 0.0D, 66.0D, 2.0D)));
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), type, structure);
        portal.setAmbientAttended(false);
        return portal;
    }

    static Object defaultValue(Class<?> type) {
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

    /** Records the state changes a reservation or effect applies so a test can assert on behavior. */
    static final class FakeTraveler implements InvocationHandler {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private final Set<String> permissions;
        private final Entity proxy;
        private final List<Vector> velocities = new ArrayList<>();
        private Vector velocity = new Vector(0.0D, 0.0D, 1.0D);
        private Location location;
        private int level;
        private float experience;
        private int totalExperience;
        private int foodLevel = 20;
        private double health = 20.0D;
        private double maximumHealth = 20.0D;
        private boolean sneaking;

        private FakeTraveler(String name, Location location, Set<String> permissions, Class<?> type) {
            this.name = name;
            this.location = location.clone();
            this.permissions = Set.copyOf(permissions);
            this.proxy = (Entity) Proxy.newProxyInstance(RulesTestSupport.class.getClassLoader(),
                new Class<?>[] {type}, this);
        }

        static FakeTraveler player(String name, Location location, Set<String> permissions) {
            return new FakeTraveler(name, location, permissions, Player.class);
        }

        static FakeTraveler entity(String name, Location location, Class<?> type) {
            return new FakeTraveler(name, location, Set.of(), type);
        }

        Entity entity() {
            return proxy;
        }

        Player player() {
            return (Player) proxy;
        }

        UUID id() {
            return id;
        }

        int level() {
            return level;
        }

        void level(int value) {
            level = value;
        }

        int foodLevel() {
            return foodLevel;
        }

        void foodLevel(int value) {
            foodLevel = value;
        }

        double health() {
            return health;
        }

        void health(double value) {
            health = value;
        }

        void maximumHealth(double value) {
            maximumHealth = value;
        }

        void location(Location value) {
            location = value.clone();
        }

        List<Vector> velocities() {
            return velocities;
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "getLocation" -> location.clone();
                case "getWorld" -> location.getWorld();
                case "hasPermission" -> Boolean.valueOf(permissions.contains(String.valueOf(arguments[0])));
                case "isPermissionSet" -> Boolean.valueOf(permissions.contains(String.valueOf(arguments[0])));
                case "getLevel" -> Integer.valueOf(level);
                case "setLevel" -> {
                    level = ((Integer) arguments[0]).intValue();
                    yield null;
                }
                case "getExp" -> Float.valueOf(experience);
                case "setExp" -> {
                    experience = ((Float) arguments[0]).floatValue();
                    yield null;
                }
                case "getTotalExperience" -> Integer.valueOf(totalExperience);
                case "setTotalExperience" -> {
                    totalExperience = ((Integer) arguments[0]).intValue();
                    yield null;
                }
                case "giveExp" -> {
                    totalExperience += ((Integer) arguments[0]).intValue();
                    yield null;
                }
                case "getFoodLevel" -> Integer.valueOf(foodLevel);
                case "setFoodLevel" -> {
                    foodLevel = ((Integer) arguments[0]).intValue();
                    yield null;
                }
                case "getHealth" -> Double.valueOf(health);
                case "getMaxHealth" -> Double.valueOf(maximumHealth);
                case "setHealth" -> {
                    health = ((Double) arguments[0]).doubleValue();
                    yield null;
                }
                case "isSneaking" -> Boolean.valueOf(sneaking);
                case "setSneaking" -> {
                    sneaking = ((Boolean) arguments[0]).booleanValue();
                    yield null;
                }
                case "getVelocity" -> velocity.clone();
                case "setVelocity" -> {
                    velocity = ((Vector) arguments[0]).clone();
                    velocities.add(velocity.clone());
                    yield null;
                }
                case "isValid", "isOnline" -> Boolean.TRUE;
                case "isOp" -> Boolean.FALSE;
                case "getPassengers" -> List.of();
                case "getVehicle" -> null;
                case "equals" -> Boolean.valueOf(instance == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(instance));
                case "toString" -> "RulesTestTraveler[" + name + "]";
                default -> defaultValue(method.getReturnType());
            };
        }
    }
}
