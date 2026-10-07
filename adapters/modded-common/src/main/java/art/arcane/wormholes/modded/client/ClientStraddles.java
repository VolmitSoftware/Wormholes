package art.arcane.wormholes.modded.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

public final class ClientStraddles {
    private ClientStraddles() {
    }

    public static boolean suffocates(Entity player, boolean suffocates, BlockPos position) {
        if (!suffocates) {
            return false;
        }
        StraddleTracker.Straddle straddle = StraddleTracker.straddle(player);
        return straddle == null || StraddleTracker.frontSide(straddle.frame(), straddle.origin(),
            new Vec3d(position.getX() + 0.5D, position.getY() + 0.5D, position.getZ() + 0.5D)) == straddle.frontSide();
    }

    public static boolean hidesInWallOverlay(Entity player) {
        return StraddleTracker.straddle(player) != null;
    }
}
