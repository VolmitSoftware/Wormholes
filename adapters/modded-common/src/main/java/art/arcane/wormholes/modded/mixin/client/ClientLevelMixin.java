package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ProjectionOverlay;
import art.arcane.wormholes.modded.client.ClientMeshEntities;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void wormholesNativeVisualTick(Entity entity, CallbackInfo callback) {
        if ((entity instanceof ItemEntity || entity instanceof Display) && ClientMeshEntities.hiddenFromWorld(entity)) {
            callback.cancel();
        }
    }

    @ModifyVariable(method = "setServerVerifiedBlockState", at = @At("HEAD"), argsOnly = true)
    private BlockState wormholesProjectedState(BlockState state, BlockPos position) {
        WormholesClient.blockChanged(this, position);
        return ProjectionOverlay.intercept(this, position, state);
    }
}
