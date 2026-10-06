package art.arcane.wormholes.render;

import java.util.Random;

import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.fidelity.WeatherRelay;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.spi.OpticsScheduler;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

final class ProjectorWeather {
    private final WeatherRelay relay = new WeatherRelay();
    private final Random random = new Random();

    void relay(OpticsScheduler<Player, World> scheduler, Player observer, Conditions conditions,
               Long2ObjectMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> claims) {
        WeatherRelay.Burst burst = relay.plan(conditions.storm(), conditions.thunder(), conditions.biome(), scheduler.tick());
        if (burst == null) {
            return;
        }
        relay.spawn(new WeatherRelay.Emission<BlockData, ProjectionWorldView>(claims, burst, random,
            block -> ProjectionWorldView.isAir(block.getMaterial())), (particle, cell) -> spawn(observer, particle, cell));
    }

    private static void spawn(Player observer, WeatherRelay.Precipitation precipitation, long cell) {
        observer.spawnParticle(precipitation == WeatherRelay.Precipitation.SNOWFLAKE ? Particle.SNOWFLAKE : Particle.RAIN,
            CellKeys.unpackX(cell) + 0.5D, CellKeys.unpackY(cell) + 0.5D, CellKeys.unpackZ(cell) + 0.5D,
            1, 0.4D, 0.5D, 0.4D, 0.0D);
    }

    record Conditions(boolean storm, boolean thunder, String biome) {
    }
}
