package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientStraddle;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LocalPlayer.class)
public abstract class StraddleLocalPlayerMixin {
    @ModifyReturnValue(method = "suffocatesAt", at = @At("RETURN"))
    private boolean wormholes$straddleSuffocation(boolean suffocates, @Local(argsOnly = true) BlockPos position) {
        if (!suffocates) {
            return false;
        }
        WormholesClient client = WormholesClient.instance();
        ClientStraddle straddle = client == null ? null : client.preparedTravel().straddle();
        return straddle == null || !straddle.behind(position.getX() + 0.5D, position.getY() + 0.5D, position.getZ() + 0.5D);
    }
}
