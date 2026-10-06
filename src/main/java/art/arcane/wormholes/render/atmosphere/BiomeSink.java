package art.arcane.wormholes.render.atmosphere;

import java.util.List;

import org.bukkit.World;
import org.bukkit.entity.Player;
import art.arcane.optics.fidelity.BiomeClaimSet;

/** Delivers rebuilt chunk-column biome grids to one observer. */
@FunctionalInterface
public interface BiomeSink {
    void send(Player observer, World world, List<BiomeClaimSet.ChunkBiomes> chunks);
}
