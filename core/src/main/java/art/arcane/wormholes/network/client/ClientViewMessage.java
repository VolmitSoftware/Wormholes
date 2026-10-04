package art.arcane.wormholes.network.client;

import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.PlateBox;

public sealed interface ClientViewMessage {
    ClientViewMessageType type();

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

    record TravelBegin(UUID token, long generation, UUID sourcePortal, String sourceWorld, ClientPortalGeometry sourceGeometry,
                       ClientViewEnvironment.Transform destinationToSource, TravelWorld world, TravelPose arrival, List<TravelCoordinate> chunks, ClientViewEnvironment environment,
                       int expiresMillis) implements ClientViewMessage {
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
                || chunks.isEmpty() || chunks.size() > ClientViewProtocol.MAX_TRAVEL_CHUNKS
                || new HashSet<>(chunks).size() != chunks.size()
                || expiresMillis <= 0 || expiresMillis > ClientViewProtocol.MAX_TRAVEL_EXPIRY_MILLIS
                || !world.dimension().equals(environment.world().dimensionKey())
                || !ClientViewEnvironment.Transform.IDENTITY.equals(environment.transform())) {
                throw new IllegalArgumentException("Travel preparation");
            }
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.TRAVEL_BEGIN;
        }
    }

    record TravelChunk(UUID token, long generation, int chunkX, int chunkZ, int revision, int fragmentIndex,
                       int fragmentCount, int totalBytes, byte[] payload) implements ClientViewMessage {
        public TravelChunk {
            travelIdentity(token, generation);
            Objects.requireNonNull(payload, "payload");
            if (revision <= 0 || totalBytes <= 0 || totalBytes > ClientViewProtocol.MAX_TRAVEL_CHUNK_BYTES
                || fragmentCount != (totalBytes + ClientViewProtocol.TRAVEL_FRAGMENT_BYTES - 1) / ClientViewProtocol.TRAVEL_FRAGMENT_BYTES
                || fragmentIndex < 0 || fragmentIndex >= fragmentCount
                || payload.length != Math.min(ClientViewProtocol.TRAVEL_FRAGMENT_BYTES,
                    totalBytes - fragmentIndex * ClientViewProtocol.TRAVEL_FRAGMENT_BYTES)) {
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
        public ClientViewMessageType type() {
            return ClientViewMessageType.TRAVEL_CHUNK;
        }
    }

    record TravelReuse(UUID token, long generation, int chunkX, int chunkZ, int revision, byte[] hash) implements ClientViewMessage {
        public TravelReuse {
            travelIdentity(token, generation);
            Objects.requireNonNull(hash, "hash");
            if (revision <= 0 || hash.length != ClientViewProtocol.TRAVEL_HASH_BYTES) {
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
        public ClientViewMessageType type() {
            return ClientViewMessageType.TRAVEL_REUSE;
        }
    }

    record TravelCached(UUID token, long generation, int chunkX, int chunkZ, int revision, byte[] hash, boolean available) implements ClientViewMessage {
        public TravelCached {
            travelIdentity(token, generation);
            Objects.requireNonNull(hash, "hash");
            if (revision <= 0 || hash.length != ClientViewProtocol.TRAVEL_HASH_BYTES) {
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
        public ClientViewMessageType type() {
            return ClientViewMessageType.TRAVEL_CACHED;
        }
    }

    record TravelEnd(UUID token, long generation, long contentRevision, List<TravelChunkRevision> chunks) implements ClientViewMessage {
        public TravelEnd {
            travelIdentity(token, generation);
            chunks = List.copyOf(chunks);
            if (contentRevision <= 0 || chunks.isEmpty() || chunks.size() > ClientViewProtocol.MAX_TRAVEL_CHUNKS) {
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
        public ClientViewMessageType type() {
            return ClientViewMessageType.TRAVEL_END;
        }
    }

    record TravelReady(UUID token, long generation, long contentRevision) implements ClientViewMessage {
        public TravelReady {
            travelIdentity(token, generation);
            if (contentRevision <= 0) {
                throw new IllegalArgumentException("Travel ready revision");
            }
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.TRAVEL_READY;
        }
    }

    record TravelCommit(UUID token, long generation, long contentRevision, String sourceWorld, String destinationWorld,
                        TravelPose arrival, GeometryVector velocity) implements ClientViewMessage {
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
        public ClientViewMessageType type() {
            return ClientViewMessageType.TRAVEL_COMMIT;
        }
    }

    record TravelCross(UUID token, long generation, long contentRevision, TravelPose sourcePose,
                       GeometryVector previousEye, GeometryVector currentEye) implements ClientViewMessage {
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
        public ClientViewMessageType type() {
            return ClientViewMessageType.TRAVEL_CROSS;
        }
    }

    record TravelCancel(UUID token, long generation) implements ClientViewMessage {
        public TravelCancel {
            travelIdentity(token, generation);
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.TRAVEL_CANCEL;
        }
    }

    private static void travelVector(GeometryVector vector) {
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

    record Offer(int wire, int mcDataVersion, long serverCaps, int maxFrameBytes, long zeroCopyNonce) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.OFFER;
        }
    }

    record Hello(int wire, int mcDataVersion, long clientCaps, int maxFrameBytes, int plateMemoryMb, long zeroCopyNonceEcho,
                 String brandTag) implements ClientViewMessage {
        public Hello {
            Objects.requireNonNull(brandTag, "brandTag");
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.HELLO;
        }
    }

    record Accept(int sessionId, long caps, int tickRate, int maxFrameBytes, long hashSalt, int ackWindowFrames) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.ACCEPT;
        }
    }

    record Decline(DeclineReason reason) implements ClientViewMessage {
        public Decline {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.DECLINE;
        }
    }

    record Palette(List<PaletteEntry> entries) implements ClientViewMessage {
        public Palette {
            entries = List.copyOf(entries);
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.PALETTE;
        }
    }

    record PaletteEntry(int id, String state) {
        public PaletteEntry {
            Objects.requireNonNull(state, "state");
        }
    }

    record Portal(int portalKey, int geometryRevision, ClientPortalGeometry geometry) implements ClientViewMessage {
        public Portal {
            Objects.requireNonNull(geometry, "geometry");
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.PORTAL;
        }
    }

    record PortalDrop(int portalKey) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.PORTAL_DROP;
        }
    }

    record MeshBegin(int portalKey, int generation, PlateBox bounds, int maxResidentSections) implements ClientViewMessage {
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
        public ClientViewMessageType type() {
            return ClientViewMessageType.MESH_BEGIN;
        }
    }

    record MeshSection(int portalKey, int generation, int sectionX, int sectionY, int sectionZ, int revision,
                       int backingState, Brick brick, SectionBiomes biomes) implements ClientViewMessage {
        public MeshSection {
            Objects.requireNonNull(brick, "brick");
            Objects.requireNonNull(biomes, "biomes");
            if (brick.brickIndex() != 0) {
                throw new IllegalArgumentException("mesh section brick index " + brick.brickIndex());
            }
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.MESH_SECTION;
        }
    }

    record MeshDrop(int portalKey, int generation, int sectionX, int sectionY, int sectionZ) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.MESH_DROP;
        }
    }

    record MeshAck(int portalKey, int generation, int sectionX, int sectionY, int sectionZ, int revision) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.MESH_ACK;
        }
    }

    record MeshLocal(int portalKey, int generation, int sequence, boolean available,
                     List<MeshCoordinate> sections, List<UUID> entities) implements ClientViewMessage {
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
        public ClientViewMessageType type() {
            return ClientViewMessageType.MESH_LOCAL;
        }
    }

    record MeshCached(int portalKey, int generation, int sequence, boolean available, List<MeshClaim> claims) implements ClientViewMessage {
        public static final int MAX_CLAIMS = 512;

        public MeshCached {
            claims = List.copyOf(claims);
            if (generation <= 0 || sequence <= 0 || claims.size() > MAX_CLAIMS) {
                throw new IllegalArgumentException("mesh cache claims");
            }
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.MESH_CACHED;
        }
    }

    record MeshClaim(int x, int y, int z, long hash) {
    }

    record MeshReuse(int portalKey, int generation, int sectionX, int sectionY, int sectionZ, int revision, long hash) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.MESH_REUSE;
        }
    }

    record MeshCoordinate(int x, int y, int z) {
    }

    record PlateBegin(int portalKey, int plateRevision, PlateSectionBox sections, PlateBox cells, int backingState, int brickCount,
                      long[] brickHashes) implements ClientViewMessage {
        public PlateBegin {
            Objects.requireNonNull(sections, "sections");
            Objects.requireNonNull(cells, "cells");
            if (brickCount < 0 || brickCount > ClientViewProtocol.MAX_BRICKS_PER_PLATE) {
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
        public ClientViewMessageType type() {
            return ClientViewMessageType.PLATE_BEGIN;
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

    record PlateBricks(int portalKey, int plateRevision, List<Brick> bricks) implements ClientViewMessage {
        public PlateBricks {
            bricks = List.copyOf(bricks);
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.PLATE_BRICKS;
        }
    }

    record PlateEnd(int portalKey, int plateRevision) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.PLATE_END;
        }
    }

    record PlatePatch(int portalKey, int fromRevision, int toRevision, List<PatchOp> ops) implements ClientViewMessage {
        public PlatePatch {
            ops = List.copyOf(ops);
        }

        public boolean advances() {
            return toRevision != fromRevision;
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.PLATE_PATCH;
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
            if (cellIndices.length > ClientViewProtocol.BRICK_CELLS) {
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

    record PlateHandle(int portalKey, int plateRevision, long handle) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.PLATE_HANDLE;
        }
    }

    record BrickMiss(List<Plate> plates) implements ClientViewMessage {
        public BrickMiss {
            plates = List.copyOf(plates);
            if (plates.isEmpty() || plates.size() > ClientViewProtocol.MAX_BRICK_MISS_PLATES) {
                throw new IllegalArgumentException("brick miss for " + plates.size() + " plates");
            }
        }

        public static BrickMiss of(Plate plate) {
            return new BrickMiss(List.of(plate));
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.BRICK_MISS;
        }

        public record Plate(int portalKey, int plateRevision, long[] bitset) {
            public Plate {
                Objects.requireNonNull(bitset, "bitset");
                if (bitset.length > ClientViewProtocol.MAX_BRICK_MISS_WORDS) {
                    throw new IllegalArgumentException("brick miss bitset of " + bitset.length + " words");
                }
            }

            public boolean missed(int brickIndex) {
                int word = brickIndex >>> 6;
                return word < bitset.length && (bitset[word] & (1L << (brickIndex & 63))) != 0L;
            }

            public int wireBytes() {
                return ClientViewWriter.varintSize(portalKey) + 4 + ClientViewWriter.varintSize(bitset.length) + bitset.length * 8;
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

    record PlateRefused(int portalKey, int plateRevision) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.PLATE_REFUSED;
        }
    }

    record EntityEvent(int portalKey, int eventSeq, UUID entityId, boolean hurt, int animation, float yaw) implements ClientViewMessage {
        public EntityEvent {
            Objects.requireNonNull(entityId, "entityId");
            if (!Float.isFinite(yaw) || !hurt && animation != 0 && animation != 2 && animation != 3 && animation != 4 && animation != 5) {
                throw new IllegalArgumentException("entity event");
            }
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.ENTITY_EVENT;
        }
    }

    record EntitySelf(UUID projectedId) implements ClientViewMessage {
        public EntitySelf {
            Objects.requireNonNull(projectedId, "projectedId");
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.ENTITY_SELF;
        }
    }

    record EntityFrame(int portalKey, int entitySeq, List<EntityVisual> entities, List<UUID> presentIds, boolean presence)
        implements ClientViewMessage {
        public EntityFrame {
            entities = List.copyOf(entities);
            presentIds = List.copyOf(presentIds);
            if (!presence && !presentIds.isEmpty()) {
                throw new IllegalArgumentException("entity frame carries present ids without a presence update");
            }
            if (entities.size() > ClientViewProtocol.MAX_ENTITIES_PER_FRAME) {
                throw new IllegalArgumentException("entity frame with " + entities.size() + " entities");
            }
            if (presentIds.size() > ClientViewProtocol.MAX_PRESENT_IDS_PER_FRAME) {
                throw new IllegalArgumentException("entity frame with " + presentIds.size() + " present ids");
            }
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.ENTITY_FRAME;
        }
    }

    record Fx(int portalKey, List<FxEmitter> emitters) implements ClientViewMessage {
        public Fx {
            emitters = List.copyOf(emitters);
            if (emitters.size() > ClientViewProtocol.MAX_FX_EMITTERS) {
                throw new IllegalArgumentException("fx with " + emitters.size() + " emitters");
            }
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.FX;
        }
    }

    record FxEmitter(FxKind kind, String key, double x, double y, double z, float paramA, float paramB, int ticks, int flags) {
        public FxEmitter {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(key, "key");
        }
    }

    record Environment(int portalKey, ClientViewEnvironment environment) implements ClientViewMessage {
        public Environment {
            Objects.requireNonNull(environment, "environment");
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.ENVIRONMENT;
        }
    }

    record Atmosphere(int portalKey, long dayTime, float rain, float thunder, int flags) implements ClientViewMessage {
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
        public ClientViewMessageType type() {
            return ClientViewMessageType.ATMOSPHERE;
        }
    }

    record SessionReset(ResetReason reason) implements ClientViewMessage {
        public SessionReset {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.SESSION_RESET;
        }
    }

    record Ack(int seq, int clientTick, int appliedCells) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.ACK;
        }
    }

    record ViewStats(int clientTick, int attended, int overlayCells, int unknownStates, int sweepMicrosP50, int applyMicrosP50,
                     int plateMb) implements ClientViewMessage {
        @Override
        public ClientViewMessageType type() {
            return ClientViewMessageType.VIEW_STATS;
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

    enum FxKind {
        RIM_DUST,
        SURFACE,
        SOUND,
        DOOR_ANIM,
        ANIMATION,
        BURST;

        public static FxKind byId(int id) {
            FxKind[] values = values();
            return id < 0 || id >= values.length ? null : values[id];
        }
    }
}
