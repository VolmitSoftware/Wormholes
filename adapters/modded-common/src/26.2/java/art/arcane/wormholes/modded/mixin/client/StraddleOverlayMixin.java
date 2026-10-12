package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.client.ClientStraddles;
import art.arcane.wormholes.modded.client.GravityOverlay;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(ScreenEffectRenderer.class)
public abstract class StraddleOverlayMixin {
    @ModifyArgs(method = "/^get(?:ViewBlockingState|ViewBlockingStateAndPos|OverlayBlock)$/", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;set(DDD)Lnet/minecraft/core/BlockPos$MutableBlockPos;"), require = 1, allow = 1)
    private static void wormholes$gravityOverlayProbe(Args arguments, Player player) {
        if (MinecraftGravity.frame(player).equals(GravityFrame.WORLD)) {
            return;
        }
        Vec3 position = GravityOverlay.sample(player, new Vec3(arguments.get(0), arguments.get(1), arguments.get(2)));
        arguments.set(0, position.x);
        arguments.set(1, position.y);
        arguments.set(2, position.z);
    }

    @ModifyReturnValue(method = "getViewBlockingState", at = @At("RETURN"))
    private static BlockState wormholes$straddleOverlay(BlockState state, Player player) {
        return player == Minecraft.getInstance().player && ClientStraddles.hidesInWallOverlay(player) ? null : state;
    }
}
