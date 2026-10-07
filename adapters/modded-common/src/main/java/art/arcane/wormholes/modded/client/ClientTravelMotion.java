package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.PoseTransform;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.mixin.client.ClientAvatarStateAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class ClientTravelMotion {
    private ClientTravelMotion() {
    }

    static Pose capture(LocalPlayer player) {
        return new Pose(vector(player.position()), new Vec3d(player.xo, player.yo, player.zo), vector(player.oldPosition()),
            vector(player.getDeltaMovement()), player.getYRot(), player.getXRot(), player.yRotO, player.xRotO,
            player.yBodyRot, player.yBodyRotO, player.yHeadRot, player.yHeadRotO);
    }

    static void apply(LocalPlayer player, Pose pose) {
        player.setPos(position(pose.position()));
        player.setOldPosAndRot(position(pose.oldPosition()), pose.previousYaw(), pose.previousPitch());
        player.xo = pose.previousPosition().x();
        player.yo = pose.previousPosition().y();
        player.zo = pose.previousPosition().z();
        player.setDeltaMovement(position(pose.velocity()));
        player.setYRot(pose.yaw());
        player.setXRot(pose.pitch());
        player.yBodyRot = pose.bodyYaw();
        player.yBodyRotO = pose.previousBodyYaw();
        player.yHeadRot = pose.headYaw();
        player.yHeadRotO = pose.previousHeadYaw();
    }

    static Pose toward(OpticTransform destinationToSource, Pose source) {
        return PoseTransform.apply(source, destinationToSource.inverse());
    }

    static Pose arrive(TravelMessage.TravelBegin begin, Pose source, Vec3d crossingPoint) {
        OpticTransform toward = begin.destinationToSource().inverse();
        ApertureDescriptor geometry = begin.sourceGeometry();
        boolean front = geometry.frontSide();
        Frame sourceView = geometry.frame().view(front);
        PlaneCrossing crossing = new PlaneCrossing(sourceView, crossingPoint, crossingPoint, source.velocity(),
            Angles.direction(source.yaw(), source.pitch()), front);
        TravelMessage.ArrivalRules rules = begin.rules();
        MomentumRule momentum = rules.momentum();
        return PoseTransform.arrive(PoseTransform.apply(source, toward), crossing, exitFrame(sourceView, toward, front),
            rules.orientation(), rules.gravityFlip(), momentum, momentum.maxSpeed());
    }

    static Frame exitFrame(Frame sourceView, OpticTransform toward, boolean front) {
        return new Frame(toward.face(sourceView.getNormal()), toward.face(sourceView.getRight()), toward.face(sourceView.getUp())).view(front);
    }

    static Pose reconcile(Pose current, Vec3d offset, Vec3d predictedVelocity, Vec3d authoritativeVelocity) {
        Pose moved = current.moved(offset);
        return moved.withVelocity(current.velocity().add(authoritativeVelocity.subtract(predictedVelocity)));
    }

    static Pose turned(Pose pose, float yaw, float pitch) {
        return new Pose(pose.position(), pose.previousPosition(), pose.oldPosition(), pose.velocity(), pose.yaw() + yaw,
            Mth.clamp(pose.pitch() + pitch, -90.0F, 90.0F), pose.previousYaw() + yaw, Mth.clamp(pose.previousPitch() + pitch, -90.0F, 90.0F),
            pose.bodyYaw() + yaw, pose.previousBodyYaw() + yaw, pose.headYaw() + yaw, pose.previousHeadYaw() + yaw);
    }

    static Vec3 point(OpticTransform toward, Vec3 point) {
        return position(toward.point(vector(point)));
    }

    static Angles.Look look(OpticTransform toward, float yaw, float pitch) {
        return toward.look(new Angles.Look(yaw, pitch));
    }

    static Carry carry(LocalPlayer player) {
        if (!(player.avatarState() instanceof ClientAvatarStateAccess cloak)) {
            Vec3d position = vector(player.position());
            return new Carry(player.yBob, player.xBob, player.yBobO, player.xBobO, position, position);
        }
        return new Carry(player.yBob, player.xBob, player.yBobO, player.xBobO,
            new Vec3d(cloak.wormholes$xCloak(), cloak.wormholes$yCloak(), cloak.wormholes$zCloak()),
            new Vec3d(cloak.wormholes$xCloakO(), cloak.wormholes$yCloakO(), cloak.wormholes$zCloakO()));
    }

    static Vec3d vector(Vec3 point) {
        return new Vec3d(point.x, point.y, point.z);
    }

    static Vec3 position(Vec3d point) {
        return new Vec3(point.x(), point.y(), point.z());
    }

    record Carry(float yBob, float xBob, float yBobO, float xBobO, Vec3d cloak, Vec3d previousCloak) {
        Carry moved(Pose from, Pose to, OpticTransform toward) {
            return new Carry(yBob + to.yaw() - from.yaw(), xBob + to.pitch() - from.pitch(),
                yBobO + to.previousYaw() - from.previousYaw(), xBobO + to.previousPitch() - from.previousPitch(),
                toward.point(cloak), toward.point(previousCloak));
        }

        void restore(LocalPlayer player) {
            player.yBob = yBob;
            player.xBob = xBob;
            player.yBobO = yBobO;
            player.xBobO = xBobO;
            if (!(player.avatarState() instanceof ClientAvatarStateAccess access)) {
                return;
            }
            access.wormholes$xCloak(cloak.x());
            access.wormholes$yCloak(cloak.y());
            access.wormholes$zCloak(cloak.z());
            access.wormholes$xCloakO(previousCloak.x());
            access.wormholes$yCloakO(previousCloak.y());
            access.wormholes$zCloakO(previousCloak.z());
        }
    }
}
