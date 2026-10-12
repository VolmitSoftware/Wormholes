package art.arcane.wormholes.modded.mixin;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class GravityPlayerMixin {
    @WrapMethod(method = "maybeBackOffFromEdge")
    private Vec3 wormholes$gravityEdge(Vec3 movement, MoverType type, Operation<Vec3> original) {
        GravityFrame frame = MinecraftGravity.frame((Player) (Object) this);
        return frame.equals(GravityFrame.WORLD) ? original.call(movement, type)
            : MinecraftGravity.world(frame, original.call(MinecraftGravity.local(frame, movement), type));
    }

    @Inject(method = "canFallAtLeast", at = @At("HEAD"), cancellable = true)
    private void wormholes$gravityEdgeSupport(double x, double z, double height, CallbackInfoReturnable<Boolean> callback) {
        Player player = (Player) (Object) this;
        GravityFrame frame = MinecraftGravity.frame(player);
        if (!frame.equals(GravityFrame.WORLD)) {
            AABB box = MinecraftGravity.transformBox(frame, player.getBoundingBox(), player.position(), true);
            AABB below = new AABB(box.minX + 1.0E-7D + x, box.minY - height - 1.0E-7D, box.minZ + 1.0E-7D + z,
                box.maxX - 1.0E-7D + x, box.minY, box.maxZ - 1.0E-7D + z);
            AABB world = MinecraftGravity.transformBox(frame, below, Vec3.ZERO, false).move(player.position());
            callback.setReturnValue(player.level().noCollision(player, world));
        }
    }

    @ModifyExpressionValue(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$localVelocity(Vec3 original) {
        GravityFrame frame = MinecraftGravity.frame((Player) (Object) this);
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.local(frame, original);
    }

    @ModifyExpressionValue(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getLookAngle()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$localSwimmingLook(Vec3 original) {
        GravityFrame frame = MinecraftGravity.frame((Player) (Object) this);
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.local(frame, original);
    }

    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void wormholes$worldVelocity(Player entity, Vec3 velocity, Operation<Void> original) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        original.call(entity, frame.equals(GravityFrame.WORLD) ? velocity : MinecraftGravity.world(frame, velocity));
    }
}
