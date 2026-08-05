package art.arcane.wormholes.render;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.portal.BlackoutColor;

final class ProjectorBlackoutSeal {
    private BlockData blackoutData;
    private BlackoutColor blackoutColorCache;
    private boolean enabled;

    void beginPass(BlackoutColor blackoutColor) {
        enabled = true;
        if (blackoutData == null || blackoutColor != blackoutColorCache) {
            blackoutData = parseBlackout(blackoutColor);
            blackoutColorCache = blackoutColor;
        }
    }

    void disable() {
        enabled = false;
    }

    boolean isEnabled() {
        return enabled;
    }

    BlockData data() {
        return blackoutData;
    }

    private static BlockData parseBlackout(BlackoutColor color) {
        try {
            return Bukkit.createBlockData(color.blockState());
        } catch (IllegalArgumentException e) {
            return Material.AIR.createBlockData();
        }
    }

}
