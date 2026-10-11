package art.arcane.wormholes.modded;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;

public final class MinecraftTransitProtection {
    private MinecraftTransitProtection() {
    }

    public static boolean invulnerable(Entity entity) {
        return entity.isPermanentlyInvulnerable();
    }

    public static void invulnerable(Entity entity, boolean enabled) {
        entity.setPermanentlyInvulnerable(enabled);
    }

    public static void rescue(ServerPlayer player) {
        player.setInvulnerableTime(Math.max(player.getInvulnerableTime(), 40));
    }
}
