package art.arcane.wormholes.modded.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Renderer state a portal layer swaps around its {@code renderLevel} re-entry. */
@Mixin(LevelRenderer.class)
public interface PortalLevelRendererAccess {
    @Accessor("level")
    ClientLevel wormholes$level();

    @Accessor("renderBuffers")
    RenderBuffers wormholes$renderBuffers();

    @Accessor("cullingFrustum")
    Frustum wormholes$cullingFrustum();

    @Accessor("cullingFrustum")
    void wormholes$cullingFrustum(Frustum frustum);

    @Accessor("capturedFrustum")
    Frustum wormholes$capturedFrustum();

    @Accessor("capturedFrustum")
    void wormholes$capturedFrustum(Frustum frustum);

    @Accessor("captureFrustum")
    boolean wormholes$captureFrustum();

    @Accessor("captureFrustum")
    void wormholes$captureFrustum(boolean capture);

    @Accessor("transparencyChain")
    PostChain wormholes$transparencyChain();

    @Accessor("transparencyChain")
    void wormholes$transparencyChain(PostChain chain);

    @Accessor("translucentTarget")
    RenderTarget wormholes$translucentTarget();

    @Accessor("translucentTarget")
    void wormholes$translucentTarget(RenderTarget target);

    @Accessor("itemEntityTarget")
    RenderTarget wormholes$itemEntityTarget();

    @Accessor("itemEntityTarget")
    void wormholes$itemEntityTarget(RenderTarget target);

    @Accessor("particlesTarget")
    RenderTarget wormholes$particlesTarget();

    @Accessor("particlesTarget")
    void wormholes$particlesTarget(RenderTarget target);

    @Accessor("weatherTarget")
    RenderTarget wormholes$weatherTarget();

    @Accessor("weatherTarget")
    void wormholes$weatherTarget(RenderTarget target);

    @Accessor("cloudsTarget")
    RenderTarget wormholes$cloudsTarget();

    @Accessor("cloudsTarget")
    void wormholes$cloudsTarget(RenderTarget target);
}
