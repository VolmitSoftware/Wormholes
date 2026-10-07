package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.StraddleCollision;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class StraddleServerMovementMixin {
    @Shadow public ServerPlayer player;

    @ModifyExpressionValue(method = "isEntityCollidingWithAnythingNew", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/LevelReader;getPreMoveCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/phys/Vec3;)Ljava/lang/Iterable;"))
    private Iterable<VoxelShape> wormholesStraddleNewCollisions(Iterable<VoxelShape> shapes, @Local(argsOnly = true) Entity entity) {
        StraddleTracker.Straddle straddle = StraddleTracker.straddle(entity);
        return straddle == null ? shapes : StraddleCollision.thisSide(shapes, straddle);
    }

    @Inject(method = "tickPlayer", at = @At("HEAD"))
    private void wormholesStraddleFloating(CallbackInfoReturnable<Boolean> callback) {
        if (StraddleTracker.straddle(player) != null) {
            ((SeamlessListenerAccess) this).wormholesClientFloating(false);
        }
    }
}
