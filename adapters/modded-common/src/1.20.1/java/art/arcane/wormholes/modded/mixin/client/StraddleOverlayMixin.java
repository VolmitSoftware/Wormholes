package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.client.ClientStraddles;
import art.arcane.wormholes.modded.client.GravityOverlay;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ScreenEffectRenderer.class)
public abstract class StraddleOverlayMixin {
    // Vanilla probes in getViewBlockingState; Forge moves the probe into getOverlayBlock (StraddleOverlayForgeMixin).
    @WrapOperation(method = "getViewBlockingState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;set(DDD)Lnet/minecraft/core/BlockPos$MutableBlockPos;"), require = 0)
    private static BlockPos.MutableBlockPos wormholes$gravityOverlayProbe(BlockPos.MutableBlockPos probe, double x, double y, double z,
                                                                          Operation<BlockPos.MutableBlockPos> original,
                                                                          @Local(argsOnly = true) Player player) {
        if (MinecraftGravity.frame(player).equals(GravityFrame.WORLD)) {
            return original.call(probe, x, y, z);
        }
        Vec3 position = GravityOverlay.sample(player, new Vec3(x, y, z));
        return original.call(probe, position.x, position.y, position.z);
    }

    @ModifyExpressionValue(method = "renderScreenEffect",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z"))
    private static boolean wormholes$straddleOverlay(boolean noPhysics, @Local(argsOnly = true) Minecraft minecraft) {
        return noPhysics || ClientStraddles.hidesInWallOverlay(minecraft.player);
    }
}
