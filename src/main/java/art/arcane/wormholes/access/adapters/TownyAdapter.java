package art.arcane.wormholes.access.adapters;

import art.arcane.wormholes.access.PlacementDecision;
import art.arcane.wormholes.access.PlacementKind;
import art.arcane.wormholes.access.PlacementRequest;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Towny plot permissions, queried once per chunk through the cached permission lookup Towny exposes
 * for exactly this purpose.
 */
public final class TownyAdapter extends ReflectiveClaimAdapter {
    private static final String PLAYER_CACHE_UTIL = "com.palmergames.bukkit.towny.utils.PlayerCacheUtil";
    private static final String ACTION_TYPE = "com.palmergames.bukkit.towny.object.TownyPermission$ActionType";
    private static final Material PROBE_BLOCK = Material.OBSIDIAN;

    public TownyAdapter(ReflectiveEnvironment environment) {
        super(environment);
    }

    @Override
    public String id() {
        return "towny";
    }

    @Override
    public String pluginName() {
        return "Towny";
    }

    @Override
    public boolean perCell() {
        return false;
    }

    @Override
    protected PlacementDecision query(Object plugin, Player player, PlacementRequest request)
        throws ReflectiveOperationException {
        Class<?> cacheUtil = load(plugin, PLAYER_CACHE_UTIL);
        Object action = enumConstant(load(plugin, ACTION_TYPE), actionName(request.kind()));
        for (int[] cell : request.cells()) {
            Location location = location(request, cell);
            Object permitted = invokeStatic(cacheUtil, "getCachePermission", player, location, PROBE_BLOCK, action);
            if (!Boolean.TRUE.equals(permitted)) {
                return PlacementDecision.deniedResult(pluginName());
            }
        }
        return PlacementDecision.allowedResult();
    }

    private static String actionName(PlacementKind kind) {
        return kind == PlacementKind.CREATE || kind == PlacementKind.LINK ? "BUILD" : "SWITCH";
    }
}
