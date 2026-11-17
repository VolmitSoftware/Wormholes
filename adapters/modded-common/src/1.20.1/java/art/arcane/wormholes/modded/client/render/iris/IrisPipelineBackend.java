/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the Iris pipeline handling of IrisInterface and MyGameRenderer.switchAndRenderTheWorld, keeping the
 * level renderer's pipeline slot, Iris's selected pipeline and Iris's captured frame state across a portal layer.
 */
package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.stencil.PipelineBackend;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.mixin.client.IrisPortalPipelineAccess;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.renderer.LevelRenderer;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayDeque;

/**
 * Iris 1.7 and Oculus 1.8 on 1.20.1. Iris hooks {@code LevelRenderer.renderLevel}: at its head it stores the dimension's
 * pipeline in a field it adds to the renderer and makes it the selected pipeline, and at its return it nulls that field.
 * A layer re-entering a shared renderer would leave the main pass with no pipeline, so every layer restores the slot,
 * the selected pipeline and the captured matrices.
 *
 * <p>With a shader pack active the main pass draws into Iris's own targets, which share the main depth texture and are
 * cleared and resolved by the one pipeline instance. A layer re-entering that pipeline mid-frame would wipe the main
 * view, so shaded frames refuse stencil layers and portals use the streamed path composited after Iris's final pass.
 */
public final class IrisPipelineBackend implements PipelineBackend {
    public static final PipelineBackend INSTANCE = new IrisPipelineBackend();
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ArrayDeque<Layer> layers = new ArrayDeque<>();
    private Field rendererPipeline;
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
    public boolean prepareView(PortalView view) {
        return !deferred();
    }

    @Override
    public void beginLayer(PortalLayer layer) {
        if (deferred()) {
            throw new IllegalStateException("A portal layer cannot re-enter an active Iris shader pipeline; shaded portals use the streamed path");
        }
        IrisPortalPipelineAccess manager = (IrisPortalPipelineAccess) Iris.getPipelineManager();
        LevelRenderer renderer = layer.renderer();
        layers.push(new Layer(renderer, manager.wormholes$pipeline(), rendererPipeline(renderer), IrisCapturedState.capture()));
    }

    @Override
    public void endLayer(PortalLayer layer) {
        Layer entered = layers.pop();
        try {
            entered.captured().restore();
            rendererPipeline(entered.renderer(), entered.rendering());
        } finally {
            ((IrisPortalPipelineAccess) Iris.getPipelineManager()).wormholes$pipeline(entered.selected());
        }
    }

    private WorldRenderingPipeline rendererPipeline(LevelRenderer renderer) {
        Field slot = rendererPipeline();
        if (slot == null) {
            return null;
        }
        try {
            return (WorldRenderingPipeline) slot.get(renderer);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read the Iris pipeline slot of the level renderer", failure);
        }
    }

    private void rendererPipeline(LevelRenderer renderer, WorldRenderingPipeline pipeline) {
        Field slot = rendererPipeline();
        if (slot == null) {
            return;
        }
        try {
            slot.set(renderer, pipeline);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Unable to restore the Iris pipeline slot of the level renderer", failure);
        }
    }

    /** Iris adds the slot through a mixin {@code @Unique} field, so it is found by type rather than by name. */
    private Field rendererPipeline() {
        if (!rendererPipelineResolved) {
            rendererPipelineResolved = true;
            for (Field field : LevelRenderer.class.getDeclaredFields()) {
                if (field.getType() == WorldRenderingPipeline.class) {
                    try {
                        field.setAccessible(true);
                        rendererPipeline = field;
                    } catch (RuntimeException failure) {
                        LOGGER.error("Unable to open the Iris pipeline slot of the level renderer; portal layers that share the main"
                            + " renderer will leave it without a pipeline", failure);
                    }
                    break;
                }
            }
            if (rendererPipeline == null) {
                LOGGER.error("Iris added no pipeline slot to the level renderer; portal layers that share the main renderer"
                    + " will leave it without a pipeline");
            }
        }
        return rendererPipeline;
    }

    private record Layer(LevelRenderer renderer, WorldRenderingPipeline selected, WorldRenderingPipeline rendering,
                         IrisCapturedState captured) {
    }
}
