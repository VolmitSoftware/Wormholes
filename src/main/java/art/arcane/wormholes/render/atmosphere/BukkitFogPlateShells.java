package art.arcane.wormholes.render.atmosphere;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Picks the blackout far-shell block from the destination dimension when the fog plate is active. */
public final class BukkitFogPlateShells {
    private static final Map<World.Environment, BlockData> SHELLS = new ConcurrentHashMap<World.Environment, BlockData>();

    private BukkitFogPlateShells() {
    }

    public static BlockData shell(World.Environment environment, BlockData fallback) {
        String state = FogPlatePolicy.shellState(environment == null ? null : switch (environment) {
            case NORMAL -> FogPlatePolicy.Dimension.OVERWORLD;
            case NETHER -> FogPlatePolicy.Dimension.NETHER;
            case THE_END -> FogPlatePolicy.Dimension.END;
            default -> FogPlatePolicy.Dimension.CUSTOM;
        });
        if (state == null) {
            return fallback;
        }
        BlockData cached = SHELLS.get(environment);
        if (cached != null) {
            return cached;
        }
        BlockData parsed;
        try {
            parsed = Bukkit.createBlockData(state);
        } catch (RuntimeException unusable) {
            return fallback;
        }
        SHELLS.put(environment, parsed);
        return parsed;
    }
}
