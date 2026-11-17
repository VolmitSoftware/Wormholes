package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.client.GravityOverlay;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Forge-only: Forge moves the in-wall overlay probe from getViewBlockingState into its getOverlayBlock. */
@Mixin(ScreenEffectRenderer.class)
public abstract class StraddleOverlayForgeMixin {
    @WrapOperation(method = "getOverlayBlock", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;set(DDD)Lnet/minecraft/core/BlockPos$MutableBlockPos;"))
    private static BlockPos.MutableBlockPos wormholes$gravityOverlayProbe(BlockPos.MutableBlockPos probe, double x, double y, double z,
                                                                          Operation<BlockPos.MutableBlockPos> original,
                                                                          @Local(argsOnly = true) Player player) {
        if (MinecraftGravity.frame(player).equals(GravityFrame.WORLD)) {
            return original.call(probe, x, y, z);
        }
        Vec3 position = GravityOverlay.sample(player, new Vec3(x, y, z));
        return original.call(probe, position.x, position.y, position.z);
    }
}
