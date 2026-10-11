package art.arcane.wormholes.modded.mixin.client;

import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

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
}
