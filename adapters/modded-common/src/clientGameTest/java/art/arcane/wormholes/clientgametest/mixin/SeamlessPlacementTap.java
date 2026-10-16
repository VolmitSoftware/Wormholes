package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.ArrivalPlacementTap;
import art.arcane.wormholes.clientgametest.ServerStartedCrossingTap;
import art.arcane.wormholes.modded.seamless.MinecraftSeamlessMove;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftSeamlessMove.class)
public abstract class SeamlessPlacementTap {
    @Inject(method = "run", at = @At("HEAD"))
    private static void wormholesTest$moving(MinecraftSeamlessMove.Context context, CallbackInfoReturnable<Boolean> callback) {
        ServerStartedCrossingTap.moving(context.player());
    }

    @Inject(method = "run", at = @At("RETURN"))
    private static void wormholesTest$placed(MinecraftSeamlessMove.Context context, CallbackInfoReturnable<Boolean> callback) {
        ServerPlayer player = context.player();
        ArrivalPlacementTap.placed(new ArrivalPlacementTap.Placement(player.level().dimension().identifier().toString(), context.pose().position(),
            player.position(), Boolean.TRUE.equals(callback.getReturnValue()), context.runtime().server().getTickCount()));
    }
}
