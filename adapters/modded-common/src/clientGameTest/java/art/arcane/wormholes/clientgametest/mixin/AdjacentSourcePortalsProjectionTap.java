package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.AdjacentSourcePortalsTap;
import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.modded.client.render.PortalScene;
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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class AdjacentSourcePortalsProjectionTap {
    @Inject(method = "render", at = @At("HEAD"), require = 1, allow = 1)
    private void wormholesTest$beginAdjacentFrame(CallbackInfo callback) {
        AdjacentSourcePortalsTap.beginFrame();
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0, require = 1, allow = 1)
    private Matrix4f wormholesTest$adjacentFinalProjection(Matrix4f projection) {
        AdjacentSourcePortalsTap.projection(projection);
        return projection;
    }

    @Inject(method = "render", at = @At("TAIL"), require = 1, allow = 1)
    private void wormholesTest$endAdjacentFrame(CallbackInfo callback) {
        AdjacentSourcePortalsTap.endFrame();
    }

    @Mixin(ClientSeamlessTravel.class)
    public abstract static class Crossings {
        @WrapMethod(method = "cross")
        private Vec3 wormholesTest$adjacentNativeCross(Minecraft minecraft, LocalPlayer player, TravelMessage.TravelBegin arm,
                                                      float partial, Vec3 previous, Vec3 eye, Operation<Vec3> original) {
            AdjacentSourcePortalsTap.Attempt attempt = AdjacentSourcePortalsTap.entering(minecraft, player, arm, previous, eye);
            Vec3 result = original.call(minecraft, player, arm, partial, previous, eye);
            AdjacentSourcePortalsTap.returned(attempt, minecraft, result);
            return result;
        }
    }

    @Mixin(targets = "art.arcane.wormholes.modded.client.render.stencil.PortalWorldRenderer", remap = false)
    public abstract static class Layers {
        @Inject(method = "render(Lart/arcane/wormholes/modded/client/render/stencil/PortalView;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4f;IZZLnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/renderer/extract/LevelExtractor;)V",
            at = @At("HEAD"), require = 1, allow = 1)
        private void wormholesTest$adjacentLayer(PortalView view, CameraRenderState camera, Matrix4f projection,
                                                int depth, boolean mirrored, boolean outerMirrored, LevelRenderer renderer,
                                                LevelExtractor extractor, CallbackInfo callback) {
            AdjacentSourcePortalsTap.layer(view, depth);
        }
    }

    @Mixin(targets = "art.arcane.wormholes.modded.client.render.ClientPortalRenderer", remap = false)
    public abstract static class Meshes {
        @Inject(method = "renderTreeContent", at = @At("HEAD"), require = 1, allow = 1)
        private void wormholesTest$adjacentMesh(@Coerce Object portal, Matrix4d toRoot, @Coerce Object viewport,
                                               @Coerce Object dimensions, CallbackInfoReturnable<Boolean> callback) {
            AdjacentSourcePortalsTap.mesh(((MeshPortalAccess) portal).wormholesTest$adjacentScene().geometry(),
                ((MeshDimensionsAccess) dimensions).wormholesTest$adjacentDepth());
        }
    }

    @Mixin(targets = "art.arcane.wormholes.modded.client.render.ClientPortalRenderer$Portal", remap = false)
    public interface MeshPortalAccess {
        @Accessor("scene")
        PortalScene wormholesTest$adjacentScene();
    }

    @Mixin(targets = "art.arcane.wormholes.modded.client.render.ClientPortalRenderer$RenderDimensions", remap = false)
    public interface MeshDimensionsAccess {
        @Accessor("depth")
        int wormholesTest$adjacentDepth();
    }
}
