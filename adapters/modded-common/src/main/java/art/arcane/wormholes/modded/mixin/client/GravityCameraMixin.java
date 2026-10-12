package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.client.GravityCameraAccess;
import art.arcane.wormholes.modded.client.GravityRenderTransform;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class GravityCameraMixin implements GravityCameraAccess {
    @Shadow private Entity entity;
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
    @Shadow private int matrixPropertiesDirty;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Unique private boolean wormholes$aligning;
    @Unique private float wormholes$partialTicks;
    @Unique private Entity wormholes$scaleEntity;
    @Unique private float wormholes$entityScale = 1.0F;

    @Inject(method = "tick", at = @At("HEAD"))
    private void wormholes$beforeEyeHeightTick(CallbackInfo callback) {
        wormholes$scaleEyeHistory();
    }

    @Inject(method = "alignWithEntity", at = @At("HEAD"))
    private void wormholes$beginGravityAlignment(float partialTicks, CallbackInfo callback) {
        wormholes$scaleEyeHistory();
        wormholes$aligning = true;
        wormholes$partialTicks = partialTicks;
    }

    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void wormholes$endGravityAlignment(float partialTicks, CallbackInfo callback) {
        wormholes$aligning = false;
    }

    @Inject(method = "setRotation", at = @At("TAIL"))
    private void wormholes$gravityRotation(float yaw, float pitch, CallbackInfo callback) {
        if (!wormholes$aligning || entity == null) {
            return;
        }
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (!frame.equals(GravityFrame.WORLD)) {
            wormholes$orientation(GravityRenderTransform.camera(frame, yaw, pitch));
        }
    }

    @ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
    private void wormholes$gravityEyePosition(Args arguments) {
        Vec3 position = wormholes$eyePosition(new Vec3(arguments.get(0), arguments.get(1), arguments.get(2)));
        arguments.set(0, position.x);
        arguments.set(1, position.y);
        arguments.set(2, position.z);
    }

    @ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(Lnet/minecraft/world/phys/Vec3;)V"), index = 0)
    private Vec3 wormholes$gravityMountedEyePosition(Vec3 position) {
        return wormholes$eyePosition(position);
    }

    @Override
    public void wormholes$orientation(Quaternionf orientation) {
        rotation.set(orientation);
        forwards.set(0.0F, 0.0F, -1.0F).rotate(rotation);
        up.set(0.0F, 1.0F, 0.0F).rotate(rotation);
        left.set(-1.0F, 0.0F, 0.0F).rotate(rotation);
        matrixPropertiesDirty |= 3;
    }

    @Unique
    private void wormholes$scaleEyeHistory() {
        if (entity == null) {
            wormholes$scaleEntity = null;
            wormholes$entityScale = 1.0F;
            return;
        }
        float scale = entity instanceof LivingEntity living ? living.getScale() : 1.0F;
        if (entity != wormholes$scaleEntity) {
            wormholes$scaleEntity = entity;
            wormholes$entityScale = scale;
            eyeHeight = entity.getEyeHeight();
            eyeHeightOld = eyeHeight;
            return;
        }
        if (scale != wormholes$entityScale) {
            float ratio = scale / wormholes$entityScale;
            eyeHeight *= ratio;
            eyeHeightOld *= ratio;
            wormholes$entityScale = scale;
        }
    }

    @Unique
    private Vec3 wormholes$eyePosition(Vec3 position) {
        if (entity == null) {
            return position;
        }
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (frame.equals(GravityFrame.WORLD)) {
            return position;
        }
        double height = Mth.lerp(wormholes$partialTicks, eyeHeightOld, eyeHeight);
        Face up = frame.up();
        return position.add(height * up.x(), height * (up.y() - 1), height * up.z());
    }
}
