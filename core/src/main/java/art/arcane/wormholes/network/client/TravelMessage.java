package art.arcane.wormholes.network.client;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;

public sealed interface TravelMessage {
    int TRAVEL_BEGIN = 41;
    int TRAVEL_CANCEL = 46;
    int TRAVEL_CROSS = 47;
    int REMOTE_LEVEL_OPEN = 51;
    int REMOTE_LEVEL_CLOSE = 52;
    int ROUTED_PACKET = 53;
    int TRAVEL_ACCEPT = 54;
    int REMOTE_VIEW_ACK = 55;
    int REMOTE_LEVEL_REOPEN = 56;
    int ENTITY_CROSSED = 57;
    int FIRST_ID = 41;
    int LAST_ID = 63;

    int TRAVEL_FRAGMENT_BYTES = 48 * 1024;
    int MAX_LEVEL_HANDLE = 255;
    int MAX_REMOTE_VIEW_RADIUS = 16;
    int MAX_ROUTED_PACKET_BYTES = 2 * 1024 * 1024;
    int MAX_CHUNKS_PER_TICK_HINT = 64;

    int id();

    private static boolean residentHandle(int levelHandle) {
        return levelHandle >= 1 && levelHandle <= MAX_LEVEL_HANDLE;
    }

    private static void travelVector(Vec3d vector) {
        Objects.requireNonNull(vector, "vector");
        if (!Double.isFinite(vector.x()) || !Double.isFinite(vector.y()) || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException("Travel vector");
        }
    }

    private static void travelIdentity(UUID token, long generation) {
        Objects.requireNonNull(token, "token");
        if (generation <= 0) {
            throw new IllegalArgumentException("Travel generation");
        }
    }

    record TravelWorld(String dimension, String dimensionType, long seed, boolean debug, boolean flat,
                       int seaLevel, int minY, int height) {
        public TravelWorld {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(dimensionType, "dimensionType");
            if (dimension.isEmpty() || dimensionType.isEmpty() || dimension.length() > 256 || dimensionType.length() > 256
                || height <= 0 || height > 4096 || (height & 15) != 0 || (minY & 15) != 0) {
                throw new IllegalArgumentException("Travel world");
            }
        }
    }

    record TravelPose(double x, double y, double z, float yaw, float pitch) {
        public TravelPose {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 20_000_000) {
                throw new IllegalArgumentException("Travel pose");
            }
        }
    }

    record TravelCoordinate(int x, int z) {
    }

    record ArrivalRules(OrientationRule orientation, boolean gravityFlip, MomentumRule momentum, ScaleRule scale) {
        public static final ArrivalRules FRAME = new ArrivalRules(OrientationRule.FRAME, false,
            new MomentumRule(MomentumRule.Mode.PRESERVE, 1.0D, 0.0D, new Vec3d(0.0D, 0.0D, 0.0D)), ScaleRule.OFF);

        public ArrivalRules {
            Objects.requireNonNull(orientation, "orientation");
            Objects.requireNonNull(momentum, "momentum");
            Objects.requireNonNull(scale, "scale");
            travelVector(momentum.impulse());
        }

        public static ArrivalRules of(OrientationPolicy orientation, MomentumPolicy momentum, boolean gravityFlip, double maxSpeed, ScaleRule scale) {
            MomentumRule rule = momentum.rule();
            return new ArrivalRules(orientation.rule(), gravityFlip,
                new MomentumRule(rule.mode(), rule.factor(), rule.maxSpeed() > 0.0D ? rule.maxSpeed() : maxSpeed, rule.impulse()), scale);
        }
    }

    record DoorCollisionTarget(int x, int y, int z, boolean open) {
        public DoorCollisionTarget {
            if (x < -30_000_000 || x > 30_000_000 || z < -30_000_000 || z > 30_000_000
                || y < -20_000_000 || y > 20_000_000) {
                throw new IllegalArgumentException("Door collision target");
            }
        }
    }

    record TravelBegin(UUID token, long generation, UUID sourcePortal, String sourceWorld, ApertureDescriptor sourceGeometry,
                       OpticTransform destinationToSource, float scale, TravelWorld world, TravelPose arrival, EnvironmentState environment,
                       ArrivalRules rules, boolean resident, int levelHandle, DoorCollisionTarget doorCollision)
        implements TravelMessage {
        public TravelBegin {
            travelIdentity(token, generation);
            Objects.requireNonNull(rules, "rules");
            if (!Float.isFinite(scale) || scale <= 0.0F) {
                throw new IllegalArgumentException("Travel scale " + scale);
            }
            if (resident ? !residentHandle(levelHandle) : levelHandle != 0) {
                throw new IllegalArgumentException("Travel level handle " + levelHandle);
            }
            Objects.requireNonNull(sourcePortal, "sourcePortal");
            Objects.requireNonNull(sourceWorld, "sourceWorld");
            Objects.requireNonNull(sourceGeometry, "sourceGeometry");
            Objects.requireNonNull(destinationToSource, "destinationToSource");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(arrival, "arrival");
            Objects.requireNonNull(environment, "environment");
            if (sourceWorld.isEmpty() || sourceWorld.length() > 256
                || !sourceGeometry.valid() || sourceGeometry.mirror() || sourceGeometry.parentPortalKey() != 0
                || !sourceGeometry.nested().isEmpty()
                || !world.dimension().equals(environment.world().dimensionKey())
                || !environment.transform().isIdentity()) {
                throw new IllegalArgumentException("Travel preparation");
            }
        }

        public static OpticTransform destinationToSource(Similarity sourceToDestination) {
            Vec3d anchor = sourceToDestination.point(new Vec3d(0.0D, 0.0D, 0.0D));
            return OpticTransform.of(sourceToDestination.rigid().permutation(), anchor.x(), anchor.y(), anchor.z()).inverse();
        }

        public Similarity sourceToDestination() {
            return Similarity.of(destinationToSource.inverse().normalized(), scale);
        }

        @Override
        public int id() {
            return TRAVEL_BEGIN;
        }
    }

    record TravelCross(UUID token, long generation, long contentRevision, TravelPose sourcePose,
                       Vec3d previousEye, Vec3d currentEye) implements TravelMessage {
        public TravelCross {
            travelIdentity(token, generation);
            Objects.requireNonNull(sourcePose, "sourcePose");
            travelVector(previousEye);
            travelVector(currentEye);
            if (contentRevision <= 0 || Math.abs(previousEye.x()) > 30_000_000 || Math.abs(previousEye.z()) > 30_000_000
                || Math.abs(currentEye.x()) > 30_000_000 || Math.abs(currentEye.z()) > 30_000_000
                || Math.abs(previousEye.y()) > 20_000_000 || Math.abs(currentEye.y()) > 20_000_000) {
                throw new IllegalArgumentException("Travel crossing");
            }
        }

        @Override
        public int id() {
            return TRAVEL_CROSS;
        }
    }

    record TravelCancel(UUID token, long generation) implements TravelMessage {
        public TravelCancel {
            travelIdentity(token, generation);
        }

        @Override
        public int id() {
            return TRAVEL_CANCEL;
        }
    }

    record RemoteLevelOpen(int levelHandle, TravelWorld world, EnvironmentState environment, int viewRadius,
                           TravelCoordinate center) implements TravelMessage {
        public RemoteLevelOpen {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(center, "center");
            if (!residentHandle(levelHandle) || viewRadius < 1 || viewRadius > MAX_REMOTE_VIEW_RADIUS
                || !world.dimension().equals(environment.world().dimensionKey()) || !environment.transform().isIdentity()) {
                throw new IllegalArgumentException("Remote level");
            }
        }

        @Override
        public int id() {
            return REMOTE_LEVEL_OPEN;
        }
    }

    record RemoteLevelClose(int levelHandle) implements TravelMessage {
        public RemoteLevelClose {
            if (!residentHandle(levelHandle)) {
                throw new IllegalArgumentException("Remote level handle " + levelHandle);
            }
        }

        @Override
        public int id() {
            return REMOTE_LEVEL_CLOSE;
        }
    }

    record RoutedPacket(int levelHandle, int sequence, int fragmentIndex, int fragmentCount, int totalBytes,
                        byte[] payload) implements TravelMessage {
        public RoutedPacket {
            Objects.requireNonNull(payload, "payload");
            if (!residentHandle(levelHandle) || sequence < 0 || totalBytes <= 0 || totalBytes > MAX_ROUTED_PACKET_BYTES
                || fragmentCount != (totalBytes + TRAVEL_FRAGMENT_BYTES - 1) / TRAVEL_FRAGMENT_BYTES
                || fragmentIndex < 0 || fragmentIndex >= fragmentCount
                || payload.length != Math.min(TRAVEL_FRAGMENT_BYTES, totalBytes - fragmentIndex * TRAVEL_FRAGMENT_BYTES)) {
                throw new IllegalArgumentException("Routed packet fragment");
            }
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RoutedPacket that && levelHandle == that.levelHandle && sequence == that.sequence
                && fragmentIndex == that.fragmentIndex && fragmentCount == that.fragmentCount && totalBytes == that.totalBytes
                && Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(levelHandle, sequence, fragmentIndex, fragmentCount, totalBytes) * 31 + Arrays.hashCode(payload);
        }

        @Override
        public int id() {
            return ROUTED_PACKET;
        }
    }

    record TravelAccept(UUID token, long generation, long contentRevision, TravelPose pose, Vec3d velocity, int levelHandle,
                        boolean dimensionChanged, long serverTick) implements TravelMessage {
        public TravelAccept {
            travelIdentity(token, generation);
            Objects.requireNonNull(pose, "pose");
            travelVector(velocity);
            if (contentRevision <= 0 || levelHandle < 0 || levelHandle > MAX_LEVEL_HANDLE || serverTick < 0) {
                throw new IllegalArgumentException("Travel accept");
            }
        }

        @Override
        public int id() {
            return TRAVEL_ACCEPT;
        }
    }

    record RemoteViewAck(int levelHandle, int lastSequence, int chunksPerTickHint) implements TravelMessage {
        public RemoteViewAck {
            if (!residentHandle(levelHandle) || lastSequence < 0 || chunksPerTickHint < 1 || chunksPerTickHint > MAX_CHUNKS_PER_TICK_HINT) {
                throw new IllegalArgumentException("Remote view acknowledgement");
            }
        }

        @Override
        public int id() {
            return REMOTE_VIEW_ACK;
        }
    }

    record RemoteLevelReopen(int levelHandle) implements TravelMessage {
        public RemoteLevelReopen {
            if (!residentHandle(levelHandle)) {
                throw new IllegalArgumentException("Remote level handle " + levelHandle);
            }
        }

        @Override
        public int id() {
            return REMOTE_LEVEL_REOPEN;
        }
    }

    record EntityCrossed(int levelHandle, int entityId, OpticTransform toward, Vec3d planeOrigin, Face planeNormal, Vec3d velocity)
        implements TravelMessage {
        public EntityCrossed {
            Objects.requireNonNull(toward, "toward");
            Objects.requireNonNull(planeNormal, "planeNormal");
            travelVector(planeOrigin);
            travelVector(velocity);
            if (levelHandle < 0 || levelHandle > MAX_LEVEL_HANDLE) {
                throw new IllegalArgumentException("Entity crossing level handle " + levelHandle);
            }
        }

        @Override
        public int id() {
            return ENTITY_CROSSED;
        }
    }
}
