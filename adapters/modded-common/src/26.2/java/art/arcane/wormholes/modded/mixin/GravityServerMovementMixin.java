package art.arcane.wormholes.modded.mixin;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class GravityServerMovementMixin {
    @Shadow
    public ServerPlayer player;

    @ModifyVariable(method = "handleMovePlayer", at = @At("STORE"), name = "movedUpwards")
    private boolean wormholes$gravityJump(boolean original, @Local(name = "xDist") double x,
                                          @Local(name = "yDist") double y, @Local(name = "zDist") double z) {
        GravityFrame frame = MinecraftGravity.frame(player);
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.local(frame, new Vec3(x, y, z)).y > 0.0D;
    }

    @ModifyVariable(method = "handleMovePlayer", at = @At("STORE"), name = "oyDist")
    private double wormholes$gravityFloating(double original, @Local(name = "xDist") double x, @Local(name = "zDist") double z) {
        GravityFrame frame = MinecraftGravity.frame(player);
        return frame.equals(GravityFrame.WORLD) ? original : MinecraftGravity.local(frame, new Vec3(x, original, z)).y;
    }

    @WrapOperation(method = "noBlocksAround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;expandTowards(DDD)Lnet/minecraft/world/phys/AABB;"))
    private AABB wormholes$gravitySupportArea(AABB box, double x, double y, double z, Operation<AABB> original,
                                              @Local(argsOnly = true) Entity entity) {
        GravityFrame frame = MinecraftGravity.frame(entity);
        if (frame.equals(GravityFrame.WORLD)) {
            return original.call(box, x, y, z);
        }
        Vec3 extension = MinecraftGravity.world(frame, new Vec3(x, y, z));
        return original.call(box, extension.x, extension.y, extension.z);
    }
}
