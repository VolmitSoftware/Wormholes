package art.arcane.wormholes.service;

import art.arcane.wormholes.Wormholes;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.CustomChart;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class MetricsRuntime {
    private final Logger logger;
    private Metrics metrics;

    private MetricsRuntime(Logger logger, Metrics metrics) {
        this.logger = logger;
        this.metrics = metrics;
    }

    public static MetricsRuntime start(Wormholes plugin, int pluginId) {
        Metrics metrics = new Metrics(plugin, pluginId);
        return new MetricsRuntime(plugin.getLogger(), metrics);
    }

    public void addChart(CustomChart chart) {
        Metrics activeMetrics = metrics;
        if (activeMetrics == null || chart == null) {
            return;
        }
        try {
            activeMetrics.addCustomChart(chart);
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "Error registering bStats chart", ex);
        }
    }

    public void shutdown() {
        Metrics activeMetrics = metrics;
        metrics = null;
        if (activeMetrics != null) {
            try {
                activeMetrics.shutdown();
            } catch (Throwable ex) {
                logger.log(Level.WARNING, "Error during bStats shutdown", ex);
            }
        }
    }
}
