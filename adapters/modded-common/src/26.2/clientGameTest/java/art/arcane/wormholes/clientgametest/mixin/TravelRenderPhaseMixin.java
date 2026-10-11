package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.TravelRenderPhaseTap;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.client.render.stencil.VanillaTerrainBackend;
import art.arcane.wormholes.modded.mixin.client.PortalLevelExtractorAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.ProjectionType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(value = ClientPortalRenderer.class, remap = false)
public abstract class TravelRenderPhaseMixin {
    @WrapMethod(method = "maintain(Lart/arcane/wormholes/modded/client/render/ClientPortalRenderer$Portal;)V", require = 1, allow = 1)
    private void wormholesTest$maintain(@Coerce Object portal, Operation<Void> original) {
        if (!TravelRenderPhaseTap.enabled()) {
            original.call(portal);
            return;
        }
        TravelRenderPhaseTap.Span span = TravelRenderPhaseTap.begin("ClientPortalRenderer.maintain", null, portal);
        try {
            original.call(portal);
        } finally {
            TravelRenderPhaseTap.end(span);
        }
    }

    @WrapMethod(method = "selectNextSection(Lart/arcane/wormholes/modded/client/render/ClientPortalRenderer$Portal;)Z", require = 1, allow = 1)
    private boolean wormholesTest$selectNextSection(@Coerce Object portal, Operation<Boolean> original) {
        if (!TravelRenderPhaseTap.enabled()) {
            return original.call(portal);
        }
        TravelRenderPhaseTap.Span span = TravelRenderPhaseTap.begin("ClientPortalRenderer.selectNextSection", null, portal);
        try {
            return original.call(portal);
        } finally {
            TravelRenderPhaseTap.end(span);
        }
    }

    @WrapMethod(method = "prepareDestination(Lart/arcane/wormholes/modded/client/render/ClientPortalRenderer$Portal;Lart/arcane/wormholes/modded/client/render/ClientPortalRenderer$RenderDimensions;)V", require = 1, allow = 1)
    private void wormholesTest$prepareDestination(@Coerce Object portal, @Coerce Object dimensions, Operation<Void> original) {
        if (!TravelRenderPhaseTap.enabled()) {
            original.call(portal, dimensions);
            return;
        }
        TravelRenderPhaseTap.Span span = TravelRenderPhaseTap.begin("ClientPortalRenderer.prepareDestination", null, portal);
        try {
            original.call(portal, dimensions);
        } finally {
            TravelRenderPhaseTap.end(span);
        }
    }

    @WrapMethod(method = "clearDestinationSky(Lart/arcane/wormholes/modded/client/render/ClientPortalRenderer$Portal;Lart/arcane/wormholes/modded/client/render/ClientPortalRenderer$RenderDimensions;Lcom/mojang/blaze3d/ProjectionType;)V", require = 1, allow = 1)
    private void wormholesTest$clearDestinationSky(@Coerce Object portal, @Coerce Object dimensions, ProjectionType projectionType,
                                                  Operation<Void> original) {
        if (!TravelRenderPhaseTap.enabled()) {
            original.call(portal, dimensions, projectionType);
            return;
        }
        TravelRenderPhaseTap.Span span = TravelRenderPhaseTap.begin("ClientPortalRenderer.clearDestinationSky", null, portal);
        try {
            original.call(portal, dimensions, projectionType);
        } finally {
            TravelRenderPhaseTap.end(span);
        }
    }

    @Mixin(targets = "art.arcane.wormholes.modded.client.render.stencil.PortalWorldRenderer", remap = false)
    public abstract static class World {
        @WrapMethod(method = "render(Lart/arcane/wormholes/modded/client/render/stencil/PortalView;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4f;IZZLnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/renderer/extract/LevelExtractor;)V", require = 1, allow = 1)
        private void wormholesTest$innerRender(PortalView view, CameraRenderState outer, Matrix4f projection, int depth,
                                              boolean mirrored, boolean outerMirrored, LevelRenderer renderer, LevelExtractor extractor,
                                              Operation<Void> original) {
            if (!TravelRenderPhaseTap.enabled()) {
                original.call(view, outer, projection, depth, mirrored, outerMirrored, renderer, extractor);
                return;
            }
            TravelRenderPhaseTap.Span span = TravelRenderPhaseTap.begin("PortalWorldRenderer.render.inner", view.destination(), view);
            try {
                original.call(view, outer, projection, depth, mirrored, outerMirrored, renderer, extractor);
            } finally {
                TravelRenderPhaseTap.end(span);
            }
        }

        @WrapMethod(method = "refreshLightmap(Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/renderer/Lightmap;Lart/arcane/wormholes/modded/client/render/stencil/LayerResources;)V", require = 1, allow = 1)
        private void wormholesTest$refreshLightmap(GameRenderer gameRenderer, ClientLevel destination, Lightmap lightmap,
                                                  @Coerce Object layer, Operation<Void> original) {
            if (!TravelRenderPhaseTap.enabled()) {
                original.call(gameRenderer, destination, lightmap, layer);
                return;
            }
            TravelRenderPhaseTap.Span span = TravelRenderPhaseTap.begin("PortalWorldRenderer.refreshLightmap", destination, lightmap);
            try {
                original.call(gameRenderer, destination, lightmap, layer);
            } finally {
                TravelRenderPhaseTap.end(span);
            }
        }
    }

    @Mixin(value = VanillaTerrainBackend.class, remap = false)
    public abstract static class Terrain {
        @WrapMethod(method = "beginLayer(Lart/arcane/wormholes/modded/client/render/stencil/PortalLayer;)V", require = 1, allow = 1)
        private void wormholesTest$beginLayer(PortalLayer layer, Operation<Void> original) {
            if (!TravelRenderPhaseTap.enabled()) {
                original.call(layer);
                return;
            }
            TravelRenderPhaseTap.Span span = TravelRenderPhaseTap.begin("VanillaTerrainBackend.beginLayer", layer.level(), layer);
            try {
                original.call(layer);
            } finally {
                TravelRenderPhaseTap.end(span);
            }
        }

        @WrapMethod(method = "discover(Lnet/minecraft/client/renderer/ViewArea;Lnet/minecraft/client/renderer/culling/Frustum;Lorg/joml/Vector4dc;Lnet/minecraft/world/phys/Vec3;Lit/unimi/dsi/fastutil/objects/ObjectArrayList;Lit/unimi/dsi/fastutil/objects/ObjectArrayList;)V", require = 1, allow = 1)
        private static void wormholesTest$discover(ViewArea area, Frustum frustum, Vector4dc plane, Vec3 camera,
                                                   ObjectArrayList<SectionRenderDispatcher.RenderSection> visible,
                                                   ObjectArrayList<SectionRenderDispatcher.RenderSection> nearby, Operation<Void> original) {
            if (!TravelRenderPhaseTap.enabled()) {
                original.call(area, frustum, plane, camera, visible, nearby);
                return;
            }
            TravelRenderPhaseTap.Span span = TravelRenderPhaseTap.begin("VanillaTerrainBackend.discover", null, area);
            try {
                original.call(area, frustum, plane, camera, visible, nearby);
            } finally {
                TravelRenderPhaseTap.end(span);
            }
        }
    }

    @Mixin(LevelExtractor.class)
    public abstract static class Extraction {
        @WrapMethod(method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V", require = 1, allow = 1)
        private void wormholesTest$extract(DeltaTracker deltaTracker, Camera camera, float partialTicks, Operation<Void> original) {
            if (!TravelRenderPhaseTap.enabled()) {
                original.call(deltaTracker, camera, partialTicks);
                return;
            }
            ClientLevel level = ((PortalLevelExtractorAccess) this).wormholes$portalLevel();
            TravelRenderPhaseTap.Span span = TravelRenderPhaseTap.begin("LevelExtractor.extract", level, this);
            try {
                original.call(deltaTracker, camera, partialTicks);
            } finally {
                TravelRenderPhaseTap.end(span);
            }
        }
    }
}
