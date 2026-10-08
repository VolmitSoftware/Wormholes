/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the Iris pipeline handling of IrisInterface and MyGameRenderer.switchAndRenderTheWorld, keeping the
 * level renderer's pipeline slot and Iris's selected pipeline across a portal layer, carrying the layer's clip plane and making
 * the layer rewrite Iris's per-frame program uniforms and shadow terrain uniforms.
 */
package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.stencil.PipelineBackend;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import art.arcane.wormholes.modded.mixin.client.IrisPortalPipelineAccess;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.renderer.LevelRenderer;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;

public final class IrisPipelineBackend implements PipelineBackend {
    public static final PipelineBackend INSTANCE = new IrisPipelineBackend();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RENDERER_PIPELINE = "pipeline";

    private final ArrayDeque<Layer> layers = new ArrayDeque<>();
    private VarHandle rendererPipeline;
    private boolean rendererPipelineResolved;

    private IrisPipelineBackend() {
    }

    @Override
    public String name() {
        return deferred() ? "iris" : "iris-off";
    }

    @Override
    public boolean deferred() {
        return Iris.isPackInUseQuick();
    }

    @Override
    public void beginLayer(PortalLayer layer) {
        PipelineManager manager = Iris.getPipelineManager();
        LevelRenderer renderer = layer.renderer();
        VarHandle slot = rendererPipeline();
        WorldRenderingPipeline rendering = slot == null ? null : (WorldRenderingPipeline) slot.get(renderer);
        IrisShadowUniforms shadows = deferred() ? IrisShadowUniforms.reset(renderer) : null;
        layers.push(new Layer(renderer, manager.getPipelineNullable(), rendering, shadows));
        if (slot != null) {
            slot.set(renderer, (WorldRenderingPipeline) null);
        }
        IrisClipPlanes.push(layer.clipSpacePlane());
        IrisLayerUniforms.transition();
    }

    @Override
    public void endLayer(PortalLayer layer) {
        Layer entered = layers.pop();
        IrisClipPlanes.pop();
        IrisLayerUniforms.transition();
        if (entered.shadows() != null) {
            entered.shadows().restore();
        }
        VarHandle slot = rendererPipeline();
        if (slot != null) {
            slot.set(entered.renderer(), entered.rendering());
        }
        ((IrisPortalPipelineAccess) Iris.getPipelineManager()).wormholes$pipeline(entered.selected());
    }

    private VarHandle rendererPipeline() {
        if (!rendererPipelineResolved) {
            rendererPipelineResolved = true;
            try {
                rendererPipeline = MethodHandles.privateLookupIn(LevelRenderer.class, MethodHandles.lookup())
                    .findVarHandle(LevelRenderer.class, RENDERER_PIPELINE, WorldRenderingPipeline.class);
            } catch (NoSuchFieldException | IllegalAccessException failure) {
                LOGGER.error("Unable to reach the Iris pipeline slot of the level renderer; portal layers that share the current level renderer"
                    + " can lose its pipeline while shaders are off", failure);
            }
        }
        return rendererPipeline;
    }

    private record Layer(LevelRenderer renderer, WorldRenderingPipeline selected, WorldRenderingPipeline rendering, IrisShadowUniforms shadows) {
    }
}
