package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class LocalMeshBlockEntityMixin {
    @Inject(method = "loadWithComponents", at = @At("RETURN"))
    private void wormholesLocalMetadata(ValueInput data, CallbackInfo callback) {
        wormholesDirtyMetadata();
    }

    @Inject(method = "setChanged()V", at = @At("RETURN"))
    private void wormholesLocalChanged(CallbackInfo callback) {
        wormholesDirtyMetadata();
    }

    private void wormholesDirtyMetadata() {
        BlockEntity entity = (BlockEntity) (Object) this;
        if (entity.getLevel() instanceof ClientLevel level && level.getBlockEntity(entity.getBlockPos()) == entity) {
            WormholesClient.blockChanged(level, entity.getBlockPos());
        }
    }
}
