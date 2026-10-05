package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetherPortalBlock.class)
public abstract class VanillaPortalEffectMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void wormholes$managedEffect(BlockState state, Level level, BlockPos position, Entity entity,
                                         InsideBlockEffectApplier effects, boolean precise, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (level != minecraft.level || entity != minecraft.player) {
            return;
        }
        WormholesClient client = WormholesClient.instance();
        if (client != null && client.managesVanillaPortal(minecraft.level, position)) {
            if (minecraft.player.portalProcess != null && minecraft.player.portalProcess.getEntryPosition().equals(position)) {
                minecraft.player.portalProcess = null;
            }
            minecraft.player.portalEffectIntensity = 0.0F;
            minecraft.player.oPortalEffectIntensity = 0.0F;
            callback.cancel();
        }
    }
}
