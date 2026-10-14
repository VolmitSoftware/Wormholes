package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.PortalBufferSourceAccess;
import art.arcane.wormholes.modded.mixin.client.PortalOutlineBufferAccess;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;

public final class PortalRenderBuffers {
    private PortalRenderBuffers() {
    }

    public static FeatureRenderDispatcher dispatcher(Minecraft minecraft, SubmitNodeStorage submits, RenderBuffers buffers, GameRenderState state) {
        return new FeatureRenderDispatcher(submits, minecraft.getModelManager(), buffers.bufferSource(), minecraft.getAtlasManager(),
            buffers.outlineBufferSource(), buffers.crumblingBufferSource(), minecraft.font, state);
    }

    public static void close(RenderBuffers buffers) {
        buffers.fixedBufferPack().close();
        release(buffers.bufferSource());
        release(buffers.crumblingBufferSource());
        release(((PortalOutlineBufferAccess) buffers.outlineBufferSource()).wormholes$buffers());
    }

    private static void release(MultiBufferSource.BufferSource source) {
        PortalBufferSourceAccess access = (PortalBufferSourceAccess) source;
        for (ByteBufferBuilder buffer : access.wormholes$fixedBuffers().values()) {
            buffer.close();
        }
        access.wormholes$sharedBuffer().close();
    }
}
