package art.arcane.wormholes.modded;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;

public final class MinecraftTransitProtection {
    private MinecraftTransitProtection() {
    }

    public static boolean invulnerable(Entity entity) {
        return entity.isInvulnerable();
    }

    public static void invulnerable(Entity entity, boolean enabled) {
        entity.setInvulnerable(enabled);
    }

    public static void rescue(ServerPlayer player) {
        player.invulnerableTime = Math.max(player.invulnerableTime, 40);
    }
}
