package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.client.CameraOrientation;
import art.arcane.wormholes.modded.client.GravityCameraAccess;
import art.arcane.wormholes.modded.client.GravityRenderTransform;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class GravityCameraMixin implements GravityCameraAccess, CameraOrientation.Holder {
    @Shadow private Entity entity;
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Unique private boolean wormholes$aligning;
    @Unique private boolean wormholes$oriented;
    @Unique private float wormholes$partialTicks;

    @Inject(method = "setup", at = @At("HEAD"))
    private void wormholes$beginGravityAlignment(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTicks,
                                                 CallbackInfo callback) {
        wormholes$aligning = true;
        wormholes$partialTicks = partialTicks;
    }

    @Inject(method = "setup", at = @At("RETURN"))
    private void wormholes$endGravityAlignment(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTicks,
                                               CallbackInfo callback) {
        wormholes$aligning = false;
    }

    @Inject(method = "setRotation", at = @At("HEAD"))
    private void wormholes$plainRotation(float yaw, float pitch, CallbackInfo callback) {
        wormholes$oriented = false;
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

    // WrapOperation rather than ModifyArgs: Forge's launcher cannot load ModifyArgs' synthetic Args classes.
    @WrapOperation(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
    private void wormholes$gravityEyePosition(Camera camera, double x, double y, double z, Operation<Void> original) {
        Vec3 position = wormholes$eyePosition(new Vec3(x, y, z));
        original.call(camera, position.x, position.y, position.z);
    }

    @Override
    public void wormholes$orientation(Quaternionf orientation) {
        rotation.set(CameraOrientation.stored(orientation));
        forwards.set(0.0F, 0.0F, 1.0F).rotate(rotation);
        up.set(0.0F, 1.0F, 0.0F).rotate(rotation);
        left.set(1.0F, 0.0F, 0.0F).rotate(rotation);
        wormholes$oriented = true;
    }

    @Override
    public boolean wormholes$oriented() {
        return wormholes$oriented;
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
