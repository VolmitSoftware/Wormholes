package art.arcane.wormholes.modded.client;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.PoseTransform;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.mixin.client.EntityCrossingLerpAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class ClientEntityCrossings {
    private final List<Entity> pending = new ArrayList<>();

    boolean receive(ClientLevel level, Entity self, TravelMessage.EntityCrossed crossed) {
        Entity entity = level == null ? null : level.getEntity(crossed.entityId());
        if (entity == null || entity == self || entity.isControlledByLocalInstance()) {
            return false;
        }
        CrossingEntity holder = (CrossingEntity) entity;
        ClientEntityCrossing previous = holder.wormholes$crossing();
        if (previous != null) {
            previous.resolve(entity);
        }
        ClientEntityCrossing crossing = new ClientEntityCrossing(crossed, ClientEntityCrossing.echoed(entity));
        crossing.prepare(entity);
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

    static void cross(Entity entity, TravelMessage.EntityCrossed crossing) {
        OpticTransform toward = crossing.toward();
        Vec3 position = point(toward, entity.position());
        Vec3 old = point(toward, new Vec3(entity.xOld, entity.yOld, entity.zOld));
        Pose pose = PoseTransform.orient(pose(entity), ClientEntityCrossing.localRotation(crossing));
        if (entity instanceof EntityCrossingLerpAccess interpolation) {
            carry(interpolation, toward.isTranslation() ? position.subtract(entity.position()) : null);
        }
        MinecraftGravity.set(entity, crossing.destinationGravity());
        entity.setYRot(pose.yaw());
        entity.setXRot(pose.pitch());
        entity.setYHeadRot(pose.headYaw());
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = pose.bodyYaw();
            living.yBodyRotO = pose.previousBodyYaw();
            living.yHeadRotO = pose.previousHeadYaw();
        }
        entity.setPos(position);
        entity.xOld = old.x;
        entity.yOld = old.y;
        entity.zOld = old.z;
        entity.xo = old.x;
        entity.yo = old.y;
        entity.zo = old.z;
        entity.yRotO = pose.previousYaw();
        entity.xRotO = pose.previousPitch();
        Vec3d velocity = crossing.velocity();
        entity.setDeltaMovement(new Vec3(velocity.x(), velocity.y(), velocity.z()));
    }

    private static Pose pose(Entity entity) {
        Vec3 position = entity.position();
        Vec3 old = new Vec3(entity.xOld, entity.yOld, entity.zOld);
        Vec3 velocity = entity.getDeltaMovement();
        float bodyYaw = entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot();
        float previousBodyYaw = entity instanceof LivingEntity living ? living.yBodyRotO : entity.yRotO;
        float previousHeadYaw = entity instanceof LivingEntity living ? living.yHeadRotO : entity.yRotO;
        return new Pose(new Vec3d(position.x, position.y, position.z), new Vec3d(entity.xo, entity.yo, entity.zo),
            new Vec3d(old.x, old.y, old.z), new Vec3d(velocity.x, velocity.y, velocity.z), entity.getYRot(), entity.getXRot(),
            entity.yRotO, entity.xRotO, bodyYaw, previousBodyYaw, entity.getYHeadRot(), previousHeadYaw);
    }

    private static void carry(EntityCrossingLerpAccess interpolation, Vec3 delta) {
        if (delta == null || delta.lengthSqr() == 0.0D || interpolation.wormholes$lerpSteps() <= 0) {
            interpolation.wormholes$lerpSteps(0);
            return;
        }
        interpolation.wormholes$lerpX(interpolation.wormholes$lerpX() + delta.x);
        interpolation.wormholes$lerpY(interpolation.wormholes$lerpY() + delta.y);
        interpolation.wormholes$lerpZ(interpolation.wormholes$lerpZ() + delta.z);
    }

    private static Vec3 point(OpticTransform toward, Vec3 point) {
        Vec3d mapped = toward.point(new Vec3d(point.x, point.y, point.z));
        return new Vec3(mapped.x(), mapped.y(), mapped.z());
    }
}
