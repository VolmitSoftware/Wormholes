package qa;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

final class FixtureScheduler {
    private static final boolean REGIONS = available("io.papermc.paper.threadedregions.scheduler.RegionScheduler");

    private FixtureScheduler() {
    }

    static void region(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        if (REGIONS) {
            plugin.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    static CompletableFuture<Boolean> teleport(Player player, Location location) {
        if (REGIONS) {
            return player.teleportAsync(location);
        }
        return CompletableFuture.completedFuture(player.teleport(location));
    }

    private static boolean available(String className) {
        try {
            Class.forName(className, false, FixtureScheduler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }
}
