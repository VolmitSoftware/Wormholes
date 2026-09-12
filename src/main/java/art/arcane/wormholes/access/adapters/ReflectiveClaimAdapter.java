package art.arcane.wormholes.access.adapters;

import art.arcane.wormholes.access.PlacementDecision;
import art.arcane.wormholes.access.PlacementRequest;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared envelope for the bundled claim adapters: an absent or disabled plugin allows without
 * loading a class, a reflection mismatch on an enabled plugin fails closed with the original
 * exception, and class lookups are cached per plugin instance so a reload of the other plugin
 * re-resolves. A disabled protection plugin is protecting nothing, so it must not refuse placement.
 */
public abstract class ReflectiveClaimAdapter implements ClaimAdapter {
    private final ReflectiveEnvironment environment;
    private final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();
    private volatile Object resolvedAgainst;

    protected ReflectiveClaimAdapter(ReflectiveEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public final PlacementDecision evaluate(PlacementRequest request) {
        try {
            Object plugin = environment.findPlugin(pluginName());
            if (plugin == null) {
                return PlacementDecision.allowedResult();
            }
            if (!environment.isPluginEnabled(plugin)) {
                return PlacementDecision.allowedResult();
            }
            Player player = environment.resolvePlayer(request.playerId());
            if (player == null) {
                return PlacementDecision.allowedResult();
            }
            return query(plugin, player, request);
        } catch (Exception | LinkageError failure) {
            return PlacementDecision.failureResult(pluginName(), unwrap(failure));
        }
    }

    @Override
    public final void invalidate() {
        classCache.clear();
        resolvedAgainst = null;
    }

    /** Runs with the plugin present, enabled, and the acting player online. */
    protected abstract PlacementDecision query(Object plugin, Player player, PlacementRequest request)
        throws ReflectiveOperationException;

    protected final ReflectiveEnvironment environment() {
        return environment;
    }

    protected final Class<?> load(Object plugin, String className) throws ClassNotFoundException {
        if (resolvedAgainst != plugin) {
            classCache.clear();
            resolvedAgainst = plugin;
        }
        Class<?> cached = classCache.get(className);
        if (cached != null) {
            return cached;
        }
        Class<?> resolved = environment.loadClass(plugin, className);
        classCache.put(className, resolved);
        return resolved;
    }

    protected static Location location(PlacementRequest request, int[] cell) {
        return new Location(request.world(), cell[0] + 0.5D, cell[1], cell[2] + 0.5D);
    }

    protected static Object invokeStatic(Class<?> type, String name, Object... arguments) throws ReflectiveOperationException {
        return findMethod(type, name, true, arguments).invoke(null, arguments);
    }

    protected static Object invoke(Object target, String name, Object... arguments) throws ReflectiveOperationException {
        Object required = Objects.requireNonNull(target, name + " target");
        return findMethod(required.getClass(), name, false, arguments).invoke(required, arguments);
    }

    protected static Object staticField(Class<?> type, String name) throws ReflectiveOperationException {
        Field declared = type.getField(name);
        declared.setAccessible(true);
        return declared.get(null);
    }

    protected static Object field(Object target, String name) throws ReflectiveOperationException {
        Field declared = Objects.requireNonNull(target, name + " target").getClass().getField(name);
        declared.setAccessible(true);
        return declared.get(target);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class<Enum>) type, name);
    }

    /**
     * Resolves against the runtime class, which for an obfuscated plugin is often a non-public class
     * implementing a public interface. The access check runs against the declaring class, so the
     * member is opened before it is handed back or Method.invoke throws IllegalAccessException.
     */
    private static Method findMethod(Class<?> type, String name, boolean staticMethod, Object[] arguments)
        throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || Modifier.isStatic(method.getModifiers()) != staticMethod) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != arguments.length) {
                continue;
            }
            boolean compatible = true;
            for (int index = 0; index < parameterTypes.length; index++) {
                if (!compatible(parameterTypes[index], arguments[index])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static boolean compatible(Class<?> parameterType, Object argument) {
        if (argument == null) {
            return !parameterType.isPrimitive();
        }
        if (!parameterType.isPrimitive()) {
            return parameterType.isInstance(argument);
        }
        return parameterType == boolean.class && argument instanceof Boolean
            || parameterType == byte.class && argument instanceof Byte
            || parameterType == short.class && argument instanceof Short
            || parameterType == int.class && argument instanceof Integer
            || parameterType == long.class && argument instanceof Long
            || parameterType == float.class && argument instanceof Float
            || parameterType == double.class && argument instanceof Double
            || parameterType == char.class && argument instanceof Character;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable unwrapped = failure;
        while (unwrapped instanceof InvocationTargetException && unwrapped.getCause() != null) {
            unwrapped = unwrapped.getCause();
        }
        return unwrapped;
    }
}
