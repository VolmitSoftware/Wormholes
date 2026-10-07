package art.arcane.optics.entity;

import java.util.UUID;
import java.util.function.IntSupplier;

public final class SpoofedEntity {
    private static final int METADATA_REFRESH_PASSES = 10;
    private static final int MAP_REFRESH_PASSES = 10;
    private static final double MIN_POSITION_DELTA_SQUARED = 1.0E-6D;
    private static final double MAX_RELATIVE_MOVE_DELTA = 7.75D;

    private final int fakeId;
    private final UUID fakeUuid;
    private final boolean playerEntry;
    private final int labelFakeId;
    private final UUID labelFakeUuid;
    private final boolean upsideDown;
    private final boolean living;
    private int leashedToFakeId = Integer.MIN_VALUE;
    private int[] lastPassengers;
    private int remoteStateVersion = -1;
    private int metadataTransformKey = Integer.MIN_VALUE;
    private MapSnapshot lastMapData;
    private boolean lastMapReversed;
    private String lastMetadataSignature;
    private String lastEquipmentSignature;
    private byte[] lastMetadataPayload;
    private byte[] lastEquipmentPayload;
    private EntityProfile playerProfile;
    private long playerProfileCheckedAtNanos;
    private String playerProfileName;
    private String playerLabelText;
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

    public static SpoofedEntity create(IntSupplier entityIds, boolean playerEntry, boolean upsideDown, boolean living) {
        return new SpoofedEntity(entityIds, UUID.randomUUID(), playerEntry, upsideDown, living);
    }

    private SpoofedEntity(IntSupplier entityIds, UUID fakeUuid, boolean playerEntry, boolean upsideDown, boolean living) {
        this.fakeId = entityIds.getAsInt();
        this.fakeUuid = fakeUuid;
        this.playerEntry = playerEntry;
        this.labelFakeId = playerEntry ? entityIds.getAsInt() : -1;
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

    public int fakeId() {
        return fakeId;
    }

    public UUID fakeUuid() {
        return fakeUuid;
    }

    public boolean playerEntry() {
        return playerEntry;
    }

    public int labelFakeId() {
        return labelFakeId;
    }

    public UUID labelFakeUuid() {
        return labelFakeUuid;
    }

    public boolean upsideDown() {
        return upsideDown;
    }

    public boolean living() {
        return living;
    }

    public int leashedToFakeId() {
        return leashedToFakeId;
    }

    public void setLeashedToFakeId(int leashedToFakeId) {
        this.leashedToFakeId = leashedToFakeId;
    }

    public int[] lastPassengers() {
        return lastPassengers;
    }

    public void setLastPassengers(int[] lastPassengers) {
        this.lastPassengers = lastPassengers;
    }

    public int remoteStateVersion() {
        return remoteStateVersion;
    }

    public void setRemoteStateVersion(int remoteStateVersion) {
        this.remoteStateVersion = remoteStateVersion;
    }

    public String lastMetadataSignature() {
        return lastMetadataSignature;
    }

    public void setLastMetadataSignature(String lastMetadataSignature) {
        this.lastMetadataSignature = lastMetadataSignature;
    }

    public String lastEquipmentSignature() {
        return lastEquipmentSignature;
    }

    public void setLastEquipmentSignature(String lastEquipmentSignature) {
        this.lastEquipmentSignature = lastEquipmentSignature;
    }

    public byte[] lastMetadataPayload() {
        return lastMetadataPayload;
    }

    public void setLastMetadataPayload(byte[] lastMetadataPayload) {
        this.lastMetadataPayload = lastMetadataPayload;
    }

    public byte[] lastEquipmentPayload() {
        return lastEquipmentPayload;
    }

    public void setLastEquipmentPayload(byte[] lastEquipmentPayload) {
        this.lastEquipmentPayload = lastEquipmentPayload;
    }

    public EntityProfile playerProfile() {
        return playerProfile;
    }

    public void setPlayerProfile(EntityProfile playerProfile) {
        this.playerProfile = playerProfile;
    }

    public long playerProfileCheckedAtNanos() {
        return playerProfileCheckedAtNanos;
    }

    public void setPlayerProfileCheckedAtNanos(long playerProfileCheckedAtNanos) {
        this.playerProfileCheckedAtNanos = playerProfileCheckedAtNanos;
    }

    public String playerProfileName() {
        return playerProfileName;
    }

    public String playerLabelText() {
        return playerLabelText;
    }

    public void setPlayerIdentity(String profileName, String labelText) {
        this.playerProfileName = profileName;
        this.playerLabelText = labelText;
    }

    public boolean updatePlayerLabelText(String labelText) {
        if (labelText.equals(this.playerLabelText)) {
            return false;
        }
        this.playerLabelText = labelText;
        return true;
    }

    public void rememberPosition(double nextX, double nextY, double nextZ) {
        x = nextX;
        y = nextY;
        z = nextZ;
        positionKnown = true;
    }

    public void rememberLabelPosition(double nextX, double nextY, double nextZ) {
        labelX = nextX;
        labelY = nextY;
        labelZ = nextZ;
        labelPositionKnown = true;
    }

    public Move updateLabelPosition(double nextX, double nextY, double nextZ) {
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

    public Move updatePosition(double nextX, double nextY, double nextZ) {
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

    public boolean updateRotation(float yaw, float pitch) {
        if (rotationKnown && angleDelta(yaw, this.yaw) < 0.5F && Math.abs(pitch - this.pitch) < 0.5F) {
            return false;
        }
        this.yaw = yaw;
        this.pitch = pitch;
        rotationKnown = true;
        return true;
    }

    public boolean updateVelocity(double x, double y, double z, double epsilon) {
        if (velocityKnown && Math.abs(x - velocityX) < epsilon && Math.abs(y - velocityY) < epsilon
            && Math.abs(z - velocityZ) < epsilon && !stopsMotion(x, y, z)) {
            return false;
        }
        velocityX = x;
        velocityY = y;
        velocityZ = z;
        velocityKnown = true;
        return true;
    }

    public boolean updateMetadataTransform(int transformKey) {
        if (metadataTransformKey == transformKey) {
            return false;
        }
        metadataTransformKey = transformKey;
        return true;
    }

    public boolean updateMapData(MapSnapshot mapData, boolean reversed) {
        if (reversed == lastMapReversed && mapData.equals(lastMapData)) {
            return false;
        }
        lastMapData = mapData;
        lastMapReversed = reversed;
        return true;
    }

    public boolean markMapPayloadFailureReported() {
        if (mapPayloadFailureReported) {
            return false;
        }
        mapPayloadFailureReported = true;
        return true;
    }

    public boolean shouldRefreshMetadata() {
        metadataRefreshPasses--;
        return metadataRefreshPasses <= 0;
    }

    public void resetMetadataCooldown() {
        metadataRefreshPasses = METADATA_REFRESH_PASSES;
    }

    public boolean shouldRefreshMap() {
        mapRefreshPasses--;
        return mapRefreshPasses <= 0;
    }

    public void resetMapCooldown() {
        mapRefreshPasses = MAP_REFRESH_PASSES;
    }

    private boolean stopsMotion(double x, double y, double z) {
        return x == 0.0D && y == 0.0D && z == 0.0D && (velocityX != 0.0D || velocityY != 0.0D || velocityZ != 0.0D);
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

    public static final class Move {
        private static final Move NONE = new Move(false, false, 0.0D, 0.0D, 0.0D);
        private static final Move TELEPORT = new Move(true, false, 0.0D, 0.0D, 0.0D);

        public final boolean moved;
        public final boolean relative;
        public final double deltaX;
        public final double deltaY;
        public final double deltaZ;

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
