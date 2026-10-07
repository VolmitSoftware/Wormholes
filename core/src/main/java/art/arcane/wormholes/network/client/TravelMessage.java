package art.arcane.wormholes.network.client;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ProjectionEnvironment;

public sealed interface TravelMessage {
    int TRAVEL_BEGIN = 41;
    int TRAVEL_CHUNK = 42;
    int TRAVEL_END = 43;
    int TRAVEL_READY = 44;
    int TRAVEL_COMMIT = 45;
    int TRAVEL_CANCEL = 46;
    int TRAVEL_CROSS = 47;
    int TRAVEL_REUSE = 48;
    int TRAVEL_CACHED = 49;
    int FIRST_ID = TRAVEL_BEGIN;
    int LAST_ID = TRAVEL_CACHED;

    int TRAVEL_HASH_BYTES = 32;
    int TRAVEL_REUSE_BYTES = 74;
    int MAX_TRAVEL_REUSE_PROBES_PER_TICK = 8;
    int MAX_TRAVEL_CHUNKS = 1089;
    int MAX_TRAVEL_CHUNK_BYTES = 2 * 1024 * 1024;
    int MAX_TRAVEL_BYTES = 64 * 1024 * 1024;
    int TRAVEL_FRAGMENT_BYTES = 48 * 1024;
    int MAX_TRAVEL_EXPIRY_MILLIS = 300_000;

    int id();

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

    record TravelChunkRevision(int x, int z, int revision) {
        public TravelChunkRevision {
            if (revision <= 0) {
                throw new IllegalArgumentException("Travel chunk revision");
            }
        }
    }

    record TravelBegin(UUID token, long generation, UUID sourcePortal, String sourceWorld, ApertureDescriptor sourceGeometry,
                       OpticTransform destinationToSource, TravelWorld world, TravelPose arrival, List<TravelCoordinate> chunks, ProjectionEnvironment environment,
                       int expiresMillis) implements TravelMessage {
        public TravelBegin {
            travelIdentity(token, generation);
            Objects.requireNonNull(sourcePortal, "sourcePortal");
            Objects.requireNonNull(sourceWorld, "sourceWorld");
            Objects.requireNonNull(sourceGeometry, "sourceGeometry");
            Objects.requireNonNull(destinationToSource, "destinationToSource");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(arrival, "arrival");
            Objects.requireNonNull(environment, "environment");
            chunks = List.copyOf(chunks);
            if (sourceWorld.isEmpty() || sourceWorld.length() > 256
                || !sourceGeometry.valid() || sourceGeometry.mirror() || sourceGeometry.parentPortalKey() != 0
                || !sourceGeometry.nested().isEmpty()
                || chunks.isEmpty() || chunks.size() > MAX_TRAVEL_CHUNKS
                || new HashSet<>(chunks).size() != chunks.size()
                || expiresMillis <= 0 || expiresMillis > MAX_TRAVEL_EXPIRY_MILLIS
                || !world.dimension().equals(environment.world().dimensionKey())
                || !environment.transform().isIdentity()) {
                throw new IllegalArgumentException("Travel preparation");
            }
        }

        @Override
        public int id() {
            return TRAVEL_BEGIN;
        }
    }

    record TravelChunk(UUID token, long generation, int chunkX, int chunkZ, int revision, int fragmentIndex,
                       int fragmentCount, int totalBytes, byte[] payload) implements TravelMessage {
        public TravelChunk {
            travelIdentity(token, generation);
            Objects.requireNonNull(payload, "payload");
            if (revision <= 0 || totalBytes <= 0 || totalBytes > MAX_TRAVEL_CHUNK_BYTES
                || fragmentCount != (totalBytes + TRAVEL_FRAGMENT_BYTES - 1) / TRAVEL_FRAGMENT_BYTES
                || fragmentIndex < 0 || fragmentIndex >= fragmentCount
                || payload.length != Math.min(TRAVEL_FRAGMENT_BYTES, totalBytes - fragmentIndex * TRAVEL_FRAGMENT_BYTES)) {
                throw new IllegalArgumentException("Travel chunk fragment");
            }
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TravelChunk that && token.equals(that.token) && generation == that.generation
                && chunkX == that.chunkX && chunkZ == that.chunkZ && revision == that.revision
                && fragmentIndex == that.fragmentIndex && fragmentCount == that.fragmentCount && totalBytes == that.totalBytes
                && Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(token, generation, chunkX, chunkZ, revision, fragmentIndex, fragmentCount, totalBytes) * 31
                + Arrays.hashCode(payload);
        }

        @Override
        public int id() {
            return TRAVEL_CHUNK;
        }
    }

    record TravelReuse(UUID token, long generation, int chunkX, int chunkZ, int revision, byte[] hash) implements TravelMessage {
        public TravelReuse {
            travelIdentity(token, generation);
            Objects.requireNonNull(hash, "hash");
            if (revision <= 0 || hash.length != TRAVEL_HASH_BYTES) {
                throw new IllegalArgumentException("Travel cache proof");
            }
            hash = hash.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TravelReuse that && token.equals(that.token) && generation == that.generation
                && chunkX == that.chunkX && chunkZ == that.chunkZ && revision == that.revision
                && Arrays.equals(hash, that.hash);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(token, generation, chunkX, chunkZ, revision);
            return 31 * result + Arrays.hashCode(hash);
        }

        @Override
        public int id() {
            return TRAVEL_REUSE;
        }
    }

    record TravelCached(UUID token, long generation, int chunkX, int chunkZ, int revision, byte[] hash, boolean available) implements TravelMessage {
        public TravelCached {
            travelIdentity(token, generation);
            Objects.requireNonNull(hash, "hash");
            if (revision <= 0 || hash.length != TRAVEL_HASH_BYTES) {
                throw new IllegalArgumentException("Travel cache proof");
            }
            hash = hash.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TravelCached that && token.equals(that.token) && generation == that.generation
                && chunkX == that.chunkX && chunkZ == that.chunkZ && revision == that.revision && available == that.available
                && Arrays.equals(hash, that.hash);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(token, generation, chunkX, chunkZ, revision);
            result = 31 * result + Boolean.hashCode(available);
            return 31 * result + Arrays.hashCode(hash);
        }

        @Override
        public int id() {
            return TRAVEL_CACHED;
        }
    }

    record TravelEnd(UUID token, long generation, long contentRevision, List<TravelChunkRevision> chunks) implements TravelMessage {
        public TravelEnd {
            travelIdentity(token, generation);
            chunks = List.copyOf(chunks);
            if (contentRevision <= 0 || chunks.isEmpty() || chunks.size() > MAX_TRAVEL_CHUNKS) {
                throw new IllegalArgumentException("Travel manifest");
            }
            HashSet<TravelCoordinate> coordinates = new HashSet<>();
            for (TravelChunkRevision chunk : chunks) {
                if (!coordinates.add(new TravelCoordinate(chunk.x(), chunk.z()))) {
                    throw new IllegalArgumentException("Repeated travel chunk");
                }
            }
        }

        @Override
        public int id() {
            return TRAVEL_END;
        }
    }

    record TravelReady(UUID token, long generation, long contentRevision) implements TravelMessage {
        public TravelReady {
            travelIdentity(token, generation);
            if (contentRevision <= 0) {
                throw new IllegalArgumentException("Travel ready revision");
            }
        }

        @Override
        public int id() {
            return TRAVEL_READY;
        }
    }

    record TravelCommit(UUID token, long generation, long contentRevision, String sourceWorld, String destinationWorld,
                        TravelPose arrival, Vec3d velocity) implements TravelMessage {
        public TravelCommit {
            travelIdentity(token, generation);
            travelVector(velocity);
            Objects.requireNonNull(sourceWorld, "sourceWorld");
            Objects.requireNonNull(destinationWorld, "destinationWorld");
            Objects.requireNonNull(arrival, "arrival");
            if (contentRevision <= 0 || sourceWorld.isEmpty() || destinationWorld.isEmpty()
                || sourceWorld.length() > 256 || destinationWorld.length() > 256) {
                throw new IllegalArgumentException("Travel commit");
            }
        }

        @Override
        public int id() {
            return TRAVEL_COMMIT;
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
}
