package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhBlazeTemporalViews;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhTaaRenderer", remap = false)
public interface DhPortalBlazeTemporalAccess {
    @Accessor("colorTextureA")
    BlazeTextureWrapper wormholes$colorA();

    @Mutable
    @Accessor("colorTextureA")
    void wormholes$colorA(BlazeTextureWrapper texture);

    @Accessor("colorTextureB")
    BlazeTextureWrapper wormholes$colorB();

    @Mutable
    @Accessor("colorTextureB")
    void wormholes$colorB(BlazeTextureWrapper texture);

    @Accessor("taaDepthTextureWrapper")
    BlazeTextureWrapper wormholes$depth();

    @Mutable
    @Accessor("taaDepthTextureWrapper")
    void wormholes$depth(BlazeTextureWrapper texture);

    @Accessor("textureAIsHistory")
    boolean wormholes$historyA();

    @Accessor("textureAIsHistory")
    void wormholes$historyA(boolean history);

    @Accessor("previousDhProjMvmMatrix")
    DhMat4f wormholes$previousMatrix();

    @Mutable
    @Accessor("previousDhProjMvmMatrix")
    void wormholes$previousMatrix(DhMat4f matrix);

    @Accessor("previousCameraPos")
    DhVec3d wormholes$previousCamera();

    @Mutable
    @Accessor("previousCameraPos")
    void wormholes$previousCamera(DhVec3d camera);

    @Pseudo
    @Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer", remap = false)
    interface Targets {
        @Accessor("dhColorTextureWrapper")
        BlazeTextureWrapper wormholes$lodColor();

        @Mutable
        @Accessor("dhColorTextureWrapper")
        void wormholes$lodColor(BlazeTextureWrapper texture);

        @Accessor("dhDepthTextureWrapper")
        BlazeTextureWrapper wormholes$lodDepth();

        @Mutable
        @Accessor("dhDepthTextureWrapper")
        void wormholes$lodDepth(BlazeTextureWrapper texture);
    }

    @Pseudo
    @Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureViewWrapper", remap = false)
    abstract class ViewLease implements DhBlazeTemporalViews.BorrowedView {
        @Shadow
        private GpuTextureView textureView;

        @Inject(method = "tryWrap", at = @At("RETURN"))
        private void wormholes$retain(GpuTexture texture, CallbackInfo callback) {
            DhBlazeTemporalViews.retainBorrowedView(this, textureView);
        }

        @Override
        @Unique
        public void wormholes$release(GpuTextureView expected) {
            if (textureView != expected) {
                return;
            }
            textureView = null;
            expected.close();
        }
    }
}
