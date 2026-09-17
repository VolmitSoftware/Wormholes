package art.arcane.wormholes.render;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.atmosphere.FogPlatePolicy;

/**
 * The block the blackout shell is built from for the current pass: the portal's concrete colour, or
 * the destination dimension's fog plate when the atmosphere mode asks for one.
 */
final class ProjectorBlackoutSeal {
    private BlockData blackoutData;
    private BlockData colorData;
    private BlackoutColor blackoutColorCache;
    private boolean enabled;

    void beginPass(BlackoutColor blackoutColor) {
        beginPass(blackoutColor, null, null);
    }

    void beginPass(BlackoutColor blackoutColor, World.Environment destination, AtmosphereMode mode) {
        enabled = true;
        if (colorData == null || blackoutColor != blackoutColorCache) {
            colorData = parseBlackout(blackoutColor);
            blackoutColorCache = blackoutColor;
        }
        blackoutData = FogPlatePolicy.applies(FidelitySettings.fogPlate, mode)
            ? FogPlatePolicy.shell(destination, colorData)
            : colorData;
    }

    void disable() {
        enabled = false;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** The shell block for this pass, or null when the colour has no usable block state. */
    BlockData data() {
        return blackoutData;
    }

    private static BlockData parseBlackout(BlackoutColor color) {
        try {
            return Bukkit.createBlockData(color.blockState());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
