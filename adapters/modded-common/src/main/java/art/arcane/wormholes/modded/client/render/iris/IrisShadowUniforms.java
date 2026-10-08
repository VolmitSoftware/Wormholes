package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalRenderer;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalUniforms;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.irisshaders.iris.mixinterface.ShadowRenderListAccess;
import net.minecraft.client.renderer.LevelRenderer;

record IrisShadowUniforms(UniformBufferManager uniforms, GpuBufferSlice data, boolean written) {
    static IrisShadowUniforms reset(LevelRenderer renderer) {
        if (!(renderer instanceof LevelRendererExtension extension)) {
            return null;
        }
        SodiumWorldRenderer world = extension.sodium$getWorldRenderer();
        UniformBufferManager uniforms = world == null ? null : ((SodiumPortalRenderer) world).wormholes$uniforms();
        if (uniforms == null) {
            return null;
        }
        ShadowRenderListAccess shadow = (ShadowRenderListAccess) uniforms;
        SodiumPortalUniforms buffers = (SodiumPortalUniforms) uniforms;
        shadow.iris$beginShadowRenderListScope();
        try {
            IrisShadowUniforms saved = new IrisShadowUniforms(uniforms, buffers.wormholes$data(), buffers.wormholes$written());
            buffers.wormholes$written(false);
            return saved;
        } finally {
            shadow.iris$endShadowRenderListScope();
        }
    }

    void restore() {
        ShadowRenderListAccess shadow = (ShadowRenderListAccess) uniforms;
        SodiumPortalUniforms buffers = (SodiumPortalUniforms) uniforms;
        shadow.iris$beginShadowRenderListScope();
        try {
            buffers.wormholes$data(data);
            buffers.wormholes$written(written);
        } finally {
            shadow.iris$endShadowRenderListScope();
        }
    }
}
