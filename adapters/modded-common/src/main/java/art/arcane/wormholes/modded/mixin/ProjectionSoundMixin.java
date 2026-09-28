package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftProjectionService;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class ProjectionSoundMixin {
    @Shadow @Final private MinecraftServer server;

    @Inject(method = "broadcast", at = @At("HEAD"))
    private void wormholesProjectionSound(Player except, double x, double y, double z, double range,
                                          ResourceKey<Level> dimension, Packet<?> packet, CallbackInfo callback) {
        MinecraftProjectionService projection = MinecraftProjectionService.forServer(server);
        if (projection == null) {
            return;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level != null) {
            projection.sound(level, packet);
        }
    }
}
