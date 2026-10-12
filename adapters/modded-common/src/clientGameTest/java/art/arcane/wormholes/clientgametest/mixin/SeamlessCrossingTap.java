package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.SeamlessTurningTap;
import art.arcane.wormholes.clientgametest.VerticalCameraLoopTap;
import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.network.client.TravelMessage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientSeamlessTravel.class)
public abstract class SeamlessCrossingTap {
    @Inject(method = "afterTick", at = @At("HEAD"), require = 1, allow = 1)
    private void wormholesTest$turnBeforeTickCrossing(CallbackInfo callback) {
        SeamlessTurningTap.beforeTickCrossing(Minecraft.getInstance());
    }

    @WrapMethod(method = "cross")
    private Vec3 wormholesTest$originalCrossing(Minecraft minecraft, LocalPlayer player, TravelMessage.TravelBegin arm,
                                               float partial, Vec3 previous, Vec3 eye, Operation<Vec3> original) {
        SeamlessTurningTap.Attempt attempt = SeamlessTurningTap.entering(minecraft, player, arm, partial, previous, eye);
        VerticalCameraLoopTap.Attempt vertical = VerticalCameraLoopTap.entering(minecraft, player, arm, partial, previous, eye);
        Vec3 result = original.call(minecraft, player, arm, partial, previous, eye);
        SeamlessTurningTap.returned(attempt, minecraft, result);
        VerticalCameraLoopTap.returned(vertical, minecraft, result);
        return result;
    }
}
