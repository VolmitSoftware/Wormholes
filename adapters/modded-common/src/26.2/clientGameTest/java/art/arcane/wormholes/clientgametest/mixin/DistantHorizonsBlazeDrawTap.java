package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.DistantHorizonsTap;
import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer", remap = false)
public abstract class DistantHorizonsBlazeDrawTap {
    @Shadow
    @Final
    private static DhVec3f MODEL_POS;

    @Unique
    private boolean wormholesTest$loggedPipeline;

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/common/render/blaze/wrappers/RenderPassWrapper;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void wormholesTest$pipeline(RenderPassWrapper pass, RenderPipeline pipeline) {
        if (!wormholesTest$loggedPipeline) {
            System.out.println("DH Blaze pipeline shaders=" + pipeline.getVertexShader() + "/" + pipeline.getFragmentShader() + " OpenGL=" + PortalClipShaders.openGl());
            wormholesTest$loggedPipeline = true;
        }
        pass.setPipeline(pipeline);
    }

    @WrapMethod(method = "render(Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/util/objects/SortedArraySet;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V")
    private void wormholesTest$terrain(RenderParams params, boolean opaque, SortedArraySet<LodBufferContainer> buffers,
                                       IProfilerWrapper profiler, Operation<Void> original) {
        int distances = DistantHorizonsTap.beginBlazeTerrain(params);
        try {
            original.call(params, opaque, buffers, profiler);
            DistantHorizonsTap.stageImage("dh-child-terrain", BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getTextureView());
        } finally {
            DistantHorizonsTap.endBlazeTerrain(distances);
        }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/util/objects/SortedArraySet;get(I)Ljava/lang/Object;"))
    private Object wormholesTest$buffer(SortedArraySet<LodBufferContainer> buffers, int index) {
        LodBufferContainer container = buffers.get(index);
        DistantHorizonsTap.buffer(container);
        return container;
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/common/render/blaze/wrappers/RenderPassWrapper;drawIndexed(I)V"))
    private void wormholesTest$draw(RenderPassWrapper pass, int count) {
        DistantHorizonsTap.modelOffset(MODEL_POS);
        int query = DistantHorizonsTap.beforeBlazeDraw(count);
        try {
            pass.drawIndexed(count);
            DistantHorizonsTap.verifyBlazeDraw(count);
        } finally {
            DistantHorizonsTap.afterDraw(query);
        }
    }
}
