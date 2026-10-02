package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class PortalLevelRendererMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "lambda$addMainPass$0", at = @At("HEAD"))
    private void wormholes$prepare(GpuBufferSlice fog, boolean improvedTransparency, ChunkSectionsToRender sections,
                                  FeatureRenderDispatcher.PreparedFrame frame, boolean outlines, boolean panorama,
                                  CallbackInfo callback) {
        ClientPortalRenderer.instance().prepare(levelRenderState.cameraRenderState, fog);
    }

    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void wormholes$composite(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame frame,
                                    RenderPass pass, CallbackInfo callback) {
        ClientPortalRenderer.instance().composite(pass);
    }
}
