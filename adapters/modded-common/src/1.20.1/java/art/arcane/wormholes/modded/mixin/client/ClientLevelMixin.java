package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ProjectionOverlay;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("RETURN"))
    private void wormholesLocalBlocks(BlockPos position, BlockState previous, BlockState next, CallbackInfo callback) {
        WormholesClient.blockChanged(this, position);
    }

    @ModifyVariable(method = "setServerVerifiedBlockState", at = @At("HEAD"), argsOnly = true)
    private BlockState wormholesProjectedState(BlockState state, BlockPos position) {
        WormholesClient.blockChanged(this, position);
        return ProjectionOverlay.intercept(this, position, state);
    }
}
