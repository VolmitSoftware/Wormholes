package art.arcane.wormholes.modded.client;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class ClientEntityCrossings {
    private final List<Entity> pending = new ArrayList<>();

    boolean receive(ClientLevel level, Entity self, TravelMessage.EntityCrossed crossed) {
        Entity entity = level == null ? null : level.getEntity(crossed.entityId());
        if (entity == null || entity == self || entity.isLocalInstanceAuthoritative()) {
            return false;
        }
        CrossingEntity holder = (CrossingEntity) entity;
        ClientEntityCrossing previous = holder.wormholes$crossing();
        if (previous != null) {
            previous.resolve(entity);
        }
        ClientEntityCrossing crossing = new ClientEntityCrossing(crossed, ClientEntityCrossing.echoed(entity));
        if (crossing.reached(entity)) {
            crossing.resolve(entity);
        }
        if (crossing.settled()) {
            holder.wormholes$crossing(null);
            return true;
        }
        holder.wormholes$crossing(crossing);
        if (previous == null) {
            pending.add(entity);
        }
        return true;
    }

    void tick() {
        Iterator<Entity> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            CrossingEntity holder = (CrossingEntity) entity;
            ClientEntityCrossing crossing = holder.wormholes$crossing();
            if (crossing == null || entity.isRemoved()) {
                holder.wormholes$crossing(null);
                iterator.remove();
                continue;
            }
            boolean expired = crossing.expired();
            if (expired || crossing.reached(entity)) {
                crossing.resolve(entity);
            }
            if (expired || crossing.settled()) {
                holder.wormholes$crossing(null);
                iterator.remove();
            }
        }
    }

    void clear() {
        for (Entity entity : pending) {
            ((CrossingEntity) entity).wormholes$crossing(null);
        }
        pending.clear();
    }

    static void cross(Entity entity, OpticTransform toward, Vec3d velocity) {
        Vec3 position = point(toward, entity.position());
        Vec3 old = point(toward, entity.oldPosition());
        InterpolationHandler interpolation = entity.getInterpolation();
        if (toward.isTranslation()) {
            carry(interpolation, position.subtract(entity.position()));
            entity.setPos(position);
            entity.setOldPosAndRot(old, entity.yRotO, entity.xRotO);
        } else {
            interpolation.cancel();
            Angles.Look look = toward.look(new Angles.Look(entity.getYRot(), entity.getXRot()));
            Angles.Look previous = toward.look(new Angles.Look(entity.yRotO, entity.xRotO));
            entity.setYRot(look.yaw());
            entity.setXRot(look.pitch());
            entity.setYHeadRot(toward.yaw(entity.getYHeadRot()));
            entity.setPos(position);
            entity.setOldPosAndRot(old, previous.yaw(), previous.pitch());
        }
        entity.setDeltaMovement(new Vec3(velocity.x(), velocity.y(), velocity.z()));
    }

    private static void carry(InterpolationHandler interpolation, Vec3 delta) {
        PositionAndRotation before = interpolation.target();
        interpolation.applyPredictedMovement(delta);
        PositionAndRotation after = interpolation.target();
        if (before != null && after != null && after.position().equals(before.position())) {
            interpolation.cancel();
        }
    }

    private static Vec3 point(OpticTransform toward, Vec3 point) {
        Vec3d mapped = toward.point(new Vec3d(point.x, point.y, point.z));
        return new Vec3(mapped.x(), mapped.y(), mapped.z());
    }
}
