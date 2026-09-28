package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class PocketServerPlayerMixin {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void wormholesPocketPvp(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        MinecraftDoorService doors = MinecraftDoorService.forServer(level.getServer());
        if (doors != null && doors.rules().denyPvp(player, source)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "die", at = @At("HEAD"))
    private void wormholesRememberPocketDeath(DamageSource source, CallbackInfo callback) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        MinecraftDoorService doors = MinecraftDoorService.forServer(player.level().getServer());
        if (doors != null) {
            doors.rules().recordDeath(player);
        }
    }

    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void wormholesRestorePocketInventory(ServerPlayer previous, boolean keepEverything, CallbackInfo callback) {
        MinecraftDoorService doors = MinecraftDoorService.forServer(previous.level().getServer());
        boolean retained = doors != null && doors.rules().restoreInventory(previous);
        if (!keepEverything && retained) {
            ServerPlayer player = (ServerPlayer) (Object) this;
            player.getInventory().replaceWith(previous.getInventory());
            player.experienceLevel = previous.experienceLevel;
            player.experienceProgress = previous.experienceProgress;
            player.totalExperience = previous.totalExperience;
            player.setScore(previous.getScore());
        }
    }
}
