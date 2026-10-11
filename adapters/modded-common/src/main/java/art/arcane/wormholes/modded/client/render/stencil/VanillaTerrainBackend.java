/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: VisibleSectionDiscovery reduced to a synchronous frustum and clip plane sweep over the 26.x view area.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.mixin.client.PortalLevelRendererAccess;
import art.arcane.wormholes.modded.mixin.client.VanillaTerrainRendererAccess;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4dc;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;

public final class VanillaTerrainBackend implements TerrainBackend {
    public static final VanillaTerrainBackend INSTANCE = new VanillaTerrainBackend();
    private static final double NEARBY_BLOCKS = 32.0D;

    private final Deque<LayerPreparation> layers = new ArrayDeque<>();

    private VanillaTerrainBackend() {
    }

    @Override
    public String name() {
        return "vanilla";
    }

    @Override
    public boolean clipsTerrain() {
        return true;
    }

    @Override
    public void beginLayer(PortalLayer layer) {
        layer.visibleSections().clear();
        layer.nearbySections().clear();
        layers.push(new LayerPreparation(layer));
    }

    @Override
    public void endLayer(PortalLayer layer) {
        LayerPreparation preparation = layers.peek();
        if (preparation == null || preparation.layer != layer) {
            throw new IllegalStateException("Vanilla terrain layer preparation is unbalanced");
        }
        layers.pop();
    }

    public void discoverSections(LevelRenderer renderer) {
        LayerPreparation preparation = current(renderer);
        if (preparation == null) {
            return;
        }
        PortalLayer layer = preparation.layer;
        ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = layer.visibleSections();
        ObjectArrayList<SectionRenderDispatcher.RenderSection> nearby = layer.nearbySections();
        visible.clear();
        nearby.clear();
        ViewArea area = renderer.viewArea();
        Vec3 camera = layer.camera().pos;
        if (area != null) {
            if (!layer.shared()) {
                area.repositionCamera(SectionPos.of(camera));
            }
            discover(area, layer.camera().cullFrustum, layer.worldClipPlane(), camera, visible, nearby);
        }
        preparation.discovered = area != null;
    }

    public void prepareDraw(LevelRenderer renderer, CameraRenderState camera) {
        LayerPreparation preparation = current(renderer);
        if (preparation == null || !preparation.discovered || preparation.prepared || preparation.layer.shared()
            || preparation.layer.camera() != camera) {
            return;
        }
        VanillaTerrainRendererAccess access = (VanillaTerrainRendererAccess) renderer;
        SectionRenderDispatcher dispatcher = access.wormholes$terrainDispatcher();
        if (dispatcher == null) {
            return;
        }
        access.wormholes$compileTerrain(camera);
        ((PortalLevelRendererAccess) renderer).wormholes$portalState().sectionUpdateRenderStates.clear();
        dispatcher.lock();
        try {
            dispatcher.uploadTerrainBuffersToGpu();
        } finally {
            dispatcher.unlock();
        }
        preparation.prepared = true;
    }

    public boolean preparedDraw(LevelRenderer renderer) {
        LayerPreparation preparation = current(renderer);
        return preparation != null && preparation.prepared;
    }

    private LayerPreparation current(LevelRenderer renderer) {
        LayerPreparation preparation = layers.peek();
        return preparation != null && preparation.layer.renderer() == renderer ? preparation : null;
    }

    private static void discover(ViewArea area, Frustum frustum, Vector4dc plane, Vec3 camera,
                                 ObjectArrayList<SectionRenderDispatcher.RenderSection> visible,
                                 ObjectArrayList<SectionRenderDispatcher.RenderSection> nearby) {
        SectionPos center = area.getCameraSectionPos();
        int radius = area.getViewDistance();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                for (int y = area.minSectionY(); y <= area.maxSectionY(); y++) {
                    SectionRenderDispatcher.RenderSection section = area.getRenderSectionAt(cursor.set(x << 4, y << 4, z << 4));
                    if (section == null || section.getSectionNode() != SectionPos.asLong(x, y, z)) {
                        continue;
                    }
                    AABB bounds = section.getBoundingBox();
                    if (!frustum.isVisible(bounds) || clipped(bounds, plane)) {
                        continue;
                    }
                    visible.add(section);
                    if (bounds.distanceToSqr(camera) <= NEARBY_BLOCKS * NEARBY_BLOCKS) {
                        nearby.add(section);
                    }
                }
            }
        }
        Comparator<SectionRenderDispatcher.RenderSection> nearest = Comparator.comparingDouble(section -> section.getBoundingBox().getCenter().distanceToSqr(camera));
        visible.sort(nearest);
        nearby.sort(nearest);
    }

    private static boolean clipped(AABB bounds, Vector4dc plane) {
        double x = plane.x() >= 0.0D ? bounds.maxX : bounds.minX;
        double y = plane.y() >= 0.0D ? bounds.maxY : bounds.minY;
        double z = plane.z() >= 0.0D ? bounds.maxZ : bounds.minZ;
        return plane.x() * x + plane.y() * y + plane.z() * z + plane.w() < 0.0D;
    }

    private static final class LayerPreparation {
        private final PortalLayer layer;
        private boolean discovered;
        private boolean prepared;

        private LayerPreparation(PortalLayer layer) {
            this.layer = layer;
        }
    }
}
