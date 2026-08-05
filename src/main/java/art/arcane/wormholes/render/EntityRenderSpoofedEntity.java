package art.arcane.wormholes.render;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.retrooper.packetevents.util.Vector3d;

import art.arcane.wormholes.network.view.ProjectedMapData;

final class EntityRenderSpoofedEntity {
    private static final AtomicInteger NEXT_FAKE_ID = new AtomicInteger(1_900_000_000);
    private static final int METADATA_REFRESH_PASSES = 10;
    private static final int MAP_REFRESH_PASSES = 10;
    private static final double MIN_POSITION_DELTA_SQUARED = 1.0E-6D;
    private static final double MAX_RELATIVE_MOVE_DELTA = 7.75D;

    final int fakeId;
    final UUID fakeUuid;
    final boolean playerEntry;
    final int labelFakeId;
    final UUID labelFakeUuid;
    final boolean upsideDown;
    final boolean living;
    int leashedToFakeId = Integer.MIN_VALUE;
    int[] lastPassengers;
    int remoteStateVersion = -1;
    int metadataTransformKey = Integer.MIN_VALUE;
    private ProjectedMapData lastMapData;
    private boolean lastMapReversed;
    String lastMetadataSignature;
    String lastEquipmentSignature;
    String playerProfileName;
    String playerLabelText;
    private float yaw;
    private float pitch;
    private double velocityX;
    private double velocityY;
    private double velocityZ;
    private double x;
    private double y;
    private double z;
    private double labelX;
    private double labelY;
    private double labelZ;
    private boolean rotationKnown;
    private boolean velocityKnown;
    private boolean positionKnown;
    private boolean labelPositionKnown;
    private boolean mapPayloadFailureReported;
    private int metadataRefreshPasses;
    private int mapRefreshPasses;

    static EntityRenderSpoofedEntity create(boolean playerEntry, boolean upsideDown, boolean living) {
        return new EntityRenderSpoofedEntity(NEXT_FAKE_ID.getAndIncrement(), UUID.randomUUID(), playerEntry, upsideDown, living);
    }

    private EntityRenderSpoofedEntity(int fakeId, UUID fakeUuid, boolean playerEntry, boolean upsideDown, boolean living) {
        this.fakeId = fakeId;
        this.fakeUuid = fakeUuid;
        this.playerEntry = playerEntry;
        this.labelFakeId = playerEntry ? NEXT_FAKE_ID.getAndIncrement() : -1;
        this.labelFakeUuid = playerEntry ? UUID.randomUUID() : null;
        this.upsideDown = upsideDown;
        this.living = living;
        this.yaw = 0.0F;
        this.pitch = 0.0F;
        this.velocityX = 0.0D;
        this.velocityY = 0.0D;
        this.velocityZ = 0.0D;
        this.x = 0.0D;
        this.y = 0.0D;
        this.z = 0.0D;
        this.labelX = 0.0D;
        this.labelY = 0.0D;
        this.labelZ = 0.0D;
        this.rotationKnown = false;
        this.velocityKnown = false;
        this.positionKnown = false;
        this.labelPositionKnown = false;
        this.mapPayloadFailureReported = false;
        this.metadataRefreshPasses = METADATA_REFRESH_PASSES;
        this.mapRefreshPasses = MAP_REFRESH_PASSES;
    }

    void setPlayerIdentity(String profileName, String labelText) {
        this.playerProfileName = profileName;
        this.playerLabelText = labelText;
    }

    boolean updatePlayerLabelText(String labelText) {
        if (labelText.equals(this.playerLabelText)) {
            return false;
        }
        this.playerLabelText = labelText;
        return true;
    }

    void rememberPosition(Vector3d position) {
        x = position.getX();
        y = position.getY();
        z = position.getZ();
        positionKnown = true;
    }

    void rememberLabelPosition(Vector3d position) {
        labelX = position.getX();
        labelY = position.getY();
        labelZ = position.getZ();
        labelPositionKnown = true;
    }

    Move updateLabelPosition(Vector3d position) {
        double nextX = position.getX();
        double nextY = position.getY();
        double nextZ = position.getZ();
        if (!labelPositionKnown) {
            labelX = nextX;
            labelY = nextY;
            labelZ = nextZ;
            labelPositionKnown = true;
            return Move.teleport();
        }

        double deltaX = nextX - labelX;
        double deltaY = nextY - labelY;
        double deltaZ = nextZ - labelZ;
        double distanceSquared = (deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ);
        labelX = nextX;
        labelY = nextY;
        labelZ = nextZ;
        if (distanceSquared <= MIN_POSITION_DELTA_SQUARED) {
            return Move.none();
        }
        if (Math.abs(deltaX) > MAX_RELATIVE_MOVE_DELTA || Math.abs(deltaY) > MAX_RELATIVE_MOVE_DELTA || Math.abs(deltaZ) > MAX_RELATIVE_MOVE_DELTA) {
            return Move.teleport();
        }
        return Move.relative(deltaX, deltaY, deltaZ);
    }

    Move updatePosition(Vector3d position) {
        double nextX = position.getX();
        double nextY = position.getY();
        double nextZ = position.getZ();
        if (!positionKnown) {
            x = nextX;
            y = nextY;
            z = nextZ;
            positionKnown = true;
            return Move.teleport();
        }

        double deltaX = nextX - x;
        double deltaY = nextY - y;
        double deltaZ = nextZ - z;
        double distanceSquared = (deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ);
        x = nextX;
        y = nextY;
        z = nextZ;
        if (distanceSquared <= MIN_POSITION_DELTA_SQUARED) {
            return Move.none();
        }
        if (Math.abs(deltaX) > MAX_RELATIVE_MOVE_DELTA || Math.abs(deltaY) > MAX_RELATIVE_MOVE_DELTA || Math.abs(deltaZ) > MAX_RELATIVE_MOVE_DELTA) {
            return Move.teleport();
        }
        return Move.relative(deltaX, deltaY, deltaZ);
    }

    boolean updateRotation(float yaw, float pitch) {
        if (rotationKnown && angleDelta(yaw, this.yaw) < 0.5F && Math.abs(pitch - this.pitch) < 0.5F) {
            return false;
        }
        this.yaw = yaw;
        this.pitch = pitch;
        rotationKnown = true;
        return true;
    }

    boolean updateVelocity(Vector3d velocity) {
        double x = velocity.getX();
        double y = velocity.getY();
        double z = velocity.getZ();
        if (velocityKnown && Math.abs(x - velocityX) < 0.001D && Math.abs(y - velocityY) < 0.001D && Math.abs(z - velocityZ) < 0.001D) {
            return false;
        }
        velocityX = x;
        velocityY = y;
        velocityZ = z;
        velocityKnown = true;
        return true;
    }

    boolean updateMetadataTransform(int transformKey) {
        if (metadataTransformKey == transformKey) {
            return false;
        }
        metadataTransformKey = transformKey;
        return true;
    }

    boolean updateMapData(ProjectedMapData mapData, boolean reversed) {
        if (reversed == lastMapReversed && mapData.equals(lastMapData)) {
            return false;
        }
        lastMapData = mapData;
        lastMapReversed = reversed;
        return true;
    }

    boolean markMapPayloadFailureReported() {
        if (mapPayloadFailureReported) {
            return false;
        }
        mapPayloadFailureReported = true;
        return true;
    }

    boolean shouldRefreshMetadata() {
        metadataRefreshPasses--;
        return metadataRefreshPasses <= 0;
    }

    void resetMetadataCooldown() {
        metadataRefreshPasses = METADATA_REFRESH_PASSES;
    }

    boolean shouldRefreshMap() {
        mapRefreshPasses--;
        return mapRefreshPasses <= 0;
    }

    void resetMapCooldown() {
        mapRefreshPasses = MAP_REFRESH_PASSES;
    }

    private static float angleDelta(float a, float b) {
        float delta = (a - b) % 360.0F;
        if (delta >= 180.0F) {
            delta -= 360.0F;
        }
        if (delta < -180.0F) {
            delta += 360.0F;
        }
        return Math.abs(delta);
    }

    static final class Move {
        private static final Move NONE = new Move(false, false, 0.0D, 0.0D, 0.0D);
        private static final Move TELEPORT = new Move(true, false, 0.0D, 0.0D, 0.0D);

        final boolean moved;
        final boolean relative;
        final double deltaX;
        final double deltaY;
        final double deltaZ;

        private Move(boolean moved, boolean relative, double deltaX, double deltaY, double deltaZ) {
            this.moved = moved;
            this.relative = relative;
            this.deltaX = deltaX;
            this.deltaY = deltaY;
            this.deltaZ = deltaZ;
        }

        private static Move none() {
            return NONE;
        }

        private static Move teleport() {
            return TELEPORT;
        }

        private static Move relative(double deltaX, double deltaY, double deltaZ) {
            return new Move(true, true, deltaX, deltaY, deltaZ);
        }
    }
}
