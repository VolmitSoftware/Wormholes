package art.arcane.wormholes.modded.mixin;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.wormholes.modded.gravity.GravityCollision;
import art.arcane.wormholes.modded.gravity.GravityHolder;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(Entity.class)
public abstract class GravityEntityMixin implements GravityHolder {
    @Unique
    private GravityFrame wormholes$gravity = GravityFrame.WORLD;
    @Shadow
    private boolean onGroundNoBlocks;
    @Shadow
    protected abstract void checkFallDamage(double amount, boolean onGround, BlockState state, BlockPos position);
    @Shadow
    private void restituteMovementAfterCollisions(BlockState state, boolean xCollision, boolean zCollision, Vec3 movement) {
        throw new AssertionError();
    }

    @Unique
    private Vec3 wormholes$requestedMovement;
    @Unique
    private Vec3 wormholes$resolvedMovement;
    @Unique
    private boolean wormholes$restituted;

    @Override
    public GravityFrame wormholesGravity() {
        return wormholes$gravity == null ? GravityFrame.WORLD : wormholes$gravity;
    }

    @Override
    public void wormholesGravity(GravityFrame frame) {
        wormholes$gravity = MinecraftGravity.fromIndex(frame.orientation().index());
    }

    @Inject(method = "saveWithoutId", at = @At("TAIL"))
    private void wormholes$saveGravity(ValueOutput output, CallbackInfo callback) {
        int index = wormholesGravity().orientation().index();
        if (index != AxisPermutation.IDENTITY.index()) {
            output.putInt("wormholesGravity", index);
        }
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void wormholes$loadGravity(ValueInput input, CallbackInfo callback) {
        MinecraftGravity.set((Entity) (Object) this, MinecraftGravity.fromIndex(input.getIntOr("wormholesGravity", AxisPermutation.IDENTITY.index())));
    }

    @Inject(method = "makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;", at = @At("HEAD"), cancellable = true)
    private void wormholes$gravityBounds(Vec3 feet, CallbackInfoReturnable<AABB> callback) {
        if (!wormholesGravity().equals(GravityFrame.WORLD)) {
            callback.setReturnValue(MinecraftGravity.bounds((Entity) (Object) this, feet));
        }
    }

    @Inject(method = "getEyePosition()Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
    private void wormholes$gravityEye(CallbackInfoReturnable<Vec3> callback) {
        Entity entity = (Entity) (Object) this;
        GravityFrame frame = wormholesGravity();
        if (!frame.equals(GravityFrame.WORLD)) {
            callback.setReturnValue(MinecraftGravity.eye(frame, entity.position(), entity.getEyeHeight()));
        }
    }

    @Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
    private void wormholes$gravityEye(float partialTick, CallbackInfoReturnable<Vec3> callback) {
        Entity entity = (Entity) (Object) this;
        GravityFrame frame = wormholesGravity();
        if (!frame.equals(GravityFrame.WORLD)) {
            Vec3 feet = new Vec3(Mth.lerp(partialTick, entity.xo, entity.getX()), Mth.lerp(partialTick, entity.yo, entity.getY()),
                Mth.lerp(partialTick, entity.zo, entity.getZ()));
            callback.setReturnValue(MinecraftGravity.eye(frame, feet, entity.getEyeHeight()));
        }
    }

    @ModifyReturnValue(method = "getEyeY", at = @At("RETURN"))
    private double wormholes$gravityEyeY(double original) {
        Entity entity = (Entity) (Object) this;
        GravityFrame frame = wormholesGravity();
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.eye(frame, entity.position(), entity.getEyeHeight()).y;
    }

    @ModifyReturnValue(method = {"getViewVector", "getLookAngle", "getHeadLookAngle", "getUpVector", "getForward"}, at = @At("RETURN"))
    private Vec3 wormholes$gravityLook(Vec3 original) {
        GravityFrame frame = wormholesGravity();
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.world(frame, original);
    }

    @ModifyExpressionValue(method = "moveRelative", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getInputVector(Lnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$gravityInput(Vec3 original) {
        GravityFrame frame = wormholesGravity();
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.world(frame, original);
    }

    @Inject(method = "getOnPos(F)Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
    private void wormholes$gravitySupport(float offset, CallbackInfoReturnable<BlockPos> callback) {
        if (!wormholesGravity().equals(GravityFrame.WORLD)) {
            callback.setReturnValue(MinecraftGravity.support((Entity) (Object) this, offset));
        }
    }

    @Inject(method = "checkSupportingBlock", at = @At("HEAD"), cancellable = true)
    private void wormholes$gravitySupportingBlock(boolean onGround, Vec3 movement, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        GravityFrame frame = wormholesGravity();
        if (frame.equals(GravityFrame.WORLD)) {
            return;
        }
        if (!onGround) {
            entity.mainSupportingBlockPos = Optional.empty();
            onGroundNoBlocks = false;
        } else {
            AABB local = MinecraftGravity.transformBox(frame, entity.getBoundingBox(), entity.position(), true);
            AABB face = new AABB(local.minX, local.minY - 1.0E-6D, local.minZ, local.maxX, local.minY, local.maxZ);
            AABB world = MinecraftGravity.transformBox(frame, face, Vec3.ZERO, false).move(entity.position());
            entity.mainSupportingBlockPos = entity.level().findSupportingBlock(entity, world);
            onGroundNoBlocks = entity.mainSupportingBlockPos.isEmpty();
        }
        callback.cancel();
    }

    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void wormholes$gravityCollision(Vec3 movement, CallbackInfoReturnable<Vec3> callback) {
        GravityFrame frame = wormholesGravity();
        if (!frame.equals(GravityFrame.WORLD)) {
            callback.setReturnValue(GravityCollision.collide((Entity) (Object) this, frame, movement));
        }
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$rememberMovement(Entity entity, Vec3 movement, Operation<Vec3> original) {
        Vec3 resolved = original.call(entity, movement);
        if (!wormholesGravity().equals(GravityFrame.WORLD)) {
            wormholes$requestedMovement = movement;
            wormholes$resolvedMovement = resolved;
            wormholes$restituted = false;
        }
        return resolved;
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZZLnet/minecraft/world/phys/Vec3;)V"))
    private void wormholes$gravityGround(Entity entity, boolean grounded, boolean horizontalCollision, Vec3 movement, Operation<Void> original) {
        GravityFrame frame = wormholesGravity();
        if (frame.equals(GravityFrame.WORLD) || wormholes$requestedMovement == null) {
            original.call(entity, grounded, horizontalCollision, movement);
        } else {
            wormholes$collisionFlags(entity, frame);
            original.call(entity, entity.verticalCollisionBelow, entity.horizontalCollision, movement);
        }
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"))
    private void wormholes$gravityFall(Entity entity, double amount, boolean grounded, BlockState state, BlockPos position, Operation<Void> original) {
        GravityFrame frame = wormholesGravity();
        if (frame.equals(GravityFrame.WORLD)) {
            original.call(entity, amount, grounded, state, position);
        } else {
            wormholes$collisionFlags(entity, frame);
            entity.setOnGroundWithMovement(entity.verticalCollisionBelow, entity.horizontalCollision, wormholes$resolvedMovement);
            original.call(entity, MinecraftGravity.local(frame, wormholes$resolvedMovement).y, entity.onGround(), state, position);
        }
    }

    @Inject(method = "restituteMovementAfterCollisions", at = @At("HEAD"))
    private void wormholes$gravityRestitution(CallbackInfo callback) {
        GravityFrame frame = wormholesGravity();
        if (!frame.equals(GravityFrame.WORLD)) {
            wormholes$collisionFlags((Entity) (Object) this, frame);
            wormholes$restituted = true;
        }
    }

    @ModifyVariable(method = "restituteMovementAfterCollisions", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean wormholes$gravityRestitutionX(boolean original) {
        GravityFrame frame = wormholesGravity();
        if (frame.equals(GravityFrame.WORLD)) {
            return original;
        }
        return !Mth.equal(MinecraftGravity.local(frame, wormholes$requestedMovement).x, MinecraftGravity.local(frame, wormholes$resolvedMovement).x);
    }

    @ModifyVariable(method = "restituteMovementAfterCollisions", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private boolean wormholes$gravityRestitutionZ(boolean original) {
        GravityFrame frame = wormholesGravity();
        if (frame.equals(GravityFrame.WORLD)) {
            return original;
        }
        return !Mth.equal(MinecraftGravity.local(frame, wormholes$requestedMovement).z, MinecraftGravity.local(frame, wormholes$resolvedMovement).z);
    }

    @ModifyVariable(method = "restituteMovementAfterCollisions", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 wormholes$gravityRestitutionMovement(Vec3 original) {
        GravityFrame frame = wormholesGravity();
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.local(frame, wormholes$resolvedMovement);
    }

    @Inject(method = "move", at = @At("HEAD"))
    private void wormholes$beginMovement(MoverType type, Vec3 movement, CallbackInfo callback) {
        wormholes$requestedMovement = null;
        wormholes$resolvedMovement = null;
        wormholes$restituted = false;
    }

    @ModifyExpressionValue(method = {"applyGravity", "restituteMovementAfterCollisions"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$localVelocity(Vec3 original) {
        GravityFrame frame = wormholesGravity();
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.local(frame, original);
    }

    @WrapOperation(method = {"applyGravity", "restituteMovementAfterCollisions"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void wormholes$worldVelocity(Entity entity, Vec3 velocity, Operation<Void> original) {
        GravityFrame frame = wormholesGravity();
        original.call(entity, frame.equals(GravityFrame.WORLD) ? velocity : MinecraftGravity.world(frame, velocity));
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;multiply(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 wormholes$gravityBlockDrag(Vec3 velocity, double x, double y, double z, Operation<Vec3> original) {
        GravityFrame frame = wormholesGravity();
        return frame.equals(GravityFrame.WORLD) ? original.call(velocity, x, y, z)
            : MinecraftGravity.world(frame, MinecraftGravity.local(frame, velocity).multiply(x, y, z));
    }

    @Inject(method = "move", at = @At("TAIL"))
    private void wormholes$gravityMovementComplete(MoverType type, Vec3 movement, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        GravityFrame frame = wormholesGravity();
        if (frame.equals(GravityFrame.WORLD) || entity.noPhysics || wormholes$requestedMovement == null) {
            return;
        }
        wormholes$collisionFlags(entity, frame);
        entity.setOnGroundWithMovement(entity.verticalCollisionBelow, entity.horizontalCollision, wormholes$resolvedMovement);
        if (!wormholes$restituted && (entity.verticalCollision || entity.horizontalCollision)) {
            Vec3 requested = MinecraftGravity.local(frame, wormholes$requestedMovement);
            Vec3 resolved = MinecraftGravity.local(frame, wormholes$resolvedMovement);
            BlockPos support = entity.getOnPosLegacy();
            restituteMovementAfterCollisions(entity.level().getBlockState(support), !Mth.equal(requested.x, resolved.x),
                !Mth.equal(requested.z, resolved.z), resolved);
        }
        wormholes$requestedMovement = null;
        wormholes$resolvedMovement = null;
    }

    @Inject(method = "doCheckFallDamage", at = @At("HEAD"), cancellable = true)
    private void wormholes$gravityServerFall(double x, double y, double z, boolean grounded, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        GravityFrame frame = wormholesGravity();
        if (!frame.equals(GravityFrame.WORLD)) {
            if (!entity.touchingUnloadedChunk()) {
                Vec3 movement = new Vec3(x, y, z);
                entity.setOnGroundWithMovement(grounded, entity.horizontalCollision, movement);
                BlockPos position = entity.getOnPosLegacy();
                checkFallDamage(MinecraftGravity.local(frame, movement).y, grounded, entity.level().getBlockState(position), position);
            }
            callback.cancel();
        }
    }

    @Unique
    private void wormholes$collisionFlags(Entity entity, GravityFrame frame) {
        Vec3 requested = MinecraftGravity.local(frame, wormholes$requestedMovement);
        Vec3 resolved = MinecraftGravity.local(frame, wormholes$resolvedMovement);
        entity.horizontalCollision = !Mth.equal(requested.x, resolved.x) || !Mth.equal(requested.z, resolved.z);
        entity.verticalCollision = requested.y != resolved.y;
        entity.verticalCollisionBelow = entity.verticalCollision && requested.y < 0.0D;
    }
}
