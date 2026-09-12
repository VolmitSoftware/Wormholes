package art.arcane.wormholes.access.adapters;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.PlacementKind;

/**
 * The {@code wormholes-*} WorldGuard region flags. WorldGuard freezes its flag registry at the end
 * of its own enable and region files are parsed against whatever is registered by then, so the four
 * flags can only be added from {@code Wormholes.onLoad}, which runs before any plugin enables. At
 * query time {@link WorldGuardAdapter} only reads what was registered here.
 */
public final class WorldGuardFlags {
    private static final String PLUGIN_NAME = "WorldGuard";
    private static final String WORLD_GUARD = "com.sk89q.worldguard.WorldGuard";
    private static final String STATE_FLAG = "com.sk89q.worldguard.protection.flags.StateFlag";

    private WorldGuardFlags() {
    }

    /** Region flag for this placement. All four default to ALLOW, so an unset region is unaffected. */
    public static String flagName(PlacementKind kind) {
        return switch (kind) {
            case CREATE -> "wormholes-create";
            case LINK -> "wormholes-link";
            case USE -> "wormholes-use";
            case ARRIVE -> "wormholes-arrive";
        };
    }

    /**
     * Registers the flags WorldGuard does not already hold. Returns how many were added; 0 when
     * WorldGuard is absent, already holds them, or has closed its registry.
     */
    public static int registerAtLoad(ReflectiveEnvironment environment) {
        Object plugin = environment.findPlugin(PLUGIN_NAME);
        if (plugin == null) {
            return 0;
        }
        int registered = 0;
        try {
            Class<?> stateFlagClass = environment.loadClass(plugin, STATE_FLAG);
            Object registry = ReflectiveClaimAdapter.invoke(
                ReflectiveClaimAdapter.invokeStatic(environment.loadClass(plugin, WORLD_GUARD), "getInstance"),
                "getFlagRegistry");
            for (PlacementKind kind : PlacementKind.values()) {
                String name = flagName(kind);
                if (ReflectiveClaimAdapter.invoke(registry, "get", name) != null) {
                    continue;
                }
                Object flag = stateFlagClass.getConstructor(String.class, boolean.class)
                    .newInstance(name, Boolean.TRUE);
                ReflectiveClaimAdapter.invoke(registry, "register", flag);
                registered++;
            }
        } catch (Exception | LinkageError refused) {
            Wormholes.w("access: WorldGuard refused the wormholes-* flags (" + refused
                + "); build and interact will govern");
            return registered;
        }
        if (registered > 0) {
            Wormholes.i("access: registered " + registered + " wormholes-* WorldGuard flags");
        }
        return registered;
    }
}
