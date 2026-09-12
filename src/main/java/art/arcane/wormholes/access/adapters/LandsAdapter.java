package art.arcane.wormholes.access.adapters;

import art.arcane.wormholes.access.PlacementDecision;
import art.arcane.wormholes.access.PlacementKind;
import art.arcane.wormholes.access.PlacementRequest;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Lands areas, queried once per chunk. Wilderness has no area and is allowed; inside a land the
 * player's role flags decide.
 */
public final class LandsAdapter extends ReflectiveClaimAdapter {
    private static final String LANDS_INTEGRATION = "me.angeschossen.lands.api.LandsIntegration";
    private static final String LANDS_FLAGS = "me.angeschossen.lands.api.flags.type.Flags";

    public LandsAdapter(ReflectiveEnvironment environment) {
        super(environment);
    }

    @Override
    public String id() {
        return "lands";
    }

    @Override
    public String pluginName() {
        return "Lands";
    }

    @Override
    public boolean perCell() {
        return false;
    }

    @Override
    protected PlacementDecision query(Object plugin, Player player, PlacementRequest request)
        throws ReflectiveOperationException {
        Object integration = invokeStatic(load(plugin, LANDS_INTEGRATION), "of", environment().hostPlugin());
        Object flag = staticField(load(plugin, LANDS_FLAGS), flagName(request.kind()));
        for (int[] cell : request.cells()) {
            Location location = location(request, cell);
            Object area = invoke(integration, "getArea", location);
            if (area == null) {
                continue;
            }
            if (!Boolean.TRUE.equals(invoke(area, "hasRoleFlag", player.getUniqueId(), flag))) {
                return PlacementDecision.deniedResult(pluginName());
            }
        }
        return PlacementDecision.allowedResult();
    }

    private static String flagName(PlacementKind kind) {
        return kind == PlacementKind.CREATE || kind == PlacementKind.LINK ? "BLOCK_PLACE" : "INTERACT_GENERAL";
    }
}
