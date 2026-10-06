package art.arcane.optics.entity;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class EntityDeltaCodec {
    private EntityDeltaCodec() {
    }

    public static int computeMask(EntitySnapshot current, EntitySnapshot previous) {
        int mask = 0;
        if (positionChanged(current, previous)) {
            mask |= EntitySnapshot.FIELD_POSITION;
        }
        if (EntitySnapshot.quantizeAngle(current.yaw()) != EntitySnapshot.quantizeAngle(previous.yaw())
            || EntitySnapshot.quantizeAngle(current.pitch()) != EntitySnapshot.quantizeAngle(previous.pitch())) {
            mask |= EntitySnapshot.FIELD_YAW_PITCH;
        }
        if (velocityChanged(current, previous)) {
            mask |= EntitySnapshot.FIELD_VELOCITY;
        }
        if (lookVecChanged(current, previous)) {
            mask |= EntitySnapshot.FIELD_LOOK_VEC;
        }
        if (!Arrays.equals(safe(current.equipment()), safe(previous.equipment()))) {
            mask |= EntitySnapshot.FIELD_EQUIPMENT;
        }
        if (!Arrays.equals(safe(current.metadata()), safe(previous.metadata()))) {
            mask |= EntitySnapshot.FIELD_METADATA;
        }
        if (!Arrays.equals(safe(current.mapData()), safe(previous.mapData()))) {
            mask |= EntitySnapshot.FIELD_MAP_DATA;
        }
        if (!Objects.equals(current.passengerOf(), previous.passengerOf())) {
            mask |= EntitySnapshot.FIELD_PASSENGER;
        }
        if (!Objects.equals(current.leashHolder(), previous.leashHolder())) {
            mask |= EntitySnapshot.FIELD_LEASH;
        }
        if (current.onGround() != previous.onGround()) {
            mask |= EntitySnapshot.FIELD_ON_GROUND;
        }
        if (current.height() != previous.height()) {
            mask |= EntitySnapshot.FIELD_HEIGHT;
        }
        if (profileChanged(current, previous)) {
            mask |= EntitySnapshot.FIELD_PROFILE;
        }
        return mask;
    }

    public static EntitySnapshot buildDelta(EntitySnapshot current, EntitySnapshot previous, int sequence, int mask) {
        Objects.requireNonNull(current, "current");
        if (previous == null) {
            return EntitySnapshot.full(
                current.id(),
                current.typeKey(),
                current.x(), current.y(), current.z(),
                current.height(),
                current.lookX(), current.lookY(), current.lookZ(),
                current.yaw(), current.pitch(),
                current.velocityX(), current.velocityY(), current.velocityZ(),
                current.onGround(),
                current.playerName(),
                current.textureValue(),
                current.textureSignature(),
                current.passengerOf(),
                current.leashHolder(),
                current.metadata(),
                current.equipment(),
                current.mapData(),
                sequence
            );
        }
        return new EntitySnapshot(
            EntitySnapshot.MODE_DELTA,
            sequence,
            mask,
            current.id(),
            "",
            current.x(), current.y(), current.z(),
            current.height(),
            current.lookX(), current.lookY(), current.lookZ(),
            current.yaw(), current.pitch(),
            current.velocityX(), current.velocityY(), current.velocityZ(),
            current.onGround(),
            current.playerName() == null ? "" : current.playerName(),
            current.textureValue() == null ? "" : current.textureValue(),
            current.textureSignature() == null ? "" : current.textureSignature(),
            current.passengerOf(),
            current.leashHolder(),
            current.metadata() == null ? EntitySnapshot.EMPTY : current.metadata(),
            current.equipment() == null ? EntitySnapshot.EMPTY : current.equipment(),
            current.mapData() == null ? EntitySnapshot.EMPTY : current.mapData()
        );
    }

    public static EntitySnapshot applyDelta(EntitySnapshot incoming, EntitySnapshot lastKnown) {
        Objects.requireNonNull(incoming, "incoming");
        if (incoming.isFull()) {
            return new EntitySnapshot(
                EntitySnapshot.MODE_FULL,
                incoming.sequence(),
                EntitySnapshot.FIELD_ALL_FULL,
                incoming.id(),
                incoming.typeKey(),
                incoming.x(), incoming.y(), incoming.z(),
                incoming.height(),
                incoming.lookX(), incoming.lookY(), incoming.lookZ(),
                incoming.yaw(), incoming.pitch(),
                incoming.velocityX(), incoming.velocityY(), incoming.velocityZ(),
                incoming.onGround(),
                incoming.playerName(),
                incoming.textureValue(),
                incoming.textureSignature(),
                incoming.passengerOf(),
                incoming.leashHolder(),
                incoming.metadata(),
                incoming.equipment(),
                incoming.mapData()
            );
        }
        if (lastKnown == null) {
            return incoming;
        }
        int mask = incoming.presentMask();
        UUID passengerOf = (mask & EntitySnapshot.FIELD_PASSENGER) != 0 ? incoming.passengerOf() : lastKnown.passengerOf();
        UUID leashHolder = (mask & EntitySnapshot.FIELD_LEASH) != 0 ? incoming.leashHolder() : lastKnown.leashHolder();
        double x = (mask & EntitySnapshot.FIELD_POSITION) != 0 ? incoming.x() : lastKnown.x();
        double y = (mask & EntitySnapshot.FIELD_POSITION) != 0 ? incoming.y() : lastKnown.y();
        double z = (mask & EntitySnapshot.FIELD_POSITION) != 0 ? incoming.z() : lastKnown.z();
        double height = (mask & EntitySnapshot.FIELD_HEIGHT) != 0 ? incoming.height() : lastKnown.height();
        double lookX = (mask & EntitySnapshot.FIELD_LOOK_VEC) != 0 ? incoming.lookX() : lastKnown.lookX();
        double lookY = (mask & EntitySnapshot.FIELD_LOOK_VEC) != 0 ? incoming.lookY() : lastKnown.lookY();
        double lookZ = (mask & EntitySnapshot.FIELD_LOOK_VEC) != 0 ? incoming.lookZ() : lastKnown.lookZ();
        float yaw = (mask & EntitySnapshot.FIELD_YAW_PITCH) != 0 ? incoming.yaw() : lastKnown.yaw();
        float pitch = (mask & EntitySnapshot.FIELD_YAW_PITCH) != 0 ? incoming.pitch() : lastKnown.pitch();
        double velocityX = (mask & EntitySnapshot.FIELD_VELOCITY) != 0 ? incoming.velocityX() : lastKnown.velocityX();
        double velocityY = (mask & EntitySnapshot.FIELD_VELOCITY) != 0 ? incoming.velocityY() : lastKnown.velocityY();
        double velocityZ = (mask & EntitySnapshot.FIELD_VELOCITY) != 0 ? incoming.velocityZ() : lastKnown.velocityZ();
        boolean onGround = (mask & EntitySnapshot.FIELD_ON_GROUND) != 0 ? incoming.onGround() : lastKnown.onGround();
        String playerName = (mask & EntitySnapshot.FIELD_PROFILE) != 0 ? incoming.playerName() : lastKnown.playerName();
        String textureValue = (mask & EntitySnapshot.FIELD_PROFILE) != 0 ? incoming.textureValue() : lastKnown.textureValue();
        String textureSignature = (mask & EntitySnapshot.FIELD_PROFILE) != 0 ? incoming.textureSignature() : lastKnown.textureSignature();
        byte[] metadata = (mask & EntitySnapshot.FIELD_METADATA) != 0 ? incoming.metadata() : lastKnown.metadata();
        byte[] equipment = (mask & EntitySnapshot.FIELD_EQUIPMENT) != 0 ? incoming.equipment() : lastKnown.equipment();
        byte[] mapData = (mask & EntitySnapshot.FIELD_MAP_DATA) != 0 ? incoming.mapData() : lastKnown.mapData();
        return new EntitySnapshot(
            EntitySnapshot.MODE_FULL,
            incoming.sequence(),
            EntitySnapshot.FIELD_ALL_FULL,
            incoming.id(),
            lastKnown.typeKey(),
            x, y, z,
            height,
            lookX, lookY, lookZ,
            yaw, pitch,
            velocityX, velocityY, velocityZ,
            onGround,
            playerName,
            textureValue,
            textureSignature,
            passengerOf,
            leashHolder,
            metadata,
            equipment,
            mapData
        );
    }

    private static boolean positionChanged(EntitySnapshot a, EntitySnapshot b) {
        return EntitySnapshot.quantize(a.x()) != EntitySnapshot.quantize(b.x())
            || EntitySnapshot.quantize(a.y()) != EntitySnapshot.quantize(b.y())
            || EntitySnapshot.quantize(a.z()) != EntitySnapshot.quantize(b.z());
    }

    private static boolean velocityChanged(EntitySnapshot current, EntitySnapshot previous) {
        return EntitySnapshot.quantizeVelocity(current.velocityX()) != EntitySnapshot.quantizeVelocity(previous.velocityX())
            || EntitySnapshot.quantizeVelocity(current.velocityY()) != EntitySnapshot.quantizeVelocity(previous.velocityY())
            || EntitySnapshot.quantizeVelocity(current.velocityZ()) != EntitySnapshot.quantizeVelocity(previous.velocityZ());
    }

    private static boolean lookVecChanged(EntitySnapshot current, EntitySnapshot previous) {
        return EntitySnapshot.quantizeUnit(current.lookX()) != EntitySnapshot.quantizeUnit(previous.lookX())
            || EntitySnapshot.quantizeUnit(current.lookY()) != EntitySnapshot.quantizeUnit(previous.lookY())
            || EntitySnapshot.quantizeUnit(current.lookZ()) != EntitySnapshot.quantizeUnit(previous.lookZ());
    }

    private static boolean profileChanged(EntitySnapshot current, EntitySnapshot previous) {
        return !Objects.equals(current.playerName(), previous.playerName())
            || !Objects.equals(current.textureValue(), previous.textureValue())
            || !Objects.equals(current.textureSignature(), previous.textureSignature());
    }

    private static byte[] safe(byte[] data) {
        return data == null ? EntitySnapshot.EMPTY : data;
    }
}
