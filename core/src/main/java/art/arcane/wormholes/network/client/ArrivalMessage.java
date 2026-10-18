package art.arcane.wormholes.network.client;

import java.util.Objects;
import java.util.UUID;

public sealed interface ArrivalMessage {
    int PREPARE = 66;
    int CHUNK = 67;
    int READY = 68;
    int COMMIT = 69;
    int CANCEL = 70;
    int FRAGMENT_BYTES = 24 * 1024;
    int MAX_CHUNK_BYTES = 2 * 1024 * 1024;
    int MAX_RADIUS = 3;
    int MAX_LIFETIME_TICKS = 200;

    UUID token();

    long revision();

    int id();

    private static void identity(UUID token, long revision) {
        Objects.requireNonNull(token, "token");
        if (revision <= 0) {
            throw new IllegalArgumentException("Arrival revision must be positive");
        }
    }

    record Prepare(UUID token, long revision, UUID worldId, TravelMessage.TravelWorld world,
                   TravelMessage.TravelPose arrival, int radius, String registryFingerprint, long gameTime, long clockTime,
                   float rain, float thunder, int lifetimeTicks) implements ArrivalMessage {
        public Prepare {
            identity(token, revision);
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(arrival, "arrival");
            Objects.requireNonNull(registryFingerprint, "registryFingerprint");
            if (radius < 1 || radius > MAX_RADIUS || lifetimeTicks < 1 || lifetimeTicks > MAX_LIFETIME_TICKS
                || !registryFingerprint.matches("[0-9a-f]{64}") || !Float.isFinite(rain) || !Float.isFinite(thunder)
                || rain < 0 || rain > 1 || thunder < 0 || thunder > 1) {
                throw new IllegalArgumentException("Invalid arrival preparation");
            }
        }

        public int centerX() {
            return (int) Math.floor(arrival.x()) >> 4;
        }

        public int centerZ() {
            return (int) Math.floor(arrival.z()) >> 4;
        }

        public boolean contains(int x, int z) {
            return Math.abs((long) x - centerX()) <= radius && Math.abs((long) z - centerZ()) <= radius;
        }

        public int chunkCount() {
            return (radius * 2 + 1) * (radius * 2 + 1);
        }

        public int renderRadius() {
            return radius - 1;
        }

        @Override
        public int id() {
            return PREPARE;
        }
    }

    record Chunk(UUID token, long revision, int x, int z, int fragmentIndex, int fragmentCount, int totalBytes,
                 byte[] payload) implements ArrivalMessage {
        public Chunk {
            identity(token, revision);
            Objects.requireNonNull(payload, "payload");
            if (Math.abs((long) x) > 1_875_000 || Math.abs((long) z) > 1_875_000
                || totalBytes < 1 || totalBytes > MAX_CHUNK_BYTES
                || fragmentCount != (totalBytes + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES
                || fragmentIndex < 0 || fragmentIndex >= fragmentCount
                || payload.length != Math.min(FRAGMENT_BYTES, totalBytes - fragmentIndex * FRAGMENT_BYTES)) {
                throw new IllegalArgumentException("Invalid arrival chunk fragment");
            }
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public int id() {
            return CHUNK;
        }
    }

    record Ready(UUID token, long revision, Readiness readiness) implements ArrivalMessage {
        public Ready {
            identity(token, revision);
            Objects.requireNonNull(readiness, "readiness");
        }

        @Override
        public int id() {
            return READY;
        }
    }

    record Commit(UUID token, long revision, TravelMessage.TravelPose arrival) implements ArrivalMessage {
        public Commit {
            identity(token, revision);
            Objects.requireNonNull(arrival, "arrival");
        }

        @Override
        public int id() {
            return COMMIT;
        }
    }

    record Cancel(UUID token, long revision) implements ArrivalMessage {
        public Cancel {
            identity(token, revision);
        }

        @Override
        public int id() {
            return CANCEL;
        }
    }

    enum Readiness {
        DATA, RENDER, REJECTED
    }
}
