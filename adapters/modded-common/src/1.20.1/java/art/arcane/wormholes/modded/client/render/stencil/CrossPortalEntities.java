/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: CrossPortalEntityRenderer for 1.20.1, drawing a transformed copy of each entity that straddles
 * an opening on the far side, relying on the portal clip plane and the restored opening depth to cut both at the portal.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

final class CrossPortalEntities {
    private static final double REACH_BLOCKS = 2.0D;

    private CrossPortalEntities() {
    }

    /** Inside a layer: entities of the viewing level that reach through the opening, placed at the destination. */
    static void inner(PortalView view, Vec3 camera, PoseStack pose, MultiBufferSource.BufferSource buffers, float partialTicks) {
        if (view.kind() == PortalView.Kind.MIRROR) {
            return;
        }
        copy(view.source(), view.surface(), view.toDestination(), camera, pose, buffers, partialTicks);
    }

    /** In the viewing level: entities of each destination that reach back through the opening. */
    static void outer(List<PortalView> views, ClientLevel level, Vec3 camera, PoseStack pose, MultiBufferSource.BufferSource buffers,
                      float partialTicks) {
        for (PortalView view : views) {
            if (view.source() != level || view.kind() == PortalView.Kind.MIRROR) {
                continue;
            }
            copy(view.destination(), view.exit(), view.toDestination().inverse(), camera, pose, buffers, partialTicks);
        }
    }

    private static void copy(ClientLevel from, PortalSurface surface, Similarity transform, Vec3 camera, PoseStack pose,
                             MultiBufferSource.BufferSource buffers, float partialTicks) {
        if (transform.rigid().reflects()) {
            return;
        }
        Box area = surface.area();
        AABB reach = new AABB(area.getXa(), area.getYa(), area.getZa(), area.getXb(), area.getYb(), area.getZb()).inflate(REACH_BLOCKS);
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        List<Entity> entities = from.getEntities((Entity) null, reach, EntitySelector.ENTITY_STILL_ALIVE);
        if (entities.isEmpty()) {
            return;
        }
        float scale = (float) transform.scale();
        boolean shadows = minecraft.options.entityShadows().get();
        dispatcher.setRenderShadow(false);
        try {
            for (Entity entity : entities) {
                AABB bounds = entity.getBoundingBox();
                if (!surface.straddles(new Box(bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, bounds.minZ, bounds.maxZ))) {
                    continue;
                }
                if (entity == minecraft.player) {
                    Vec3 eye = entity.getEyePosition(partialTicks);
                    Vec3d mappedEye = transform.point(new Vec3d(eye.x, eye.y, eye.z));
                    if (!PortalWorldRenderer.detached(new Vec3(mappedEye.x(), mappedEye.y(), mappedEye.z()), camera)) {
                        continue;
                    }
                }
                Vec3 position = entity.getPosition(partialTicks);
                Vec3d mapped = transform.point(new Vec3d(position.x, position.y, position.z));
                pose.pushPose();
                pose.translate(mapped.x() - camera.x, mapped.y() - camera.y, mapped.z() - camera.z);
                pose.mulPoseMatrix(PortalLayerMath.rotation(transform.rigid().permutation()));
                pose.scale(scale, scale, scale);
                dispatcher.render(entity, 0.0D, 0.0D, 0.0D, Mth.lerp(partialTicks, entity.yRotO, entity.getYRot()), partialTicks, pose, buffers,
                    dispatcher.getPackedLightCoords(entity, partialTicks));
                pose.popPose();
            }
        } finally {
            dispatcher.setRenderShadow(shadows);
        }
        buffers.endBatch();
    }
}
