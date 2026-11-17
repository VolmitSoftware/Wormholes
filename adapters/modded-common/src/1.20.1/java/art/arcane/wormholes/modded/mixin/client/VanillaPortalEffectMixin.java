package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A nether portal Wormholes manages gives the local player no nausea overlay or vanilla portal countdown. */
@Mixin(NetherPortalBlock.class)
public abstract class VanillaPortalEffectMixin {
    @Inject(method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"), cancellable = true)
    private void wormholes$managedEffect(BlockState state, Level level, BlockPos position, Entity entity, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (level != minecraft.level || entity != minecraft.player) {
            return;
        }
        WormholesClient client = WormholesClient.instance();
        if (client != null && client.managesVanillaPortal(minecraft.level, position)) {
            minecraft.player.spinningEffectIntensity = 0.0F;
            minecraft.player.oSpinningEffectIntensity = 0.0F;
            callback.cancel();
        }
    }
}
