package art.arcane.wormholes.modded.mixin;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class GravityPlayerMixin {
    @WrapMethod(method = "maybeBackOffFromEdge")
    private Vec3 wormholes$gravityEdge(Vec3 movement, MoverType type, Operation<Vec3> original) {
        GravityFrame frame = MinecraftGravity.frame((Player) (Object) this);
        return frame.equals(GravityFrame.WORLD) ? original.call(movement, type)
            : MinecraftGravity.world(frame, original.call(MinecraftGravity.local(frame, movement), type));
    }

    @WrapOperation(method = {"maybeBackOffFromEdge", "isAboveGround"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/phys/AABB;move(DDD)Lnet/minecraft/world/phys/AABB;"))
    private AABB wormholes$gravityEdgeSupport(AABB box, double x, double y, double z, Operation<AABB> original) {
        Player player = (Player) (Object) this;
        GravityFrame frame = MinecraftGravity.frame(player);
        if (frame.equals(GravityFrame.WORLD)) {
            return original.call(box, x, y, z);
        }
        AABB local = MinecraftGravity.transformBox(frame, box, player.position(), true);
        return MinecraftGravity.transformBox(frame, original.call(local, x, y, z), Vec3.ZERO, false).move(player.position());
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

    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setDeltaMovement(DDD)V"))
    private void wormholes$worldVelocity(Player entity, double x, double y, double z, Operation<Void> original) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (frame.equals(GravityFrame.WORLD)) {
            original.call(entity, x, y, z);
        } else {
            Vec3 world = MinecraftGravity.world(frame, new Vec3(x, y, z));
            original.call(entity, world.x, world.y, world.z);
        }
    }
}
