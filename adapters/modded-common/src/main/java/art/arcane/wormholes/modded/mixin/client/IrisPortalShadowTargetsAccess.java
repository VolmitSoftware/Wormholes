package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalShadowTargets;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderTargets", remap = false)
public interface IrisPortalShadowTargetsAccess extends PortalShadowTargets {
    @Override
    @Accessor("ownedFramebuffers")
    List<GlFramebuffer> wormholes$framebuffers();
}
