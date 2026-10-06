package art.arcane.wormholes.network.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.optics.stream.ClientViewReader;
import art.arcane.optics.stream.ClientViewWriter;
import art.arcane.optics.stream.PlateSectionBox;
import art.arcane.optics.stream.ProjectionEnvironmentCodec;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessageType;

public final class ClientViewCodec {
    private static final int MAX_CELL_BOX_EDGE = 65535;

    private ClientViewCodec() {
    }

    public record S2CFrame(int seq, int flags, ClientViewMessage message) {
        public boolean last() {
            return (flags & ViewStreamLimits.FLAG_LAST) != 0;
        }
    }

    public static byte[] encodeS2C(ClientViewMessage message, int seq, int flags) throws ClientViewProtocolException {
        return encodeS2C(message, seq, flags, false);
    }

    public static byte[] encodeS2C(ClientViewMessage message, int seq, int flags, boolean deflate) throws ClientViewProtocolException {
        if (!message.type().isClientbound()) {
            throw new ClientViewProtocolException(message.type() + " is not clientbound");
        }
        if ((flags & ~(ViewStreamLimits.FLAG_LAST | ViewStreamLimits.FLAG_RESERVED)) != 0) {
            throw new ClientViewProtocolException("caller flags " + flags + " are not allowed");
        }
        ClientViewWriter body = new ClientViewWriter(256);
        writeBody(body, message);
        byte[] raw = body.rawBuffer();
        int rawLength = body.size();
        int outFlags = flags;
        byte[] payloadBody = null;
        if (deflate && rawLength >= ViewStreamLimits.DEFLATE_THRESHOLD_BYTES) {
            byte[] deflated = deflate(raw, rawLength);
            if (deflated.length < rawLength) {
                payloadBody = deflated;
                outFlags |= ViewStreamLimits.FLAG_DEFLATED;
            }
        }
        int bodyLength = payloadBody == null ? rawLength : payloadBody.length;
        if (bodyLength + ViewStreamLimits.S2C_HEADER_BYTES > ViewStreamLimits.HARD_MAX_FRAME_BYTES) {
            throw new ClientViewProtocolException(message.type() + " frame of " + (bodyLength + ViewStreamLimits.S2C_HEADER_BYTES)
                + " bytes exceeds the hard cap");
        }
        ClientViewWriter out = new ClientViewWriter(ViewStreamLimits.S2C_HEADER_BYTES + bodyLength);
        out.u8(message.type().id());
        out.i32(seq);
        out.u8(outFlags);
        if (payloadBody == null) {
            out.bytes(raw, 0, rawLength);
        } else {
            out.bytes(payloadBody);
        }
        return out.toByteArray();
    }

    public static byte[] encodeC2S(ClientViewMessage message) throws ClientViewProtocolException {
        if (!message.type().isServerbound()) {
            throw new ClientViewProtocolException(message.type() + " is not serverbound");
        }
        ClientViewWriter out = new ClientViewWriter(64);
        out.u8(message.type().id());
        writeBody(out, message);
        if (out.size() > ViewStreamLimits.MAX_C2S_BYTES) {
            throw new ClientViewProtocolException(message.type() + " payload of " + out.size() + " bytes exceeds the C2S cap");
        }
        return out.toByteArray();
    }

    public static S2CFrame decodeS2C(byte[] payload, long caps) throws ClientViewProtocolException {
        return decodeS2C(payload, 0, payload.length, caps);
    }

    public static S2CFrame decodeS2C(byte[] payload, int offset, int length, long caps) throws ClientViewProtocolException {
        if (length > ViewStreamLimits.HARD_MAX_FRAME_BYTES) {
            throw new ClientViewProtocolException("S2C payload of " + length + " bytes exceeds the hard cap");
        }
        ClientViewReader header = new ClientViewReader(payload, offset, length);
        ViewStreamMessageType type = ViewStreamMessageType.byId(header.u8());
        if (type == null || !type.isClientbound()) {
            throw new ClientViewProtocolException("unknown clientbound message type");
        }
        int seq = header.i32();
        int flags = header.u8();
        if ((flags & ~ViewStreamLimits.FLAG_MASK) != 0) {
            throw new ClientViewProtocolException("unknown frame flags " + flags);
        }
        ClientViewReader body;
        if ((flags & ViewStreamLimits.FLAG_DEFLATED) != 0) {
            byte[] inflated = inflate(payload, offset + header.position(), header.remaining(), ViewStreamLimits.HARD_MAX_FRAME_BYTES);
            body = new ClientViewReader(inflated);
        } else {
            body = new ClientViewReader(payload, offset + header.position(), header.remaining());
        }
        ClientViewMessage message = readBody(body, type, caps);
        body.expectEnd();
        return new S2CFrame(seq, flags, message);
    }

    public static ClientViewMessage decodeC2S(byte[] payload) throws ClientViewProtocolException {
        return decodeC2S(payload, 0, payload.length);
    }

    public static ClientViewMessage decodeC2S(byte[] payload, int offset, int length) throws ClientViewProtocolException {
        if (length > ViewStreamLimits.MAX_C2S_BYTES) {
            throw new ClientViewProtocolException("C2S payload of " + length + " bytes exceeds the cap");
        }
        ClientViewReader in = new ClientViewReader(payload, offset, length);
        ViewStreamMessageType type = ViewStreamMessageType.byId(in.u8());
        if (type == null || !type.isServerbound()) {
            throw new ClientViewProtocolException("unknown serverbound message type");
        }
        ClientViewMessage message = readBody(in, type, ViewStreamCapability.NONE);
        in.expectEnd();
        return message;
    }

    public static byte[] encodeBody(ClientViewMessage message) throws ClientViewProtocolException {
        ClientViewWriter out = new ClientViewWriter(256);
        writeBody(out, message);
        return out.toByteArray();
    }

    public static void writeBody(ClientViewWriter out, ClientViewMessage message) throws ClientViewProtocolException {
        switch (message) {
            case ClientViewMessage.Offer m -> {
                out.u16(m.wire());
                out.i32(m.mcDataVersion());
                out.i64(m.serverCaps());
                out.i32(m.maxFrameBytes());
                out.i64(m.zeroCopyNonce());
            }
            case ClientViewMessage.Hello m -> {
                out.u16(m.wire());
                out.i32(m.mcDataVersion());
                out.i64(m.clientCaps());
                out.i32(m.maxFrameBytes());
                out.u16(m.plateMemoryMb());
                out.i64(m.zeroCopyNonceEcho());
                out.string(m.brandTag());
            }
            case ClientViewMessage.Accept m -> {
                out.i32(m.sessionId());
                out.i64(m.caps());
                out.u8(m.tickRate());
                out.i32(m.maxFrameBytes());
                out.i64(m.hashSalt());
                out.u8(m.ackWindowFrames());
            }
            case ClientViewMessage.Decline m -> out.u8(m.reason().ordinal());
            case ClientViewMessage.Palette m -> {
                List<ClientViewMessage.PaletteEntry> entries = m.entries();
                if (entries.size() > ViewStreamLimits.MAX_PALETTE_ENTRIES_PER_MESSAGE) {
                    throw new ClientViewProtocolException("palette message with " + entries.size() + " entries");
                }
                out.varint(entries.size());
                for (ClientViewMessage.PaletteEntry entry : entries) {
                    out.varint(entry.id());
                    out.string(entry.state());
                }
            }
            case ClientViewMessage.Portal m -> {
                out.varint(m.portalKey());
                out.i32(m.geometryRevision());
                writeGeometry(out, m.geometry(), 0);
            }
            case ClientViewMessage.PortalDrop m -> out.varint(m.portalKey());
            case ClientViewMessage.MeshBegin m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.bounds().minX());
                out.i32(m.bounds().minY());
                out.i32(m.bounds().minZ());
                out.u16(m.bounds().sizeX());
                out.u16(m.bounds().sizeY());
                out.u16(m.bounds().sizeZ());
                out.varint(m.maxResidentSections());
            }
            case ClientViewMessage.MeshSection m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sectionX());
                out.i32(m.sectionY());
                out.i32(m.sectionZ());
                out.i32(m.revision());
                out.varint(m.backingState());
                BrickCodec.write(out, m.brick());
                out.u16(m.biomes().palette().size());
                for (String biome : m.biomes().palette()) {
                    out.string(biome);
                }
                out.bytes(m.biomes().indices());
            }
            case ClientViewMessage.MeshDrop m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sectionX());
                out.i32(m.sectionY());
                out.i32(m.sectionZ());
            }
            case ClientViewMessage.MeshAck m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sectionX());
                out.i32(m.sectionY());
                out.i32(m.sectionZ());
                out.i32(m.revision());
            }
            case ClientViewMessage.MeshCached m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sequence());
                out.u8(m.available() ? 1 : 0);
                out.u16(m.claims().size());
                for (ClientViewMessage.MeshClaim claim : m.claims()) {
                    out.i32(claim.x());
                    out.i32(claim.y());
                    out.i32(claim.z());
                    out.i64(claim.hash());
                }
            }
            case ClientViewMessage.MeshReuse m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sectionX());
                out.i32(m.sectionY());
                out.i32(m.sectionZ());
                out.i32(m.revision());
                out.i64(m.hash());
            }
            case ClientViewMessage.TravelBegin m -> ClientViewTravelCodec.write(out, m);
            case ClientViewMessage.TravelChunk m -> ClientViewTravelCodec.write(out, m);
            case ClientViewMessage.TravelEnd m -> ClientViewTravelCodec.write(out, m);
            case ClientViewMessage.TravelReady m -> ClientViewTravelCodec.write(out, m);
            case ClientViewMessage.TravelCommit m -> ClientViewTravelCodec.write(out, m);
            case ClientViewMessage.TravelCancel m -> ClientViewTravelCodec.write(out, m);
            case ClientViewMessage.TravelCross m -> ClientViewTravelCodec.write(out, m);
            case ClientViewMessage.TravelReuse m -> ClientViewTravelCodec.write(out, m);
            case ClientViewMessage.TravelCached m -> ClientViewTravelCodec.write(out, m);
            case ClientViewMessage.MeshLocal m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sequence());
                out.u8(m.available() ? 1 : 0);
                out.u16(m.sections().size());
                for (ClientViewMessage.MeshCoordinate section : m.sections()) {
                    out.i32(section.x());
                    out.i32(section.y());
                    out.i32(section.z());
                }
                out.u16(m.entities().size());
                for (UUID entity : m.entities()) {
                    out.i64(entity.getMostSignificantBits());
                    out.i64(entity.getLeastSignificantBits());
                }
            }
            case ClientViewMessage.PlateBegin m -> {
                out.varint(m.portalKey());
                out.i32(m.plateRevision());
                PlateSectionBox sections = m.sections();
                out.i32(sections.minSectionX());
                out.i32(sections.minSectionY());
                out.i32(sections.minSectionZ());
                out.u8(sections.sizeX());
                out.u8(sections.sizeY());
                out.u8(sections.sizeZ());
                PlateBox cells = m.cells();
                if (cells.sizeX() > MAX_CELL_BOX_EDGE || cells.sizeY() > MAX_CELL_BOX_EDGE || cells.sizeZ() > MAX_CELL_BOX_EDGE) {
                    throw new ClientViewProtocolException("cell box edge exceeds " + MAX_CELL_BOX_EDGE);
                }
                out.i32(cells.minX());
                out.i32(cells.minY());
                out.i32(cells.minZ());
                out.u16(cells.sizeX());
                out.u16(cells.sizeY());
                out.u16(cells.sizeZ());
                out.varint(m.backingState());
                out.u16(m.brickCount());
                if (m.hasHashes()) {
                    out.longs(m.brickHashes());
                }
            }
            case ClientViewMessage.PlateBricks m -> {
                List<Brick> bricks = m.bricks();
                if (bricks.size() > ViewStreamLimits.MAX_BRICKS_PER_PLATE) {
                    throw new ClientViewProtocolException("plate bricks message with " + bricks.size() + " bricks");
                }
                out.varint(m.portalKey());
                out.i32(m.plateRevision());
                out.u16(bricks.size());
                for (Brick brick : bricks) {
                    BrickCodec.write(out, brick);
                }
            }
            case ClientViewMessage.PlateEnd m -> {
                out.varint(m.portalKey());
                out.i32(m.plateRevision());
            }
            case ClientViewMessage.PlatePatch m -> {
                List<ClientViewMessage.PatchOp> ops = m.ops();
                if (ops.size() > ViewStreamLimits.MAX_PATCH_OPS) {
                    throw new ClientViewProtocolException("plate patch with " + ops.size() + " ops");
                }
                out.varint(m.portalKey());
                out.i32(m.fromRevision());
                out.i32(m.toRevision());
                out.u16(ops.size());
                for (ClientViewMessage.PatchOp op : ops) {
                    writePatchOp(out, op);
                }
            }
            case ClientViewMessage.PlateHandle m -> {
                out.varint(m.portalKey());
                out.i32(m.plateRevision());
                out.i64(m.handle());
            }
            case ClientViewMessage.BrickMiss m -> {
                out.u8(m.plates().size());
                for (ClientViewMessage.BrickMiss.Plate plate : m.plates()) {
                    out.varint(plate.portalKey());
                    out.i32(plate.plateRevision());
                    out.varint(plate.bitset().length);
                    out.longs(plate.bitset());
                }
            }
            case ClientViewMessage.PlateRefused m -> {
                out.varint(m.portalKey());
                out.i32(m.plateRevision());
            }
            case ClientViewMessage.EntityEvent m -> {
                out.varint(m.portalKey());
                out.i32(m.eventSeq());
                out.i64(m.entityId().getMostSignificantBits());
                out.i64(m.entityId().getLeastSignificantBits());
                out.u8(m.hurt() ? 1 : 0);
                out.u8(m.animation());
                out.f32(m.yaw());
            }
            case ClientViewMessage.EntitySelf m -> {
                out.i64(m.projectedId().getMostSignificantBits());
                out.i64(m.projectedId().getLeastSignificantBits());
            }
            case ClientViewMessage.EntityFrame m -> {
                out.varint(m.portalKey());
                out.i32(m.entitySeq());
                out.u8(m.entities().size());
                for (EntitySnapshot visual : m.entities()) {
                    byte[] bytes = entityBytes(visual);
                    out.varint(bytes.length);
                    out.bytes(bytes);
                }
                if (!m.presence()) {
                    out.u16(ViewStreamLimits.PRESENCE_UNCHANGED);
                } else {
                    out.u16(m.presentIds().size());
                    for (UUID id : m.presentIds()) {
                        out.i64(id.getMostSignificantBits());
                        out.i64(id.getLeastSignificantBits());
                    }
                }
            }
            case ClientViewMessage.Fx m -> {
                out.varint(m.portalKey());
                out.u8(m.emitters().size());
                for (ClientViewMessage.FxEmitter emitter : m.emitters()) {
                    out.u8(emitter.kind().ordinal());
                    out.string(emitter.key());
                    out.f64(emitter.x());
                    out.f64(emitter.y());
                    out.f64(emitter.z());
                    out.f32(emitter.paramA());
                    out.f32(emitter.paramB());
                    out.u16(emitter.ticks());
                    out.u8(emitter.flags());
                }
            }
            case ClientViewMessage.Environment m -> {
                out.varint(m.portalKey());
                ProjectionEnvironmentCodec.write(out, m.environment());
            }
            case ClientViewMessage.Atmosphere m -> {
                out.varint(m.portalKey());
                out.i64(m.dayTime());
                out.f32(m.rain());
                out.f32(m.thunder());
                out.u8(m.flags());
            }
            case ClientViewMessage.SessionReset m -> out.u8(m.reason().ordinal());
            case ClientViewMessage.Ack m -> {
                out.i32(m.seq());
                out.i32(m.clientTick());
                out.i32(m.appliedCells());
            }
            case ClientViewMessage.ViewStats m -> {
                out.i32(m.clientTick());
                out.u16(m.attended());
                out.i32(m.overlayCells());
                out.u16(m.unknownStates());
                out.u16(m.sweepMicrosP50());
                out.u16(m.applyMicrosP50());
                out.u16(m.plateMb());
            }
        }
    }

    public static ClientViewMessage readBody(ClientViewReader in, ViewStreamMessageType type, long caps) throws ClientViewProtocolException {
        return switch (type) {
            case OFFER -> new ClientViewMessage.Offer(in.u16(), in.i32(), in.i64(), in.i32(), in.i64());
            case HELLO -> new ClientViewMessage.Hello(in.u16(), in.i32(), in.i64(), in.i32(), in.u16(), in.i64(), in.string());
            case ACCEPT -> new ClientViewMessage.Accept(in.i32(), in.i64(), in.u8(), in.i32(), in.i64(), in.u8());
            case DECLINE -> {
                ClientViewMessage.DeclineReason reason = ClientViewMessage.DeclineReason.byId(in.u8());
                if (reason == null) {
                    throw new ClientViewProtocolException("unknown decline reason");
                }
                yield new ClientViewMessage.Decline(reason);
            }
            case PALETTE -> {
                int count = in.checkedCount(in.varint(), ViewStreamLimits.MAX_PALETTE_ENTRIES_PER_MESSAGE, 2);
                List<ClientViewMessage.PaletteEntry> entries = new ArrayList<ClientViewMessage.PaletteEntry>(count);
                for (int i = 0; i < count; i++) {
                    int id = in.varint(ViewStreamLimits.MAX_SESSION_PALETTE_SIZE - 1);
                    entries.add(new ClientViewMessage.PaletteEntry(id, in.string()));
                }
                yield new ClientViewMessage.Palette(entries);
            }
            case PORTAL -> {
                int portalKey = in.varint();
                int revision = in.i32();
                yield new ClientViewMessage.Portal(portalKey, revision, readGeometry(in, 0));
            }
            case PORTAL_DROP -> new ClientViewMessage.PortalDrop(in.varint());
            case MESH_BEGIN -> {
                int portalKey = in.varint();
                int generation = in.i32();
                PlateBox bounds = new PlateBox(in.i32(), in.i32(), in.i32(), in.u16(), in.u16(), in.u16());
                int limit = in.varint(Integer.MAX_VALUE);
                if (limit == 0 || bounds.cells() == 0) {
                    throw new ClientViewProtocolException("mesh view requires nonempty bounds and a resident budget");
                }
                try {
                    yield new ClientViewMessage.MeshBegin(portalKey, generation, bounds, limit);
                } catch (IllegalArgumentException invalid) {
                    throw new ClientViewProtocolException("invalid mesh section capacity", invalid);
                }
            }
            case MESH_SECTION -> {
                int portalKey = in.varint();
                int generation = in.i32();
                int sectionX = in.i32();
                int sectionY = in.i32();
                int sectionZ = in.i32();
                int revision = in.i32();
                int backing = in.varint(ViewStreamLimits.MAX_SESSION_PALETTE_SIZE - 1);
                Brick brick = BrickCodec.read(in);
                if (brick.brickIndex() != 0) {
                    throw new ClientViewProtocolException("mesh section brick index must be zero");
                }
                yield new ClientViewMessage.MeshSection(portalKey, generation, sectionX, sectionY, sectionZ, revision, backing, brick, readSectionBiomes(in));
            }
            case MESH_DROP -> new ClientViewMessage.MeshDrop(in.varint(), in.i32(), in.i32(), in.i32(), in.i32());
            case MESH_ACK -> new ClientViewMessage.MeshAck(in.varint(), in.i32(), in.i32(), in.i32(), in.i32(), in.i32());
            case MESH_CACHED -> {
                int portalKey = in.varint();
                int generation = in.i32();
                int sequence = in.i32();
                int available = in.u8();
                int count = in.u16();
                if (generation <= 0 || sequence <= 0 || available > 1 || count > ClientViewMessage.MeshCached.MAX_CLAIMS) {
                    throw new ClientViewProtocolException("Invalid mesh cache claims");
                }
                List<ClientViewMessage.MeshClaim> claims = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    claims.add(new ClientViewMessage.MeshClaim(in.i32(), in.i32(), in.i32(), in.i64()));
                }
                yield new ClientViewMessage.MeshCached(portalKey, generation, sequence, available == 1, claims);
            }
            case MESH_REUSE -> {
                int portalKey = in.varint();
                int generation = in.i32();
                int x = in.i32();
                int y = in.i32();
                int z = in.i32();
                int revision = in.i32();
                long hash = in.i64();
                if (generation <= 0 || revision <= 0) {
                    throw new ClientViewProtocolException("Invalid mesh reuse acknowledgment");
                }
                yield new ClientViewMessage.MeshReuse(portalKey, generation, x, y, z, revision, hash);
            }
            case MESH_LOCAL -> {
                int portalKey = in.varint();
                int generation = in.i32();
                int sequence = in.i32();
                int available = in.u8();
                int sectionCount = in.u16();
                if (generation <= 0 || sequence <= 0 || available > 1 || sectionCount > ClientViewMessage.MeshLocal.MAX_SECTIONS) {
                    throw new ClientViewProtocolException("Invalid local mesh availability");
                }
                List<ClientViewMessage.MeshCoordinate> sections = new ArrayList<>(sectionCount);
                for (int index = 0; index < sectionCount; index++) {
                    sections.add(new ClientViewMessage.MeshCoordinate(in.i32(), in.i32(), in.i32()));
                }
                int entityCount = in.u16();
                if (entityCount > ClientViewMessage.MeshLocal.MAX_ENTITIES) {
                    throw new ClientViewProtocolException("Too many local mesh entities");
                }
                List<UUID> entities = new ArrayList<>(entityCount);
                for (int index = 0; index < entityCount; index++) {
                    entities.add(new UUID(in.i64(), in.i64()));
                }
                yield new ClientViewMessage.MeshLocal(portalKey, generation, sequence, available == 1, sections, entities);
            }
            case PLATE_BEGIN -> {
                int portalKey = in.varint();
                int revision = in.i32();
                int minSectionX = in.i32();
                int minSectionY = in.i32();
                int minSectionZ = in.i32();
                int sizeX = in.u8();
                int sizeY = in.u8();
                int sizeZ = in.u8();
                if ((long) sizeX * sizeY * sizeZ > ViewStreamLimits.MAX_BRICKS_PER_PLATE) {
                    throw new ClientViewProtocolException("section box exceeds the brick cap");
                }
                PlateSectionBox sections = new PlateSectionBox(minSectionX, minSectionY, minSectionZ, sizeX, sizeY, sizeZ);
                int minX = in.i32();
                int minY = in.i32();
                int minZ = in.i32();
                PlateBox cells = new PlateBox(minX, minY, minZ, in.u16(), in.u16(), in.u16());
                int backingState = in.varint(ViewStreamLimits.MAX_SESSION_PALETTE_SIZE - 1);
                int brickCount = in.u16();
                if (brickCount != sections.brickCount()) {
                    throw new ClientViewProtocolException("brick count " + brickCount + " does not match the section box");
                }
                long[] hashes = null;
                if (ViewStreamCapability.BRICK_CACHE.in(caps) && (brickCount == 0 || in.remaining() > 0)) {
                    if (in.remaining() != brickCount * Long.BYTES) {
                        throw new ClientViewProtocolException("brick hash manifest does not match the brick count");
                    }
                    hashes = in.longs(brickCount);
                }
                yield new ClientViewMessage.PlateBegin(portalKey, revision, sections, cells, backingState, brickCount, hashes);
            }
            case PLATE_BRICKS -> {
                int portalKey = in.varint();
                int revision = in.i32();
                int count = in.checkedCount(in.u16(), ViewStreamLimits.MAX_BRICKS_PER_PLATE, 5);
                List<Brick> bricks = new ArrayList<Brick>(count);
                for (int i = 0; i < count; i++) {
                    bricks.add(BrickCodec.read(in));
                }
                yield new ClientViewMessage.PlateBricks(portalKey, revision, bricks);
            }
            case PLATE_END -> new ClientViewMessage.PlateEnd(in.varint(), in.i32());
            case PLATE_PATCH -> {
                int portalKey = in.varint();
                int from = in.i32();
                int to = in.i32();
                int count = in.checkedCount(in.u16(), ViewStreamLimits.MAX_PATCH_OPS, 3);
                List<ClientViewMessage.PatchOp> ops = new ArrayList<ClientViewMessage.PatchOp>(count);
                for (int i = 0; i < count; i++) {
                    ops.add(readPatchOp(in));
                }
                yield new ClientViewMessage.PlatePatch(portalKey, from, to, ops);
            }
            case PLATE_HANDLE -> new ClientViewMessage.PlateHandle(in.varint(), in.i32(), in.i64());
            case BRICK_MISS -> {
                int count = in.checkedCount(in.u8(), ViewStreamLimits.MAX_BRICK_MISS_PLATES, 6);
                if (count == 0) {
                    throw new ClientViewProtocolException("brick miss without plates");
                }
                List<ClientViewMessage.BrickMiss.Plate> plates = new ArrayList<ClientViewMessage.BrickMiss.Plate>(count);
                for (int i = 0; i < count; i++) {
                    int portalKey = in.varint();
                    int revision = in.i32();
                    int words = in.checkedCount(in.varint(), ViewStreamLimits.MAX_BRICK_MISS_WORDS, 8);
                    plates.add(new ClientViewMessage.BrickMiss.Plate(portalKey, revision, in.longs(words)));
                }
                yield new ClientViewMessage.BrickMiss(plates);
            }
            case ENTITY_EVENT -> {
                int portalKey = in.varint();
                int eventSeq = in.i32();
                UUID entityId = new UUID(in.i64(), in.i64());
                boolean hurt = readFlag(in);
                int animation = in.u8();
                float yaw = in.f32();
                if (!Float.isFinite(yaw) || !hurt && animation != 0 && animation != 2 && animation != 3 && animation != 4 && animation != 5) {
                    throw new ClientViewProtocolException("invalid entity event");
                }
                yield new ClientViewMessage.EntityEvent(portalKey, eventSeq, entityId, hurt, animation, yaw);
            }
            case ENTITY_SELF -> new ClientViewMessage.EntitySelf(new UUID(in.i64(), in.i64()));
            case ENTITY_FRAME -> {
                int portalKey = in.varint();
                int seq = in.i32();
                int count = in.checkedCount(in.u8(), ViewStreamLimits.MAX_ENTITIES_PER_FRAME, 1);
                List<EntitySnapshot> entities = new ArrayList<EntitySnapshot>(count);
                for (int i = 0; i < count; i++) {
                    int length = in.varint(ViewStreamLimits.MAX_ENTITY_VISUAL_BYTES);
                    entities.add(entityFromBytes(in.bytes(length)));
                }
                int presentCount = in.u16();
                if (presentCount == ViewStreamLimits.PRESENCE_UNCHANGED) {
                    yield new ClientViewMessage.EntityFrame(portalKey, seq, entities, List.of(), false);
                }
                int present = in.checkedCount(presentCount, ViewStreamLimits.MAX_PRESENT_IDS_PER_FRAME, 16);
                List<UUID> presentIds = new ArrayList<UUID>(present);
                for (int i = 0; i < present; i++) {
                    long most = in.i64();
                    presentIds.add(new UUID(most, in.i64()));
                }
                yield new ClientViewMessage.EntityFrame(portalKey, seq, entities, presentIds, true);
            }
            case FX -> {
                int portalKey = in.varint();
                int count = in.checkedCount(in.u8(), ViewStreamLimits.MAX_FX_EMITTERS, 37);
                List<ClientViewMessage.FxEmitter> emitters = new ArrayList<ClientViewMessage.FxEmitter>(count);
                for (int i = 0; i < count; i++) {
                    ClientViewMessage.FxKind kind = ClientViewMessage.FxKind.byId(in.u8());
                    if (kind == null) {
                        throw new ClientViewProtocolException("unknown fx kind");
                    }
                    String key = in.string();
                    double x = in.f64();
                    double y = in.f64();
                    double z = in.f64();
                    float a = in.f32();
                    float b = in.f32();
                    int ticks = in.u16();
                    emitters.add(new ClientViewMessage.FxEmitter(kind, key, x, y, z, a, b, ticks, in.u8()));
                }
                yield new ClientViewMessage.Fx(portalKey, emitters);
            }
            case TRAVEL_BEGIN, TRAVEL_CHUNK, TRAVEL_END, TRAVEL_READY, TRAVEL_COMMIT, TRAVEL_CANCEL, TRAVEL_CROSS, TRAVEL_REUSE, TRAVEL_CACHED -> ClientViewTravelCodec.read(in, type);
            case ENVIRONMENT -> new ClientViewMessage.Environment(in.varint(), ProjectionEnvironmentCodec.read(in));
            case ATMOSPHERE -> new ClientViewMessage.Atmosphere(in.varint(), in.i64(), in.f32(), in.f32(), in.u8());
            case SESSION_RESET -> {
                ClientViewMessage.ResetReason reason = ClientViewMessage.ResetReason.byId(in.u8());
                if (reason == null) {
                    throw new ClientViewProtocolException("unknown reset reason");
                }
                yield new ClientViewMessage.SessionReset(reason);
            }
            case ACK -> new ClientViewMessage.Ack(in.i32(), in.i32(), in.i32());
            case VIEW_STATS -> new ClientViewMessage.ViewStats(in.i32(), in.u16(), in.i32(), in.u16(), in.u16(), in.u16(), in.u16());
            case PLATE_REFUSED -> new ClientViewMessage.PlateRefused(in.varint(), in.i32());
        };
    }

    public static void writePatchOp(ClientViewWriter out, ClientViewMessage.PatchOp op) throws ClientViewProtocolException {
        out.u16(op.brickIndex());
        out.u8(op.op());
        switch (op) {
            case ClientViewMessage.FullOp full -> BrickCodec.write(out, full.brick());
            case ClientViewMessage.SparseOp sparse -> {
                int[] cells = sparse.cellIndices();
                int[] ids = sparse.paletteIds();
                out.u16(cells.length);
                for (int i = 0; i < cells.length; i++) {
                    out.u16(cells[i]);
                    out.varint(ids[i]);
                }
            }
            case ClientViewMessage.ClearOp clear -> {
            }
        }
    }

    public static ClientViewMessage.PatchOp readPatchOp(ClientViewReader in) throws ClientViewProtocolException {
        int brickIndex = in.u16();
        int op = in.u8();
        return switch (op) {
            case ClientViewMessage.PatchOp.OP_FULL -> {
                Brick brick = BrickCodec.read(in);
                if (brick.brickIndex() != brickIndex) {
                    throw new ClientViewProtocolException("FULL op brick index mismatch");
                }
                yield new ClientViewMessage.FullOp(brick);
            }
            case ClientViewMessage.PatchOp.OP_SPARSE -> {
                int count = in.checkedCount(in.u16(), ViewStreamLimits.BRICK_CELLS, 3);
                int[] cells = new int[count];
                int[] ids = new int[count];
                for (int i = 0; i < count; i++) {
                    cells[i] = in.u16();
                    if (cells[i] >= ViewStreamLimits.BRICK_CELLS) {
                        throw new ClientViewProtocolException("sparse cell outside the brick");
                    }
                    ids[i] = in.varint(ViewStreamLimits.MAX_SESSION_PALETTE_SIZE - 1);
                }
                yield new ClientViewMessage.SparseOp(brickIndex, cells, ids);
            }
            case ClientViewMessage.PatchOp.OP_CLEAR -> new ClientViewMessage.ClearOp(brickIndex);
            default -> throw new ClientViewProtocolException("unknown patch op " + op);
        };
    }

    public static void writeGeometry(ClientViewWriter out, ApertureDescriptor geometry, int depth) throws ClientViewProtocolException {
        if (depth >= ViewStreamLimits.MAX_GEOMETRY_DEPTH) {
            throw new ClientViewProtocolException("portal geometry nested deeper than " + ViewStreamLimits.MAX_GEOMETRY_DEPTH);
        }
        out.i32(geometry.originX());
        out.i32(geometry.originY());
        out.i32(geometry.originZ());
        out.u8(geometry.facing());
        out.u8(geometry.frontSide() ? 1 : 0);
        out.u8(geometry.quarterTurns());
        out.u8(geometry.mirror() ? 1 : 0);
        out.u16(geometry.apertureWidth());
        out.u16(geometry.apertureHeight());
        long[] mask = geometry.apertureMask();
        if (mask.length > ViewStreamLimits.MAX_APERTURE_MASK_WORDS) {
            throw new ClientViewProtocolException("aperture mask of " + mask.length + " words");
        }
        out.varint(mask.length);
        out.longs(mask);
        out.f32(geometry.nearPlanePadding());
        out.f32(geometry.aperturePadding());
        out.f32(geometry.frustumCullingRatio());
        out.u16(geometry.depthBlocks());
        out.u8(geometry.recursionDepth());
        out.u8(geometry.blackoutPolicy());
        out.varint(geometry.blackoutState());
        out.u8(geometry.maskAirPolicy());
        out.u8(geometry.lightingPolicy());
        out.u8(geometry.fidelityFlags());
        out.u8(geometry.kind());
        out.f64(geometry.planeOffset());
        out.varint(geometry.parentPortalKey());
        out.i64(geometry.targetIdentity());
        List<ApertureDescriptor> nested = geometry.nested();
        if (nested.size() > ViewStreamLimits.MAX_NESTED_GEOMETRY) {
            throw new ClientViewProtocolException("portal geometry with " + nested.size() + " nested portals");
        }
        out.u8(nested.size());
        for (ApertureDescriptor child : nested) {
            writeGeometry(out, child, depth + 1);
        }
    }

    public static ApertureDescriptor readGeometry(ClientViewReader in, int depth) throws ClientViewProtocolException {
        if (depth >= ViewStreamLimits.MAX_GEOMETRY_DEPTH) {
            throw new ClientViewProtocolException("portal geometry nested deeper than " + ViewStreamLimits.MAX_GEOMETRY_DEPTH);
        }
        int originX = in.i32();
        int originY = in.i32();
        int originZ = in.i32();
        int facing = in.u8();
        boolean frontSide = readFlag(in);
        int quarterTurns = in.u8();
        boolean mirror = readFlag(in);
        int apertureWidth = in.u16();
        int apertureHeight = in.u16();
        int words = in.checkedCount(in.varint(), ViewStreamLimits.MAX_APERTURE_MASK_WORDS, 8);
        long[] mask = in.longs(words);
        float nearPlanePadding = in.f32();
        float aperturePadding = in.f32();
        float frustumCullingRatio = in.f32();
        int depthBlocks = in.u16();
        int recursionDepth = in.u8();
        int blackoutPolicy = in.u8();
        int blackoutState = in.varint(ViewStreamLimits.MAX_SESSION_PALETTE_SIZE - 1);
        int maskAirPolicy = in.u8();
        int lightingPolicy = in.u8();
        int fidelityFlags = in.u8();
        int kind = in.u8();
        double planeOffset = in.f64();
        int parentPortalKey = in.varint();
        long targetIdentity = in.i64();
        int nestedCount = in.checkedCount(in.u8(), ViewStreamLimits.MAX_NESTED_GEOMETRY, 40);
        List<ApertureDescriptor> nested = new ArrayList<ApertureDescriptor>(nestedCount);
        for (int i = 0; i < nestedCount; i++) {
            nested.add(readGeometry(in, depth + 1));
        }
        return new ApertureDescriptor(originX, originY, originZ, facing, frontSide, quarterTurns, mirror, apertureWidth, apertureHeight,
            mask, nearPlanePadding, aperturePadding, frustumCullingRatio, depthBlocks, recursionDepth, blackoutPolicy, blackoutState,
            maskAirPolicy, lightingPolicy, fidelityFlags, kind, planeOffset, parentPortalKey, targetIdentity, nested);
    }

    public static byte[] deflate(byte[] data, int length) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(data, 0, length);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int produced = deflater.deflate(buffer);
                out.write(buffer, 0, produced);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    public static byte[] inflate(byte[] data, int offset, int length, int maxOutput) throws ClientViewProtocolException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxOutput, Math.max(256, length * 4)));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int produced = inflater.inflate(buffer);
                if (produced == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new ClientViewProtocolException("truncated deflate stream");
                    }
                    continue;
                }
                if (out.size() + produced > maxOutput) {
                    throw new ClientViewProtocolException("inflated frame exceeds " + maxOutput + " bytes");
                }
                out.write(buffer, 0, produced);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new ClientViewProtocolException("corrupt deflate stream", e);
        } finally {
            inflater.end();
        }
    }

    public static byte[] entityBytes(EntitySnapshot visual) throws ClientViewProtocolException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        DataOutputStream out = new DataOutputStream(buffer);
        try {
            visual.write(out);
            out.flush();
        } catch (IOException e) {
            throw new ClientViewProtocolException("entity visual encode failed", e);
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > ViewStreamLimits.MAX_ENTITY_VISUAL_BYTES) {
            throw new ClientViewProtocolException("entity visual of " + bytes.length + " bytes exceeds the cap");
        }
        return bytes;
    }

    public static EntitySnapshot entityFromBytes(byte[] bytes) throws ClientViewProtocolException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            EntitySnapshot visual = EntitySnapshot.read(in);
            if (in.available() != 0) {
                throw new ClientViewProtocolException("trailing bytes after entity visual");
            }
            return visual;
        } catch (IOException | RuntimeException e) {
            throw new ClientViewProtocolException("corrupt entity visual", e);
        }
    }

    private static SectionBiomes readSectionBiomes(ClientViewReader in) throws ClientViewProtocolException {
        int count = in.u16();
        if (count > SectionBiomes.CELLS) {
            throw new ClientViewProtocolException("section biome palette exceeds " + SectionBiomes.CELLS + " entries");
        }
        List<String> palette = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            palette.add(in.string());
        }
        try {
            return new SectionBiomes(palette, in.bytes(count > 1 ? SectionBiomes.INDEX_BYTES : 0));
        } catch (IllegalArgumentException invalid) {
            throw new ClientViewProtocolException("invalid section biomes", invalid);
        }
    }

    private static boolean readFlag(ClientViewReader in) throws ClientViewProtocolException {
        int value = in.u8();
        if (value > 1) {
            throw new ClientViewProtocolException("flag byte " + value);
        }
        return value == 1;
    }
}
