package art.arcane.wormholes.render;

import java.util.Random;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.claim.WorldOutput;
import art.arcane.optics.fidelity.WeatherRelay;
import art.arcane.optics.spi.OpticsScheduler;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

final class ProjectorWeather {
    private final WeatherRelay relay = new WeatherRelay();
    private final Random random = new Random();

    void relay(OpticsScheduler<Player, World> scheduler, WorldOutput<Player> output, Player observer, Conditions conditions,
               Long2ObjectMap<BlockClaim<BlockData, ProjectionWorldView>> claims) {
        WeatherRelay.Burst burst = relay.plan(conditions.storm(), conditions.thunder(), conditions.biome(), scheduler.tick());
        if (burst == null) {
            return;
        }
        relay.spawn(new WeatherRelay.Emission<BlockData, ProjectionWorldView>(claims, burst, random,
            block -> ProjectionWorldView.isAir(block.getMaterial())), output, observer);
    }

    record Conditions(boolean storm, boolean thunder, String biome) {
    }
}
