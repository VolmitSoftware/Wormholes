package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PocketPlayerMixin {
    @Redirect(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setHealth(F)V"))
    private void wormholesPocketRescue(Player player, float health) {
        if (player instanceof ServerPlayer serverPlayer) {
            MinecraftDoorService doors = MinecraftDoorService.forServer(serverPlayer.level().getServer());
            if (doors != null && doors.rules().retainHealth(serverPlayer, health)) {
                return;
            }
        }
        player.setHealth(health);
    }

    @Inject(method = "dropEquipment", at = @At("HEAD"), cancellable = true)
    private void wormholesKeepPocketInventory(ServerLevel level, CallbackInfo callback) {
        if ((Object) this instanceof ServerPlayer player) {
            MinecraftDoorService doors = MinecraftDoorService.forServer(level.getServer());
            if (doors != null && doors.rules().keepInventory(player)) {
                callback.cancel();
            }
        }
    }

    @Inject(method = "getBaseExperienceReward", at = @At("HEAD"), cancellable = true)
    private void wormholesKeepPocketExperience(ServerLevel level, CallbackInfoReturnable<Integer> callback) {
        if ((Object) this instanceof ServerPlayer player) {
            MinecraftDoorService doors = MinecraftDoorService.forServer(level.getServer());
            if (doors != null && doors.rules().keepInventory(player)) {
                callback.setReturnValue(0);
            }
        }
    }
}
