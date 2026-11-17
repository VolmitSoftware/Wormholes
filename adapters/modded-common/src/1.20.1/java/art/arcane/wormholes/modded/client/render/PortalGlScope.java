package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * Saves the render state the streamed renderer changes while drawing views into its own targets (bound framebuffers,
 * viewport, scissor, stencil test, front face, projection, model-view, fog and shader colour) and puts it back on close.
 */
final class PortalGlScope implements AutoCloseable {
    private final int drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
    private final int[] viewport = new int[4];
    private final boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
    private final int[] scissorBox = new int[4];
    private final boolean stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
    private final int frontFace = GL11C.glGetInteger(GL11C.GL_FRONT_FACE);
    private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
    private final VertexSorting sorting = RenderSystem.getVertexSorting();
    private final float fogStart = RenderSystem.getShaderFogStart();
    private final float fogEnd = RenderSystem.getShaderFogEnd();
    private final float[] fogColor = RenderSystem.getShaderFogColor().clone();
    private final FogShape fogShape = RenderSystem.getShaderFogShape();
    private final float[] shaderColor = RenderSystem.getShaderColor().clone();
    private final int texture0 = RenderSystem.getShaderTexture(0);
    private final int texture2 = RenderSystem.getShaderTexture(2);

    PortalGlScope() {
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissorBox);
        RenderSystem.getModelViewStack().pushPose();
    }

    /** Re-binds the framebuffer, viewport, scissor and stencil test that were current when the scope opened. */
    void rebindOuter() {
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
        GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        if (scissor) {
            GlStateManager._enableScissorTest();
            GlStateManager._scissorBox(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        } else {
            GlStateManager._disableScissorTest();
        }
        if (stencil) {
            GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        } else {
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        }
        GL11C.glFrontFace(frontFace);
    }

    /** Disables the outer stencil test and scissor so a view can draw its own target freely. */
    static void inner() {
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GlStateManager._disableScissorTest();
        GL11C.glFrontFace(GL11C.GL_CCW);
    }

    @Override
    public void close() {
        rebindOuter();
        RenderSystem.setProjectionMatrix(projection, sorting);
        RenderSystem.getModelViewStack().popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        RenderSystem.setShaderFogShape(fogShape);
        RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        RenderSystem.setShaderTexture(0, texture0);
        RenderSystem.setShaderTexture(2, texture2);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11C.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }
}
