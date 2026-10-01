package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ProjectionOverlay;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @ModifyVariable(method = "setServerVerifiedBlockState", at = @At("HEAD"), argsOnly = true)
    private BlockState wormholesProjectedState(BlockState state, BlockPos position) {
        WormholesClient.blockChanged(this, position);
        return ProjectionOverlay.intercept(this, position, state);
    }
}
