package art.arcane.wormholes.access.adapters;

import art.arcane.wormholes.access.PlacementDecision;
import art.arcane.wormholes.access.PlacementRequest;
import org.bukkit.entity.Player;

/**
 * PlotSquared plots, queried per cell because a plot boundary can run through a single chunk. Ground
 * outside a plot area, and unclaimed ground inside one, carry no plot membership and are allowed:
 * {@code PlotArea.getPlot} answers unclaimed plot space with a synthetic unowned plot rather than
 * null, and {@code Plot.isAdded} is false for everyone on such a plot, so ownership is checked first.
 */
public final class PlotSquaredAdapter extends ReflectiveClaimAdapter {
    private static final String PLOT_SQUARED = "com.plotsquared.core.PlotSquared";
    private static final String PLOT_LOCATION = "com.plotsquared.core.location.Location";

    public PlotSquaredAdapter(ReflectiveEnvironment environment) {
        super(environment);
    }

    @Override
    public String id() {
        return "plotsquared";
    }

    @Override
    public String pluginName() {
        return "PlotSquared";
    }

    @Override
    public boolean perCell() {
        return true;
    }

    @Override
    protected PlacementDecision query(Object plugin, Player player, PlacementRequest request)
        throws ReflectiveOperationException {
        Object areaManager = invoke(invokeStatic(load(plugin, PLOT_SQUARED), "get"), "getPlotAreaManager");
        Class<?> locationClass = load(plugin, PLOT_LOCATION);
        String worldName = request.world().getName();
        for (int[] cell : request.cells()) {
            Object location = invokeStatic(locationClass, "at", worldName,
                Integer.valueOf(cell[0]), Integer.valueOf(cell[1]), Integer.valueOf(cell[2]));
            Object area = invoke(areaManager, "getApplicablePlotArea", location);
            if (area == null) {
                continue;
            }
            Object plot = invoke(area, "getPlot", location);
            if (plot == null || !Boolean.TRUE.equals(invoke(plot, "hasOwner"))) {
                continue;
            }
            if (!Boolean.TRUE.equals(invoke(plot, "isAdded", player.getUniqueId()))) {
                return PlacementDecision.deniedResult(pluginName());
            }
        }
        return PlacementDecision.allowedResult();
    }
}
