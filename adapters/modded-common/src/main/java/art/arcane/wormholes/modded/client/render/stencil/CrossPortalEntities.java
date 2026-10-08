/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: CrossPortalEntityRenderer as extra 26.x entity render states, relying on the portal clip plane
 * and the restored opening depth to cut the original and the projected copy at the portal.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

final class CrossPortalEntities {
    private static final double REACH_BLOCKS = 2.0D;
    private static final double SELF_HIDE_BLOCKS = 1.0D;
    private static final double YAW_EPSILON = 1.0E-6D;
    private static final Vec3d SOUTH = new Vec3d(0.0D, 0.0D, 1.0D);
    private static final Vec3d UP = new Vec3d(0.0D, 1.0D, 0.0D);

    private CrossPortalEntities() {
    }

    static void inner(PortalView view, LevelRenderState state, ClientLevel source, Camera camera, float partialTicks) {
        if (view.kind() == PortalView.Kind.MIRROR) {
            return;
        }
        copy(source, view.surface(), view.toDestination(), state, camera, partialTicks);
    }

    static void outer(List<PortalView> views, ClientLevel level, LevelRenderState state, Camera camera, float partialTicks) {
        for (PortalView view : views) {
            if (view.source() != level || view.kind() == PortalView.Kind.MIRROR) {
                continue;
            }
            copy(view.destination(), view.exit(), view.toDestination().inverse(), state, camera, partialTicks);
        }
    }

    private static void copy(ClientLevel from, PortalSurface surface, Similarity transform, LevelRenderState state, Camera camera,
                             float partialTicks) {
        if (!yawOnly(transform)) {
            return;
        }
        float yaw = yaw(transform);
        Box area = surface.area();
        AABB reach = new AABB(area.getXa(), area.getYa(), area.getZa(), area.getXb(), area.getYb(), area.getZb()).inflate(REACH_BLOCKS);
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        for (Entity entity : from.getEntities((Entity) null, reach, EntitySelector.ENTITY_STILL_ALIVE)) {
            AABB bounds = entity.getBoundingBox();
            if (!surface.straddles(new Box(bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, bounds.minZ, bounds.maxZ))) {
                continue;
            }
            Vec3 position = entity.getPosition(partialTicks);
            Vec3d mapped = transform.point(new Vec3d(position.x, position.y, position.z));
            if (hidesSelf(minecraft, entity, camera, transform, partialTicks)) {
                continue;
            }
            EntityRenderState copy = dispatcher.extractEntity(entity, partialTicks);
            if (copy instanceof LivingEntityRenderState living) {
                living.bodyRot += yaw;
                living.scale *= (float) transform.scale();
            } else if (!transform.isRigid()) {
                continue;
            }
            copy.x = mapped.x();
            copy.y = mapped.y();
            copy.z = mapped.z();
            copy.leashStates = null;
            state.entityRenderStates.add(copy);
        }
    }

    private static boolean hidesSelf(Minecraft minecraft, Entity entity, Camera camera, Similarity transform, float partialTicks) {
        if (entity != minecraft.player || camera.isDetached()) {
            return false;
        }
        Vec3 eye = entity.getEyePosition(partialTicks);
        Vec3d mapped = transform.point(new Vec3d(eye.x, eye.y, eye.z));
        Vec3 position = camera.position();
        return mapped.distance(new Vec3d(position.x, position.y, position.z)) < SELF_HIDE_BLOCKS;
    }

    private static boolean yawOnly(Similarity transform) {
        return !transform.rigid().reflects() && Math.abs(transform.direction(UP).y() - 1.0D) <= YAW_EPSILON;
    }

    private static float yaw(Similarity transform) {
        Vec3d south = transform.direction(SOUTH);
        return (float) Math.toDegrees(Math.atan2(-south.x(), south.z()));
    }
}
