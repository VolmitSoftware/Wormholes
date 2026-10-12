/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: CrossPortalEntityRenderer as extra 26.x entity render states, relying on the portal clip plane
 * and the restored opening depth to cut the original and the projected copy at the portal.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.GravityRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
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
    private static final double POSITION_EPSILON_SQUARED = 1.0E-10D;

    private CrossPortalEntities() {
    }

    static void inner(PortalView view, LevelRenderState state, ClientLevel source, float partialTicks) {
        if (view.kind() == PortalView.Kind.MIRROR) {
            return;
        }
        copy(source, view.surface(), view.toDestination(), state, partialTicks);
    }

    static void outer(List<PortalView> views, ClientLevel level, LevelRenderState state, float partialTicks) {
        for (PortalView view : views) {
            if (view.source() != level || view.kind() == PortalView.Kind.MIRROR) {
                continue;
            }
            copy(view.destination(), view.exit(), view.toDestination().inverse(), state, partialTicks);
        }
    }

    private static void copy(ClientLevel from, PortalSurface surface, Similarity transform, LevelRenderState state, float partialTicks) {
        if (transform.rigid().reflects()) {
            return;
        }
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
            if (entity == minecraft.player) {
                Vec3 eye = entity.getEyePosition(partialTicks);
                Vec3d mappedEye = transform.point(new Vec3d(eye.x, eye.y, eye.z));
                if (!PortalWorldRenderer.detached(new Vec3(mappedEye.x(), mappedEye.y(), mappedEye.z()), state.cameraRenderState.pos)
                    || playerAlreadyVisible(state.entityRenderStates, entity.getId(), mapped)) {
                    continue;
                }
            }
            EntityRenderState copy = dispatcher.extractEntity(entity, partialTicks);
            if (!transform(copy, transform)) {
                continue;
            }
            copy.x = mapped.x();
            copy.y = mapped.y();
            copy.z = mapped.z();
            copy.leashStates = null;
            state.entityRenderStates.add(copy);
        }
    }

    static boolean transform(EntityRenderState copy, Similarity transform) {
        if (transform.rigid().reflects()) {
            return false;
        }
        if (copy instanceof LivingEntityRenderState living) {
            GravityRenderState gravity = (GravityRenderState) copy;
            gravity.wormholes$gravity(gravity.wormholes$gravity().transformed(transform.rigid().permutation()));
            living.scale *= (float) transform.scale();
        } else if (!transform.isRigid() || transform.rigid().permutation().face(Face.U) != Face.U) {
            return false;
        }
        float scale = (float) transform.scale();
        copy.boundingBoxWidth *= scale;
        copy.boundingBoxHeight *= scale;
        copy.eyeHeight *= scale;
        if (!transform.isRigid() || transform.rigid().permutation().face(Face.U) != Face.U) {
            copy.shadowPieces.clear();
            copy.shadowRadius = 0.0F;
        }
        if (copy.nameTagAttachment != null) {
            Vec3 attachment = copy.nameTagAttachment;
            Vec3d mapped = transform.vector(new Vec3d(attachment.x, attachment.y, attachment.z));
            copy.nameTagAttachment = new Vec3(mapped.x(), mapped.y(), mapped.z());
        }
        if (copy.passengerOffset != null) {
            Vec3 offset = copy.passengerOffset;
            Vec3d mapped = transform.vector(new Vec3d(offset.x, offset.y, offset.z));
            copy.passengerOffset = new Vec3(mapped.x(), mapped.y(), mapped.z());
        }
        return true;
    }


    static boolean playerAlreadyVisible(List<EntityRenderState> states, int playerId, Vec3d position) {
        for (EntityRenderState state : states) {
            if (!(state instanceof AvatarRenderState avatar) || avatar.id != playerId) {
                continue;
            }
            double dx = state.x - position.x();
            double dy = state.y - position.y();
            double dz = state.z - position.z();
            if (dx * dx + dy * dy + dz * dz <= POSITION_EPSILON_SQUARED) {
                return true;
            }
        }
        return false;
    }

}
