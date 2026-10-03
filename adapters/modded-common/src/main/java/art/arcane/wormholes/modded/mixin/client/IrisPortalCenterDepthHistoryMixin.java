package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisHistory;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.CenterDepthSampler", remap = false)
public abstract class IrisPortalCenterDepthHistoryMixin implements PortalIrisHistory.State {
    @Shadow @Final private GlFramebuffer framebuffer;
    @Shadow @Final private int texture;
    @Shadow @Final private int altTexture;
    @Shadow private boolean hasFirstSample;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholes$capture(CallbackInfo callback) {
        PortalIrisHistory.register(this);
    }

    @Override
    public void wormholes$resetHistory() {
        int previous = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        int[] mask = new int[4];
        GL30C.glGetIntegeri_v(GL11C.GL_COLOR_WRITEMASK, 0, mask);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer.getId());
        try {
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL30C.glColorMaski(0, true, true, true, true);
            float[] initial = new float[]{Float.NaN, 0, 0, 0};
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, texture, 0);
            GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, initial);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, altTexture, 0);
            GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, initial);
            hasFirstSample = false;
        } finally {
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, texture, 0);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previous);
            GL30C.glColorMaski(0, mask[0] != 0, mask[1] != 0, mask[2] != 0, mask[3] != 0);
            if (scissor) {
                GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            }
        }
    }
}
