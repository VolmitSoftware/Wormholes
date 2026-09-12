package art.arcane.wormholes.access.adapters;

import art.arcane.wormholes.access.PlacementDecision;
import art.arcane.wormholes.access.PlacementKind;
import art.arcane.wormholes.access.PlacementRequest;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * GriefPrevention claims, queried once per chunk. A null permission result means the claim grants
 * the action; anything else is the refusal text GriefPrevention would have shown.
 */
public final class GriefPreventionAdapter extends ReflectiveClaimAdapter {
    private static final String GRIEF_PREVENTION = "me.ryanhamshire.GriefPrevention.GriefPrevention";
    private static final String CLAIM_PERMISSION = "me.ryanhamshire.GriefPrevention.ClaimPermission";

    public GriefPreventionAdapter(ReflectiveEnvironment environment) {
        super(environment);
    }

    @Override
    public String id() {
        return "griefprevention";
    }

    @Override
    public String pluginName() {
        return "GriefPrevention";
    }

    @Override
    public boolean perCell() {
        return false;
    }

    @Override
    protected PlacementDecision query(Object plugin, Player player, PlacementRequest request)
        throws ReflectiveOperationException {
        Object instance = staticField(load(plugin, GRIEF_PREVENTION), "instance");
        Object dataStore = field(instance, "dataStore");
        Object permission = enumConstant(load(plugin, CLAIM_PERMISSION), permissionName(request.kind()));
        for (int[] cell : request.cells()) {
            Location location = location(request, cell);
            Object claim = invoke(dataStore, "getClaimAt", location, Boolean.TRUE, null);
            if (claim == null) {
                continue;
            }
            if (invoke(claim, "checkPermission", player, permission, null) != null) {
                return PlacementDecision.deniedResult(pluginName());
            }
        }
        return PlacementDecision.allowedResult();
    }

    private static String permissionName(PlacementKind kind) {
        return kind == PlacementKind.CREATE || kind == PlacementKind.LINK ? "Build" : "Access";
    }
}
