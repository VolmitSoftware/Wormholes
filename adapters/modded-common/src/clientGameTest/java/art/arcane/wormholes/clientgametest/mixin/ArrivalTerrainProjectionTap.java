package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.ArrivalTerrainTap;
import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.network.client.TravelMessage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class ArrivalTerrainProjectionTap {
    @Inject(method = "render", at = @At("HEAD"), require = 1, allow = 1)
    private void wormholesTest$beginArrivalFrame(CallbackInfo callback) {
        ArrivalTerrainTap.beginFrame();
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0, require = 1, allow = 1)
    private Matrix4f wormholesTest$arrivalProjection(Matrix4f projection) {
        ArrivalTerrainTap.projection(projection);
        return projection;
    }

    @Inject(method = "render", at = @At("TAIL"), require = 1, allow = 1)
    private void wormholesTest$endArrivalFrame(CallbackInfo callback) {
        ArrivalTerrainTap.endFrame();
    }

    @Mixin(ClientSeamlessTravel.class)
    public abstract static class Crossings {
        @WrapMethod(method = "cross")
        private Vec3 wormholesTest$arrivalNativeCross(Minecraft minecraft, LocalPlayer player, TravelMessage.TravelBegin arm,
                                                    float partial, Vec3 previous, Vec3 eye, Operation<Vec3> original) {
            ArrivalTerrainTap.Attempt attempt = ArrivalTerrainTap.entering(minecraft, player, arm, previous, eye);
            Vec3 result = original.call(minecraft, player, arm, partial, previous, eye);
            ArrivalTerrainTap.returned(attempt, minecraft, result);
            return result;
        }
    }
}
