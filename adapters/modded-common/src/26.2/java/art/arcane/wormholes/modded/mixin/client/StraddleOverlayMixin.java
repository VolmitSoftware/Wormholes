package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientStraddles;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ScreenEffectRenderer.class)
public abstract class StraddleOverlayMixin {
    @ModifyReturnValue(method = "getViewBlockingState", at = @At("RETURN"))
    private static BlockState wormholes$straddleOverlay(BlockState state, Player player) {
        return player == Minecraft.getInstance().player && ClientStraddles.hidesInWallOverlay(player) ? null : state;
    }
}
