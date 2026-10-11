package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.NetherCrossingEffectsTap;
import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.network.client.TravelMessage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class NetherCrossingEffectsProjectionTap {
    @Inject(method = "render", at = @At("HEAD"), require = 1, allow = 1)
    private void wormholesTest$beginEffectsFrame(CallbackInfo callback) {
        NetherCrossingEffectsTap.beginFrame();
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0, require = 1, allow = 1)
    private Matrix4f wormholesTest$finalEffectsProjection(Matrix4f projection) {
        LevelRenderState state = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState;
        NetherCrossingEffectsTap.projection(projection, new NetherCrossingEffectsTap.Effects(state.playerRenderState.portalEffectIntensity,
            state.playerRenderState.nauseaEffectIntensity, state.playerRenderState.spinningEffectAngle, state.worldPartialTicks, "extracted-26.3"));
        return projection;
    }

    @Inject(method = "render", at = @At("TAIL"), require = 1, allow = 1)
    private void wormholesTest$endEffectsFrame(CallbackInfo callback) {
        NetherCrossingEffectsTap.endFrame();
    }

    @Mixin(targets = "art.arcane.wormholes.modded.client.render.stencil.PortalWorldRenderer", remap = false)
    public abstract static class Layers {
        @Inject(method = "render(Lart/arcane/wormholes/modded/client/render/stencil/PortalView;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4f;IZZLnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/renderer/extract/LevelExtractor;)V",
            at = @At("HEAD"), require = 1, allow = 1)
        private void wormholesTest$renderedDepth(PortalView view, CameraRenderState camera, Matrix4f projection,
                                               int depth, boolean mirrored, boolean outerMirrored, LevelRenderer renderer,
                                               LevelExtractor extractor, CallbackInfo callback) {
            NetherCrossingEffectsTap.layer(view, depth);
        }
    }

    @Mixin(ClientSeamlessTravel.class)
    public abstract static class Crossings {
        @WrapMethod(method = "cross")
        private Vec3 wormholesTest$effectsNativeCross(Minecraft minecraft, LocalPlayer player, TravelMessage.TravelBegin arm,
                                                     float partial, Vec3 previous, Vec3 eye, Operation<Vec3> original) {
            NetherCrossingEffectsTap.CrossingAttempt attempt = NetherCrossingEffectsTap.entering(minecraft, player, arm, partial, previous, eye);
            Vec3 result = original.call(minecraft, player, arm, partial, previous, eye);
            NetherCrossingEffectsTap.returned(attempt, minecraft, result);
            return result;
        }
    }
}
