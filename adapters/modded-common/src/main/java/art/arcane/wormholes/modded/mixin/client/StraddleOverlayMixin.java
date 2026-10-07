package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelExtractor.class)
public abstract class StraddleOverlayMixin {
    @ModifyReturnValue(method = "getViewBlockingState", at = @At("RETURN"))
    private static BlockState wormholes$straddleOverlay(BlockState state) {
        WormholesClient client = WormholesClient.instance();
        return state != null && client != null && client.preparedTravel().straddle() != null ? null : state;
    }
}
