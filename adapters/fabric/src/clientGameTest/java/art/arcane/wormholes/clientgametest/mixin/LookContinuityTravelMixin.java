package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.LookContinuityTravelTap;
import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ClientSeamlessTravel.class, remap = false)
public abstract class LookContinuityTravelMixin {
    @Inject(method = "cross", at = @At("HEAD"))
    private void wormholesLookTest$begin(Minecraft client, LocalPlayer player, TravelMessage.TravelBegin arm, float partial,
                                        Vec3 previous, Vec3 eye, CallbackInfoReturnable<Vec3> callback) {
        if (LookContinuityTravelTap.active()) {
            LookContinuityTravelTap.begin((ClientSeamlessTravel) (Object) this,
                new LookContinuityTravelTap.NativeCall(client, player, arm, partial, previous, eye));
        }
    }

    @Inject(method = "cross", at = @At("RETURN"))
    private void wormholesLookTest$finish(CallbackInfoReturnable<Vec3> callback) {
        LookContinuityTravelTap.finish(callback.getReturnValue() != null);
    }
}
