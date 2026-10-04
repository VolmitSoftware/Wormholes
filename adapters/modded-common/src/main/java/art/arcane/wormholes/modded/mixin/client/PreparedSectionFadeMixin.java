package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class PreparedSectionFadeMixin {
    @Shadow private long uploadedTime;
    @Inject(method = "getVisibility", at = @At("HEAD"), cancellable = true)
    private void wormholesPreparedSectionVisibility(long now, long fadeDuration, CallbackInfoReturnable<Float> callback) {
        SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) (Object) this;
        Minecraft minecraft = Minecraft.getInstance();
        if (ClientPortalRenderer.instance().coversMainSection(section.getSectionNode())
            && minecraft.levelRenderer.viewArea() != null
            && minecraft.levelRenderer.viewArea().getRenderSectionAt(section.getRenderOrigin()) == section
            && section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED) {
            uploadedTime = Math.min(uploadedTime, now - Math.max(fadeDuration,
                Util.toMillis(minecraft.options.chunkSectionFadeInTime().get())));
            callback.setReturnValue(1.0f);
        }
    }
}
