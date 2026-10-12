package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.client.GravityRenderState;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public abstract class GravityAvatarRendererMixin {
    @Inject(method = "getRenderOffset(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"), cancellable = true)
    private void wormholes$gravityCrouching(AvatarRenderState state, CallbackInfoReturnable<Vec3> callback) {
        GravityFrame frame = ((GravityRenderState) state).wormholes$gravity();
        if (frame.equals(GravityFrame.WORLD) || !state.isCrouching) {
            return;
        }
        double offset = state.scale * -2.0F / 16.0D;
        Face up = frame.up();
        callback.setReturnValue(callback.getReturnValue().add(offset * up.x(), offset * (up.y() - 1), offset * up.z()));
    }

    @Inject(method = "extractFlightData", at = @At("TAIL"))
    private void wormholes$gravityFlight(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo callback) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (frame.equals(GravityFrame.WORLD)) {
            return;
        }
        ClientAvatarState avatar = ((ClientAvatarEntity) entity).avatarState();
        Vec3 look = MinecraftGravity.local(frame, entity.getViewVector(partialTicks));
        Vec3 movement = MinecraftGravity.local(frame, avatar.deltaMovementOnPreviousTick().lerp(entity.getDeltaMovement(), partialTicks));
        if (movement.horizontalDistanceSqr() <= 1.0E-5F || look.horizontalDistanceSqr() <= 1.0E-5F) {
            state.shouldApplyFlyingYRot = false;
            state.flyingYRot = 0.0F;
            return;
        }
        state.shouldApplyFlyingYRot = true;
        double dot = movement.horizontal().normalize().dot(look.horizontal().normalize());
        double sign = movement.x * look.z - movement.z * look.x;
        state.flyingYRot = (float) (Math.signum(sign) * Math.acos(Math.min(1.0D, Math.abs(dot))));
    }

    @Inject(method = "extractCapeState", at = @At("TAIL"))
    private void wormholes$gravityCape(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo callback) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (frame.equals(GravityFrame.WORLD)) {
            return;
        }
        ClientAvatarState avatar = ((ClientAvatarEntity) entity).avatarState();
        Vec3 delta = MinecraftGravity.local(frame, new Vec3(
            avatar.getInterpolatedCloakX(partialTicks) - Mth.lerp(partialTicks, entity.xo, entity.getX()),
            avatar.getInterpolatedCloakY(partialTicks) - Mth.lerp(partialTicks, entity.yo, entity.getY()),
            avatar.getInterpolatedCloakZ(partialTicks) - Mth.lerp(partialTicks, entity.zo, entity.getZ())));
        float bodyYaw = Mth.rotLerp(partialTicks, entity.yBodyRotO, entity.yBodyRot);
        double forwardX = Mth.sin((float) Math.toRadians(bodyYaw));
        double forwardZ = -Mth.cos((float) Math.toRadians(bodyYaw));
        state.capeFlap = Mth.clamp((float) delta.y * 10.0F, -6.0F, 32.0F);
        state.capeLean = Mth.clamp((float) (delta.x * forwardX + delta.z * forwardZ) * 100.0F * (1.0F - state.fallFlyingScale()), 0.0F, 150.0F);
        state.capeLean2 = Mth.clamp((float) (delta.x * forwardZ - delta.z * forwardX) * 100.0F, -20.0F, 20.0F);
        state.capeFlap += Mth.sin(avatar.getInterpolatedWalkDistance(partialTicks) * 6.0F) * 32.0F * avatar.getInterpolatedBob(partialTicks);
    }
}
