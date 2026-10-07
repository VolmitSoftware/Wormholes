package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientEntityCrossing;
import art.arcane.wormholes.modded.client.CrossingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionPath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityCrossingMixin implements CrossingEntity {
    @Unique
    private ClientEntityCrossing wormholes$pendingCrossing;

    @Override
    public ClientEntityCrossing wormholes$crossing() {
        return wormholes$pendingCrossing;
    }

    @Override
    public void wormholes$crossing(ClientEntityCrossing crossing) {
        wormholes$pendingCrossing = crossing;
    }

    @Inject(method = "moveOrInterpolateTo(Lnet/minecraft/world/entity/PositionPath;FFZ)V", at = @At("HEAD"), cancellable = true)
    private void wormholes$sourceFrame(PositionPath position, float yRot, float xRot, boolean hasRotation, CallbackInfo callback) {
        ClientEntityCrossing crossing = wormholes$pendingCrossing;
        if (crossing == null) {
            return;
        }
        if (crossing.absorb()) {
            callback.cancel();
            return;
        }
        if (crossing.crossed()) {
            return;
        }
        callback.cancel();
        wormholes$pendingCrossing = null;
        try {
            crossing.route((Entity) (Object) this, position, yRot, xRot, hasRotation);
        } finally {
            wormholes$pendingCrossing = crossing;
        }
    }

    @Inject(method = "snapTo(DDDFF)V", at = @At("HEAD"))
    private void wormholes$crossBeforeSnap(double x, double y, double z, float yRot, float xRot, CallbackInfo callback) {
        ClientEntityCrossing crossing = wormholes$pendingCrossing;
        if (crossing != null) {
            crossing.resolve((Entity) (Object) this);
        }
    }
}
