package art.arcane.wormholes.modded.mixin.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.client.ClientStraddles;
import art.arcane.wormholes.modded.client.GravityOverlay;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class StraddleOverlayMixin {
    @ModifyArgs(method = "/^get(?:ViewBlockingState|ViewBlockingStateAndPos|OverlayBlock)$/", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;set(DDD)Lnet/minecraft/core/BlockPos$MutableBlockPos;"), require = 1, allow = 1)
    private static void wormholes$gravityOverlayProbe(Args arguments, LocalPlayer player, Frustum frustum) {
        if (MinecraftGravity.frame(player).equals(GravityFrame.WORLD)) {
            return;
        }
        Vec3 position = GravityOverlay.sample(player, new Vec3(arguments.get(0), arguments.get(1), arguments.get(2)));
        arguments.set(0, position.x);
        arguments.set(1, position.y);
        arguments.set(2, position.z);
    }

    @Inject(method = "extractPlayerState", at = @At("RETURN"))
    private void wormholes$straddleOverlay(Camera camera, DeltaTracker tracker, float partialTicks, PlayerRenderState state, CallbackInfo callback) {
        if (ClientStraddles.hidesInWallOverlay(Minecraft.getInstance().player)) {
            state.blockOverlay = null;
        }
    }
}
