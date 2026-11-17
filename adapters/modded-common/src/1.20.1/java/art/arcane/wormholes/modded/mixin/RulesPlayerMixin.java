package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class RulesPlayerMixin {
    @Inject(method = "hurt", at = @At("RETURN"))
    private void wormholesCancelWarmupOnDamage(DamageSource source, float amount,
                                              CallbackInfoReturnable<Boolean> callback) {
        MinecraftRules rules = MinecraftRules.forServer(((ServerPlayer) (Object) this).server);
        if (Boolean.TRUE.equals(callback.getReturnValue()) && rules != null) {
            rules.damaged((ServerPlayer) (Object) this);
        }
    }
}
