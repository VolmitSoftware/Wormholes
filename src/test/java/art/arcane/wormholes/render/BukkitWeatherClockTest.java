package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.fidelity.WeatherRelay;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.plate.PlateWorkers;
import art.arcane.wormholes.platform.BukkitOpticsScheduler;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

final class BukkitWeatherClockTest {
    private final PlateWorkers workers = new PlateWorkers("Weather-Clock-Test-", 1);

    @AfterEach
    void shutdownWorkers() {
        workers.shutdown();
    }

    @Test
    void burstsFollowTheProjectionTickInsteadOfWallTime() throws InterruptedException {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BukkitWeatherClockTest"));
        BukkitOpticsScheduler scheduler = new BukkitOpticsScheduler(plugin, workers);
        Player observer = mock(Player.class);
        ProjectorWeather weather = new ProjectorWeather();
        BukkitProjectionOutput output = new BukkitProjectionOutput((player, chunkX, chunkZ) -> true, portalId -> List.of());
        Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> claims = airClaims(40);

        weather.relay(scheduler, output, observer, new ProjectorWeather.Conditions(true, false, "minecraft:plains"), claims);
        verify(observer, times(12)).spawnParticle(eq(Particle.RAIN), anyDouble(), anyDouble(), anyDouble(), anyInt(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble());
        clearInvocations(observer);

        Thread.sleep(400L);
        weather.relay(scheduler, output, observer, new ProjectorWeather.Conditions(true, false, "minecraft:plains"), claims);
        verify(observer, never()).spawnParticle(eq(Particle.RAIN), anyDouble(), anyDouble(), anyDouble(), anyInt(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble());

        for (int tick = 0; tick < WeatherRelay.BURST_INTERVAL_TICKS - 1; tick++) {
            scheduler.advanceTick();
        }
        weather.relay(scheduler, output, observer, new ProjectorWeather.Conditions(true, false, "minecraft:plains"), claims);
        verify(observer, never()).spawnParticle(eq(Particle.RAIN), anyDouble(), anyDouble(), anyDouble(), anyInt(),
            anyDouble(), anyDouble(), anyDouble(), anyDouble());

        scheduler.advanceTick();
        weather.relay(scheduler, output, observer, new ProjectorWeather.Conditions(true, true, "minecraft:snowy_plains"), claims);
        verify(observer, times(WeatherRelay.MAX_PARTICLES_PER_BURST)).spawnParticle(eq(Particle.SNOWFLAKE), anyDouble(), anyDouble(),
            anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertEquals(WeatherRelay.BURST_INTERVAL_TICKS, scheduler.tick());
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> airClaims(int count) {
        BlockData air = mock(BlockData.class);
        when(air.getMaterial()).thenReturn(Material.AIR);
        Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> claims =
            new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(count);
        for (int index = 0; index < count; index++) {
            claims.put(CellKeys.pack(index, 70, 0), new ProjectedBlockClaim<BlockData, ProjectionWorldView>(air, null,
                ProjectedBlockClaim.NO_REMOTE_KEY, false));
        }
        return claims;
    }
}
