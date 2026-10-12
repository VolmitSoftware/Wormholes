package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class GravityOverlay {
    private GravityOverlay() {
    }

    public static Vec3 sample(Player player, Vec3 position) {
        GravityFrame gravity = MinecraftGravity.frame(player);
        if (gravity.equals(GravityFrame.WORLD)) {
            return position;
        }
        Vec3 offset = new Vec3(position.x - player.getX(), position.y - player.getEyeY(), position.z - player.getZ());
        return player.getEyePosition().add(MinecraftGravity.world(gravity, offset));
    }
}
