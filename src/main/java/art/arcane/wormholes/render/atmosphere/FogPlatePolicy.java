package art.arcane.wormholes.render.atmosphere;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Picks the blackout far-shell block from the destination dimension when the fog plate is active. */
public final class FogPlatePolicy {
    private static final Map<World.Environment, BlockData> SHELLS = new ConcurrentHashMap<World.Environment, BlockData>();

    private FogPlatePolicy() {
    }

    public static boolean applies(boolean fogPlateEnabled, AtmosphereMode mode) {
        return fogPlateEnabled && mode != null && mode.usesFogPlate();
    }

    public static String shellState(World.Environment environment) {
        if (environment == null) {
            return null;
        }
        return switch (environment) {
            case NORMAL -> "minecraft:light_blue_stained_glass";
            case NETHER -> "minecraft:netherrack";
            case THE_END -> "minecraft:end_stone";
            default -> null;
        };
    }

    public static BlockData shell(World.Environment environment, BlockData fallback) {
        String state = shellState(environment);
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
