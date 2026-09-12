package art.arcane.wormholes.access.adapters;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.PlacementDecision;
import art.arcane.wormholes.access.PlacementKind;
import art.arcane.wormholes.access.PlacementRequest;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WorldGuard regions, queried per cell. Reads the {@code wormholes-*} state flags that
 * {@link WorldGuardFlags} registered at load; when they are not registered, because Wormholes loaded
 * after WorldGuard closed its registry, it falls back to the built-in build and interact flags.
 */
public final class WorldGuardAdapter extends ReflectiveClaimAdapter {
    private static final String WORLD_GUARD = "com.sk89q.worldguard.WorldGuard";
    private static final String WORLD_GUARD_PLUGIN = "com.sk89q.worldguard.bukkit.WorldGuardPlugin";
    private static final String BUKKIT_ADAPTER = "com.sk89q.worldedit.bukkit.BukkitAdapter";
    private static final String FLAGS = "com.sk89q.worldguard.protection.flags.Flags";

    private final AtomicBoolean reportedFlagFallback = new AtomicBoolean();
    private volatile boolean customFlags = true;

    public WorldGuardAdapter(ReflectiveEnvironment environment) {
        super(environment);
    }

    /** Reading the custom flags is a config switch; without it the built-in flags are read. */
    public void setCustomFlagsEnabled(boolean enabled) {
        customFlags = enabled;
    }

    @Override
    public String id() {
        return "worldguard";
    }

    @Override
    public String pluginName() {
        return "WorldGuard";
    }

    @Override
    public boolean perCell() {
        return true;
    }

    @Override
    protected PlacementDecision query(Object plugin, Player player, PlacementRequest request)
        throws ReflectiveOperationException {
        Class<?> worldGuardClass = load(plugin, WORLD_GUARD);
        Class<?> worldGuardPluginClass = load(plugin, WORLD_GUARD_PLUGIN);
        Class<?> bukkitAdapterClass = load(plugin, BUKKIT_ADAPTER);
        Object worldGuard = invokeStatic(worldGuardClass, "getInstance");
        Object platform = invoke(worldGuard, "getPlatform");
        Object localPlayer = invoke(invokeStatic(worldGuardPluginClass, "inst"), "wrapPlayer", player);
        Object adaptedWorld = invokeStatic(bukkitAdapterClass, "adapt", request.world());
        if (Boolean.TRUE.equals(invoke(invoke(platform, "getSessionManager"), "hasBypass", localPlayer, adaptedWorld))) {
            return PlacementDecision.allowedResult();
        }
        Object flag = resolveFlag(plugin, worldGuard, request.kind());
        Object query = invoke(invoke(platform, "getRegionContainer"), "createQuery");
        for (int[] cell : request.cells()) {
            Location location = location(request, cell);
            Object adaptedLocation = invokeStatic(bukkitAdapterClass, "adapt", location);
            if (!testState(query, adaptedLocation, localPlayer, flag)) {
                return PlacementDecision.deniedResult(pluginName());
            }
        }
        return PlacementDecision.allowedResult();
    }

    private Object resolveFlag(Object plugin, Object worldGuard, PlacementKind kind) throws ReflectiveOperationException {
        if (customFlags) {
            Object registered = invoke(invoke(worldGuard, "getFlagRegistry"), "get", WorldGuardFlags.flagName(kind));
            if (registered != null) {
                return registered;
            }
            if (reportedFlagFallback.compareAndSet(false, true)) {
                Wormholes.w("access: the wormholes-* flags are not registered with WorldGuard; build and interact govern");
            }
        }
        return staticField(load(plugin, FLAGS), builtInFlagName(kind));
    }

    private static String builtInFlagName(PlacementKind kind) {
        return kind == PlacementKind.CREATE || kind == PlacementKind.LINK ? "BUILD" : "INTERACT";
    }

    private static boolean testState(Object query, Object location, Object localPlayer, Object flag)
        throws ReflectiveOperationException {
        for (Method method : query.getClass().getMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!method.getName().equals("testState") || parameterTypes.length != 3 || !parameterTypes[2].isArray()) {
                continue;
            }
            Class<?> componentType = parameterTypes[2].getComponentType();
            if (!componentType.isInstance(flag)) {
                continue;
            }
            Object flags = Array.newInstance(componentType, 1);
            Array.set(flags, 0, flag);
            Object result = method.invoke(query, location, localPlayer, flags);
            if (result instanceof Boolean allowed) {
                return allowed.booleanValue();
            }
            throw new IllegalStateException("WorldGuard testState returned a non-boolean result");
        }
        throw new NoSuchMethodException(query.getClass().getName() + ".testState");
    }
}
