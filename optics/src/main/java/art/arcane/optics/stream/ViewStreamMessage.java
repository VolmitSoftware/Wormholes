package art.arcane.optics.stream;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.math.BlockBox;

public sealed interface ViewStreamMessage {
    int id();

    sealed interface Projection extends ViewStreamMessage {
        ViewStreamMessageType type();

        @Override
        default int id() {
            return type().id();
        }
    }

    record Extension(int id, Object payload) implements ViewStreamMessage {
        public Extension {
            Objects.requireNonNull(payload, "payload");
            if (id < 0 || id > ViewStreamLimits.MAX_MESSAGE_ID) {
                throw new IllegalArgumentException("extension message id " + id);
            }
        }
    }

    record Offer(int wire, int mcDataVersion, long serverCaps, int maxFrameBytes, long zeroCopyNonce) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.OFFER;
        }
    }

    record Hello(int wire, int mcDataVersion, long clientCaps, int maxFrameBytes, int plateMemoryMb, long zeroCopyNonceEcho,
                 String brandTag) implements Projection {
        public Hello {
            Objects.requireNonNull(brandTag, "brandTag");
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.HELLO;
        }
    }

    record Accept(int sessionId, long caps, int tickRate, int maxFrameBytes, long hashSalt, int ackWindowFrames) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.ACCEPT;
        }
    }

    record Decline(DeclineReason reason) implements Projection {
        public Decline {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.DECLINE;
        }
    }

    record Palette(List<PaletteEntry> entries) implements Projection {
        public Palette {
            entries = List.copyOf(entries);
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.PALETTE;
        }
    }

    record PaletteEntry(int id, String state) {
        public PaletteEntry {
            Objects.requireNonNull(state, "state");
        }
    }

    record Portal(int portalKey, int geometryRevision, ApertureDescriptor geometry) implements Projection {
        public Portal {
            Objects.requireNonNull(geometry, "geometry");
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.PORTAL;
        }
    }

    record PortalDrop(int portalKey) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.PORTAL_DROP;
        }
    }

    record MeshBegin(int portalKey, int generation, BlockBox bounds, int maxResidentSections) implements Projection {
        public MeshBegin {
            Objects.requireNonNull(bounds, "bounds");
            if (bounds.cells() == 0 || bounds.sizeX() > 65535 || bounds.sizeY() > 65535 || bounds.sizeZ() > 65535) {
                throw new IllegalArgumentException("mesh bounds " + bounds);
            }
            long x = (((long) bounds.minX() + bounds.sizeX() - 1) >> 4) - (bounds.minX() >> 4) + 1;
            long y = (((long) bounds.minY() + bounds.sizeY() - 1) >> 4) - (bounds.minY() >> 4) + 1;
            long z = (((long) bounds.minZ() + bounds.sizeZ() - 1) >> 4) - (bounds.minZ() >> 4) + 1;
            if (maxResidentSections < 1 || maxResidentSections > x * y * z) {
                throw new IllegalArgumentException("resident sections " + maxResidentSections);
            }
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.MESH_BEGIN;
        }
    }

    record MeshSection(int portalKey, int generation, int sectionX, int sectionY, int sectionZ, int revision,
                       int backingState, Brick brick, SectionBiomes biomes) implements Projection {
        public MeshSection {
            Objects.requireNonNull(brick, "brick");
            Objects.requireNonNull(biomes, "biomes");
            if (brick.brickIndex() != 0) {
                throw new IllegalArgumentException("mesh section brick index " + brick.brickIndex());
            }
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.MESH_SECTION;
        }
    }

    record MeshDrop(int portalKey, int generation, int sectionX, int sectionY, int sectionZ) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.MESH_DROP;
        }
    }

    record MeshAck(int portalKey, int generation, int sectionX, int sectionY, int sectionZ, int revision) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.MESH_ACK;
        }
    }

    record MeshLocal(int portalKey, int generation, int sequence, boolean available,
                     List<MeshCoordinate> sections, List<UUID> entities) implements Projection {
        public static final int MAX_SECTIONS = 512;
        public static final int MAX_ENTITIES = 256;

        public MeshLocal {
            sections = List.copyOf(sections);
            entities = List.copyOf(entities);
            if (generation <= 0 || sequence <= 0 || sections.size() > MAX_SECTIONS || entities.size() > MAX_ENTITIES) {
                throw new IllegalArgumentException("Invalid local mesh availability");
            }
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.MESH_LOCAL;
        }
    }

    record MeshCached(int portalKey, int generation, int sequence, boolean available, List<MeshClaim> claims) implements Projection {
        public static final int MAX_CLAIMS = 512;

        public MeshCached {
            claims = List.copyOf(claims);
            if (generation <= 0 || sequence <= 0 || claims.size() > MAX_CLAIMS) {
                throw new IllegalArgumentException("mesh cache claims");
            }
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.MESH_CACHED;
        }
    }

    record MeshClaim(int x, int y, int z, long hash) {
    }

    record MeshReuse(int portalKey, int generation, int sectionX, int sectionY, int sectionZ, int revision, long hash) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.MESH_REUSE;
        }
    }

    record MeshCoordinate(int x, int y, int z) {
    }

    record PlateBegin(int portalKey, int plateRevision, PlateSectionBox sections, BlockBox cells, int backingState, int brickCount,
                      long[] brickHashes) implements Projection {
        public PlateBegin {
            Objects.requireNonNull(sections, "sections");
            Objects.requireNonNull(cells, "cells");
            if (brickCount < 0 || brickCount > ViewStreamLimits.MAX_BRICKS_PER_PLATE) {
                throw new IllegalArgumentException("brick count " + brickCount);
            }
            if (brickHashes != null && brickHashes.length != brickCount) {
                throw new IllegalArgumentException("hash manifest of " + brickHashes.length + " for " + brickCount + " bricks");
            }
        }

        public boolean hasHashes() {
            return brickHashes != null;
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.PLATE_BEGIN;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PlateBegin that)) {
                return false;
            }
            return portalKey == that.portalKey && plateRevision == that.plateRevision && sections.equals(that.sections)
                && cells.equals(that.cells) && backingState == that.backingState && brickCount == that.brickCount
                && Arrays.equals(brickHashes, that.brickHashes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(portalKey, plateRevision, sections, cells, backingState, brickCount) * 31 + Arrays.hashCode(brickHashes);
        }
    }

    record PlateBricks(int portalKey, int plateRevision, List<Brick> bricks) implements Projection {
        public PlateBricks {
            bricks = List.copyOf(bricks);
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.PLATE_BRICKS;
        }
    }

    record PlateEnd(int portalKey, int plateRevision) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.PLATE_END;
        }
    }

    record PlatePatch(int portalKey, int fromRevision, int toRevision, List<PatchOp> ops) implements Projection {
        public PlatePatch {
            ops = List.copyOf(ops);
        }

        public boolean advances() {
            return toRevision != fromRevision;
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.PLATE_PATCH;
        }
    }

    sealed interface PatchOp {
        int OP_FULL = 0;
        int OP_SPARSE = 1;
        int OP_CLEAR = 2;

        int brickIndex();

        int op();
    }

    record FullOp(Brick brick) implements PatchOp {
        public FullOp {
            Objects.requireNonNull(brick, "brick");
        }

        @Override
        public int brickIndex() {
            return brick.brickIndex();
        }

        @Override
        public int op() {
            return OP_FULL;
        }
    }

    record SparseOp(int brickIndex, int[] cellIndices, int[] paletteIds) implements PatchOp {
        public SparseOp {
            Objects.requireNonNull(cellIndices, "cellIndices");
            Objects.requireNonNull(paletteIds, "paletteIds");
            if (cellIndices.length != paletteIds.length) {
                throw new IllegalArgumentException("sparse op with mismatched arrays");
            }
            if (cellIndices.length > ViewStreamLimits.BRICK_CELLS) {
                throw new IllegalArgumentException("sparse op with " + cellIndices.length + " cells");
            }
        }

        @Override
        public int op() {
            return OP_SPARSE;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SparseOp that)) {
                return false;
            }
            return brickIndex == that.brickIndex && Arrays.equals(cellIndices, that.cellIndices) && Arrays.equals(paletteIds, that.paletteIds);
        }

        @Override
        public int hashCode() {
            return (brickIndex * 31 + Arrays.hashCode(cellIndices)) * 31 + Arrays.hashCode(paletteIds);
        }
    }

    record ClearOp(int brickIndex) implements PatchOp {
        @Override
        public int op() {
            return OP_CLEAR;
        }
    }

    record PlateHandle(int portalKey, int plateRevision, long handle) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.PLATE_HANDLE;
        }
    }

    record BrickMiss(List<Plate> plates) implements Projection {
        public BrickMiss {
            plates = List.copyOf(plates);
            if (plates.isEmpty() || plates.size() > ViewStreamLimits.MAX_BRICK_MISS_PLATES) {
                throw new IllegalArgumentException("brick miss for " + plates.size() + " plates");
            }
        }

        public static BrickMiss of(Plate plate) {
            return new BrickMiss(List.of(plate));
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.BRICK_MISS;
        }

        public record Plate(int portalKey, int plateRevision, long[] bitset) {
            public Plate {
                Objects.requireNonNull(bitset, "bitset");
                if (bitset.length > ViewStreamLimits.MAX_BRICK_MISS_WORDS) {
                    throw new IllegalArgumentException("brick miss bitset of " + bitset.length + " words");
                }
            }

            public boolean missed(int brickIndex) {
                int word = brickIndex >>> 6;
                return word < bitset.length && (bitset[word] & (1L << (brickIndex & 63))) != 0L;
            }

            public int wireBytes() {
                return ViewStreamWriter.varintSize(portalKey) + 4 + ViewStreamWriter.varintSize(bitset.length) + bitset.length * 8;
            }

            public static long[] bitsetFor(int brickCount, boolean[] missed) {
                long[] words = new long[(brickCount + 63) >>> 6];
                for (int i = 0; i < brickCount && i < missed.length; i++) {
                    if (missed[i]) {
                        words[i >>> 6] |= 1L << (i & 63);
                    }
                }
                return words;
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) {
                    return true;
                }
                if (!(other instanceof Plate that)) {
                    return false;
                }
                return portalKey == that.portalKey && plateRevision == that.plateRevision && Arrays.equals(bitset, that.bitset);
            }

            @Override
            public int hashCode() {
                return (portalKey * 31 + plateRevision) * 31 + Arrays.hashCode(bitset);
            }
        }
    }

    record PlateRefused(int portalKey, int plateRevision) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.PLATE_REFUSED;
        }
    }

    record EntityEvent(int portalKey, int eventSeq, UUID entityId, boolean hurt, int animation, float yaw) implements Projection {
        public EntityEvent {
            Objects.requireNonNull(entityId, "entityId");
            if (!Float.isFinite(yaw) || !hurt && animation != 0 && animation != 2 && animation != 3 && animation != 4 && animation != 5) {
                throw new IllegalArgumentException("entity event");
            }
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.ENTITY_EVENT;
        }
    }

    record EntitySelf(UUID projectedId) implements Projection {
        public EntitySelf {
            Objects.requireNonNull(projectedId, "projectedId");
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.ENTITY_SELF;
        }
    }

    record EntityFrame(int portalKey, int entitySeq, List<EntitySnapshot> entities, List<UUID> presentIds, boolean presence)
        implements Projection {
        public EntityFrame {
            entities = List.copyOf(entities);
            presentIds = List.copyOf(presentIds);
            if (!presence && !presentIds.isEmpty()) {
                throw new IllegalArgumentException("entity frame carries present ids without a presence update");
            }
            if (entities.size() > ViewStreamLimits.MAX_ENTITIES_PER_FRAME) {
                throw new IllegalArgumentException("entity frame with " + entities.size() + " entities");
            }
            if (presentIds.size() > ViewStreamLimits.MAX_PRESENT_IDS_PER_FRAME) {
                throw new IllegalArgumentException("entity frame with " + presentIds.size() + " present ids");
            }
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.ENTITY_FRAME;
        }
    }

    record Environment(int portalKey, EnvironmentState environment) implements Projection {
        public Environment {
            Objects.requireNonNull(environment, "environment");
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.ENVIRONMENT;
        }
    }

    record Atmosphere(int portalKey, long dayTime, float rain, float thunder, int flags) implements Projection {
        public static final int FLAG_TIME = 1;
        public static final int FLAG_WEATHER = 1 << 1;
        public static final int FLAG_RESTORE = 1 << 2;
        public static final int FLAG_SKY_DARKEN = 1 << 3;
        public static final int SKY_DARKEN_SHIFT = 4;

        public static int withSkyDarken(int flags, int skyDarken) {
            return (flags & ~(0x0F << SKY_DARKEN_SHIFT)) | FLAG_SKY_DARKEN | ((Math.max(0, Math.min(15, skyDarken)) & 0x0F) << SKY_DARKEN_SHIFT);
        }

        public int skyDarken() {
            return (flags & FLAG_SKY_DARKEN) == 0 ? 0 : (flags >>> SKY_DARKEN_SHIFT) & 0x0F;
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.ATMOSPHERE;
        }
    }

    record SessionReset(ResetReason reason) implements Projection {
        public SessionReset {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.SESSION_RESET;
        }
    }

    record Ack(int seq, int clientTick, int appliedCells) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.ACK;
        }
    }

    record ViewStats(int clientTick, int attended, int overlayCells, int unknownStates, int sweepMicrosP50, int applyMicrosP50,
                     int plateMb) implements Projection {
        @Override
        public ViewStreamMessageType type() {
            return ViewStreamMessageType.VIEW_STATS;
        }
    }

    enum DeclineReason {
        WIRE_MISMATCH,
        DATA_VERSION_MISMATCH,
        DISABLED,
        CAPACITY;

        public static DeclineReason byId(int id) {
            DeclineReason[] values = values();
            return id < 0 || id >= values.length ? null : values[id];
        }
    }

    enum ResetReason {
        TELEPORT,
        DIMENSION,
        RESPAWN,
        DISABLED,
        PROTOCOL,
        OVERLOAD;

        public static ResetReason byId(int id) {
            ResetReason[] values = values();
            return id < 0 || id >= values.length ? null : values[id];
        }
    }
}
