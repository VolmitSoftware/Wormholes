package art.arcane.wormholes.service;

import art.arcane.wormholes.Wormholes;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.CustomChart;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        validateConfiguration(plugin.getDataFolder().toPath().resolveSibling("bStats").resolve("config.yml"));
        Metrics metrics = new Metrics(plugin, pluginId);
        return new MetricsRuntime(plugin.getLogger(), metrics);
    }

    public static void validateConfiguration(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(path.toFile());
            if (configuration.contains("enabled") && !configuration.isBoolean("enabled")) {
                throw new IllegalArgumentException("bStats enabled must be a boolean in " + path);
            }
        } catch (IOException | InvalidConfigurationException failure) {
            throw new IllegalArgumentException("Could not load bStats configuration " + path, failure);
        }
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
