package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.EntityDataRevision;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SynchedEntityData.class)
public abstract class EntityDataRevisionMixin implements EntityDataRevision {
    @Unique
    private long wormholesMetadataRevision;

    public long wormholesRevision() {
        return wormholesMetadataRevision;
    }

    @Inject(method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/syncher/SynchedEntityData$DataItem;setDirty(Z)V"))
    private void wormholesMetadataChanged(CallbackInfo callback) {
        wormholesMetadataRevision++;
    }
}
