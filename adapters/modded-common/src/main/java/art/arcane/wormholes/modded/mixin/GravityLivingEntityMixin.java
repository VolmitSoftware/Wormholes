package art.arcane.wormholes.modded.mixin;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class GravityLivingEntityMixin {
    @WrapOperation(method = "wouldNotSuffocateAtTargetPose", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityDimensions;makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;"))
    private AABB wormholes$gravityPoseBounds(EntityDimensions dimensions, Vec3 feet, Operation<AABB> original) {
        GravityFrame frame = MinecraftGravity.frame((LivingEntity) (Object) this);
        return frame.equals(GravityFrame.WORLD) ? original.call(dimensions, feet) : MinecraftGravity.bounds(frame, dimensions, feet);
    }

    @ModifyExpressionValue(method = {"aiStep", "jumpFromGround", "goDownInWater", "jumpInLiquid", "getEffectiveGravity", "travelFlying",
        "travelInAir", "travelInFluid", "travelInWater", "travelInLava", "jumpOutOfFluid", "travelFallFlying",
        "handleRelativeFrictionAndCalculateMovement"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$localVelocity(Vec3 original) {
        GravityFrame frame = MinecraftGravity.frame((LivingEntity) (Object) this);
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.local(frame, original);
    }

    @WrapOperation(method = {"aiStep", "goDownInWater", "jumpInLiquid", "travelFlying", "travelInAir", "travelInWater", "travelInLava",
        "travelFallFlying", "handleRelativeFrictionAndCalculateMovement"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void wormholes$worldVelocity(LivingEntity entity, Vec3 velocity, Operation<Void> original) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        original.call(entity, frame.equals(GravityFrame.WORLD) ? velocity : MinecraftGravity.world(frame, velocity));
    }

    @WrapOperation(method = {"aiStep", "jumpFromGround", "travelInAir", "jumpOutOfFluid"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(DDD)V"))
    private void wormholes$worldVelocity(LivingEntity entity, double x, double y, double z, Operation<Void> original) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (frame.equals(GravityFrame.WORLD)) {
            original.call(entity, x, y, z);
        } else {
            Vec3 world = MinecraftGravity.world(frame, new Vec3(x, y, z));
            original.call(entity, world.x, world.y, world.z);
        }
    }

    @WrapOperation(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void wormholes$worldImpulse(LivingEntity entity, Vec3 velocity, Operation<Void> original) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        original.call(entity, frame.equals(GravityFrame.WORLD) ? velocity : MinecraftGravity.world(frame, velocity));
    }

    @WrapOperation(method = {"travelFlying", "travelInWater", "travelInLava", "travelFallFlying", "handleRelativeFrictionAndCalculateMovement"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void wormholes$worldMovement(LivingEntity entity, MoverType type, Vec3 movement, Operation<Void> original) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        original.call(entity, type, frame.equals(GravityFrame.WORLD) ? movement : MinecraftGravity.world(frame, movement));
    }

    @ModifyExpressionValue(method = {"travelFallFlying", "updateFallFlyingMovement"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$localGlidingLook(Vec3 original) {
        GravityFrame frame = MinecraftGravity.frame((LivingEntity) (Object) this);
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.local(frame, original);
    }

    @ModifyExpressionValue(method = {"travelInFluid", "jumpOutOfFluid"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getY()D"))
    private double wormholes$localFluidHeight(double original) {
        LivingEntity entity = (LivingEntity) (Object) this;
        GravityFrame frame = MinecraftGravity.frame(entity);
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.local(frame, entity.position()).y;
    }

    @WrapOperation(method = "jumpOutOfFluid", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isFree(DDD)Z"))
    private boolean wormholes$worldFluidEscape(LivingEntity entity, double x, double y, double z, Operation<Boolean> original) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (frame.equals(GravityFrame.WORLD)) {
            return original.call(entity, x, y, z);
        }
        Vec3 world = MinecraftGravity.world(frame, new Vec3(x, y, z));
        return original.call(entity, world.x, world.y, world.z);
    }

    @ModifyExpressionValue(method = "calculateEntityAnimation", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;length(DDD)D"))
    private double wormholes$gravityWalkDistance(double original, @Local(argsOnly = true) boolean useY) {
        LivingEntity entity = (LivingEntity) (Object) this;
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (useY || frame.equals(GravityFrame.WORLD)) {
            return original;
        }
        Vec3 movement = MinecraftGravity.local(frame, entity.position().subtract(entity.xo, entity.yo, entity.zo));
        return Mth.length(movement.x, movement.z);
    }
}
