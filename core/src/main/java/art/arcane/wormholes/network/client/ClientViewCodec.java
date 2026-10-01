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

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.PlateBox;

public final class ClientViewCodec {
    private static final int MAX_CELL_BOX_EDGE = 65535;

    private ClientViewCodec() {
    }

    public record S2CFrame(int seq, int flags, ClientViewMessage message) {
        public boolean last() {
            return (flags & ClientViewProtocol.FLAG_LAST) != 0;
        }
    }

    public static byte[] encodeS2C(ClientViewMessage message, int seq, int flags) throws ClientViewProtocolException {
        return encodeS2C(message, seq, flags, false);
    }

    public static byte[] encodeS2C(ClientViewMessage message, int seq, int flags, boolean deflate) throws ClientViewProtocolException {
        if (!message.type().isClientbound()) {
            throw new ClientViewProtocolException(message.type() + " is not clientbound");
        }
        if ((flags & ~(ClientViewProtocol.FLAG_LAST | ClientViewProtocol.FLAG_RESERVED)) != 0) {
            throw new ClientViewProtocolException("caller flags " + flags + " are not allowed");
        }
        ClientViewWriter body = new ClientViewWriter(256);
        writeBody(body, message);
        byte[] raw = body.rawBuffer();
        int rawLength = body.size();
        int outFlags = flags;
        byte[] payloadBody = null;
        if (deflate && rawLength >= ClientViewProtocol.DEFLATE_THRESHOLD_BYTES) {
            byte[] deflated = deflate(raw, rawLength);
            if (deflated.length < rawLength) {
                payloadBody = deflated;
                outFlags |= ClientViewProtocol.FLAG_DEFLATED;
            }
        }
        int bodyLength = payloadBody == null ? rawLength : payloadBody.length;
        if (bodyLength + ClientViewProtocol.S2C_HEADER_BYTES > ClientViewProtocol.HARD_MAX_FRAME_BYTES) {
            throw new ClientViewProtocolException(message.type() + " frame of " + (bodyLength + ClientViewProtocol.S2C_HEADER_BYTES)
                + " bytes exceeds the hard cap");
        }
        ClientViewWriter out = new ClientViewWriter(ClientViewProtocol.S2C_HEADER_BYTES + bodyLength);
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
        if (out.size() > ClientViewProtocol.MAX_C2S_BYTES) {
            throw new ClientViewProtocolException(message.type() + " payload of " + out.size() + " bytes exceeds the C2S cap");
        }
        return out.toByteArray();
    }

    public static S2CFrame decodeS2C(byte[] payload, long caps) throws ClientViewProtocolException {
        return decodeS2C(payload, 0, payload.length, caps);
    }

    public static S2CFrame decodeS2C(byte[] payload, int offset, int length, long caps) throws ClientViewProtocolException {
        if (length > ClientViewProtocol.HARD_MAX_FRAME_BYTES) {
            throw new ClientViewProtocolException("S2C payload of " + length + " bytes exceeds the hard cap");
        }
        ClientViewReader header = new ClientViewReader(payload, offset, length);
        ClientViewMessageType type = ClientViewMessageType.byId(header.u8());
        if (type == null || !type.isClientbound()) {
            throw new ClientViewProtocolException("unknown clientbound message type");
        }
        int seq = header.i32();
        int flags = header.u8();
        if ((flags & ~ClientViewProtocol.FLAG_MASK) != 0) {
            throw new ClientViewProtocolException("unknown frame flags " + flags);
        }
        ClientViewReader body;
        if ((flags & ClientViewProtocol.FLAG_DEFLATED) != 0) {
            byte[] inflated = inflate(payload, offset + header.position(), header.remaining(), ClientViewProtocol.HARD_MAX_FRAME_BYTES);
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
        if (length > ClientViewProtocol.MAX_C2S_BYTES) {
            throw new ClientViewProtocolException("C2S payload of " + length + " bytes exceeds the cap");
        }
        ClientViewReader in = new ClientViewReader(payload, offset, length);
        ClientViewMessageType type = ClientViewMessageType.byId(in.u8());
        if (type == null || !type.isServerbound()) {
            throw new ClientViewProtocolException("unknown serverbound message type");
        }
        ClientViewMessage message = readBody(in, type, ClientViewCapability.NONE);
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
                if (entries.size() > ClientViewProtocol.MAX_PALETTE_ENTRIES_PER_MESSAGE) {
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
                if (bricks.size() > ClientViewProtocol.MAX_BRICKS_PER_PLATE) {
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
                if (ops.size() > ClientViewProtocol.MAX_PATCH_OPS) {
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
            case ClientViewMessage.EntityFrame m -> {
                out.varint(m.portalKey());
                out.i32(m.entitySeq());
                out.u8(m.entities().size());
                for (EntityVisual visual : m.entities()) {
                    byte[] bytes = entityBytes(visual);
                    out.varint(bytes.length);
                    out.bytes(bytes);
                }
                if (!m.presence()) {
                    out.u16(ClientViewProtocol.PRESENCE_UNCHANGED);
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

    public static ClientViewMessage readBody(ClientViewReader in, ClientViewMessageType type, long caps) throws ClientViewProtocolException {
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
                int count = in.checkedCount(in.varint(), ClientViewProtocol.MAX_PALETTE_ENTRIES_PER_MESSAGE, 2);
                List<ClientViewMessage.PaletteEntry> entries = new ArrayList<ClientViewMessage.PaletteEntry>(count);
                for (int i = 0; i < count; i++) {
                    int id = in.varint(ClientViewProtocol.MAX_SESSION_PALETTE_SIZE - 1);
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
            case PLATE_BEGIN -> {
                int portalKey = in.varint();
                int revision = in.i32();
                int minSectionX = in.i32();
                int minSectionY = in.i32();
                int minSectionZ = in.i32();
                int sizeX = in.u8();
                int sizeY = in.u8();
                int sizeZ = in.u8();
                if ((long) sizeX * sizeY * sizeZ > ClientViewProtocol.MAX_BRICKS_PER_PLATE) {
                    throw new ClientViewProtocolException("section box exceeds the brick cap");
                }
                PlateSectionBox sections = new PlateSectionBox(minSectionX, minSectionY, minSectionZ, sizeX, sizeY, sizeZ);
                int minX = in.i32();
                int minY = in.i32();
                int minZ = in.i32();
                PlateBox cells = new PlateBox(minX, minY, minZ, in.u16(), in.u16(), in.u16());
                int backingState = in.varint(ClientViewProtocol.MAX_SESSION_PALETTE_SIZE - 1);
                int brickCount = in.u16();
                if (brickCount != sections.brickCount()) {
                    throw new ClientViewProtocolException("brick count " + brickCount + " does not match the section box");
                }
                long[] hashes = null;
                if (ClientViewCapability.BRICK_CACHE.in(caps)) {
                    in.require(brickCount * 8);
                    hashes = in.longs(brickCount);
                }
                yield new ClientViewMessage.PlateBegin(portalKey, revision, sections, cells, backingState, brickCount, hashes);
            }
            case PLATE_BRICKS -> {
                int portalKey = in.varint();
                int revision = in.i32();
                int count = in.checkedCount(in.u16(), ClientViewProtocol.MAX_BRICKS_PER_PLATE, 5);
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
                int count = in.checkedCount(in.u16(), ClientViewProtocol.MAX_PATCH_OPS, 3);
                List<ClientViewMessage.PatchOp> ops = new ArrayList<ClientViewMessage.PatchOp>(count);
                for (int i = 0; i < count; i++) {
                    ops.add(readPatchOp(in));
                }
                yield new ClientViewMessage.PlatePatch(portalKey, from, to, ops);
            }
            case PLATE_HANDLE -> new ClientViewMessage.PlateHandle(in.varint(), in.i32(), in.i64());
            case BRICK_MISS -> {
                int count = in.checkedCount(in.u8(), ClientViewProtocol.MAX_BRICK_MISS_PLATES, 6);
                if (count == 0) {
                    throw new ClientViewProtocolException("brick miss without plates");
                }
                List<ClientViewMessage.BrickMiss.Plate> plates = new ArrayList<ClientViewMessage.BrickMiss.Plate>(count);
                for (int i = 0; i < count; i++) {
                    int portalKey = in.varint();
                    int revision = in.i32();
                    int words = in.checkedCount(in.varint(), ClientViewProtocol.MAX_BRICK_MISS_WORDS, 8);
                    plates.add(new ClientViewMessage.BrickMiss.Plate(portalKey, revision, in.longs(words)));
                }
                yield new ClientViewMessage.BrickMiss(plates);
            }
            case ENTITY_FRAME -> {
                int portalKey = in.varint();
                int seq = in.i32();
                int count = in.checkedCount(in.u8(), ClientViewProtocol.MAX_ENTITIES_PER_FRAME, 1);
                List<EntityVisual> entities = new ArrayList<EntityVisual>(count);
                for (int i = 0; i < count; i++) {
                    int length = in.varint(ClientViewProtocol.MAX_ENTITY_VISUAL_BYTES);
                    entities.add(entityFromBytes(in.bytes(length)));
                }
                int presentCount = in.u16();
                if (presentCount == ClientViewProtocol.PRESENCE_UNCHANGED) {
                    yield new ClientViewMessage.EntityFrame(portalKey, seq, entities, List.of(), false);
                }
                int present = in.checkedCount(presentCount, ClientViewProtocol.MAX_PRESENT_IDS_PER_FRAME, 16);
                List<UUID> presentIds = new ArrayList<UUID>(present);
                for (int i = 0; i < present; i++) {
                    long most = in.i64();
                    presentIds.add(new UUID(most, in.i64()));
                }
                yield new ClientViewMessage.EntityFrame(portalKey, seq, entities, presentIds, true);
            }
            case FX -> {
                int portalKey = in.varint();
                int count = in.checkedCount(in.u8(), ClientViewProtocol.MAX_FX_EMITTERS, 37);
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
                int count = in.checkedCount(in.u16(), ClientViewProtocol.BRICK_CELLS, 3);
                int[] cells = new int[count];
                int[] ids = new int[count];
                for (int i = 0; i < count; i++) {
                    cells[i] = in.u16();
                    if (cells[i] >= ClientViewProtocol.BRICK_CELLS) {
                        throw new ClientViewProtocolException("sparse cell outside the brick");
                    }
                    ids[i] = in.varint(ClientViewProtocol.MAX_SESSION_PALETTE_SIZE - 1);
                }
                yield new ClientViewMessage.SparseOp(brickIndex, cells, ids);
            }
            case ClientViewMessage.PatchOp.OP_CLEAR -> new ClientViewMessage.ClearOp(brickIndex);
            default -> throw new ClientViewProtocolException("unknown patch op " + op);
        };
    }

    public static void writeGeometry(ClientViewWriter out, ClientPortalGeometry geometry, int depth) throws ClientViewProtocolException {
        if (depth >= ClientViewProtocol.MAX_GEOMETRY_DEPTH) {
            throw new ClientViewProtocolException("portal geometry nested deeper than " + ClientViewProtocol.MAX_GEOMETRY_DEPTH);
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
        if (mask.length > ClientViewProtocol.MAX_APERTURE_MASK_WORDS) {
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
        out.varint(geometry.parentPortalKey());
        out.i64(geometry.targetIdentity());
        List<ClientPortalGeometry> nested = geometry.nested();
        if (nested.size() > ClientViewProtocol.MAX_NESTED_GEOMETRY) {
            throw new ClientViewProtocolException("portal geometry with " + nested.size() + " nested portals");
        }
        out.u8(nested.size());
        for (ClientPortalGeometry child : nested) {
            writeGeometry(out, child, depth + 1);
        }
    }

    public static ClientPortalGeometry readGeometry(ClientViewReader in, int depth) throws ClientViewProtocolException {
        if (depth >= ClientViewProtocol.MAX_GEOMETRY_DEPTH) {
            throw new ClientViewProtocolException("portal geometry nested deeper than " + ClientViewProtocol.MAX_GEOMETRY_DEPTH);
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
        int words = in.checkedCount(in.varint(), ClientViewProtocol.MAX_APERTURE_MASK_WORDS, 8);
        long[] mask = in.longs(words);
        float nearPlanePadding = in.f32();
        float aperturePadding = in.f32();
        float frustumCullingRatio = in.f32();
        int depthBlocks = in.u16();
        int recursionDepth = in.u8();
        int blackoutPolicy = in.u8();
        int blackoutState = in.varint(ClientViewProtocol.MAX_SESSION_PALETTE_SIZE - 1);
        int maskAirPolicy = in.u8();
        int lightingPolicy = in.u8();
        int fidelityFlags = in.u8();
        int kind = in.u8();
        int parentPortalKey = in.varint();
        long targetIdentity = in.i64();
        int nestedCount = in.checkedCount(in.u8(), ClientViewProtocol.MAX_NESTED_GEOMETRY, 40);
        List<ClientPortalGeometry> nested = new ArrayList<ClientPortalGeometry>(nestedCount);
        for (int i = 0; i < nestedCount; i++) {
            nested.add(readGeometry(in, depth + 1));
        }
        return new ClientPortalGeometry(originX, originY, originZ, facing, frontSide, quarterTurns, mirror, apertureWidth, apertureHeight,
            mask, nearPlanePadding, aperturePadding, frustumCullingRatio, depthBlocks, recursionDepth, blackoutPolicy, blackoutState,
            maskAirPolicy, lightingPolicy, fidelityFlags, kind, parentPortalKey, targetIdentity, nested);
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

    public static byte[] entityBytes(EntityVisual visual) throws ClientViewProtocolException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        DataOutputStream out = new DataOutputStream(buffer);
        try {
            visual.write(out);
            out.flush();
        } catch (IOException e) {
            throw new ClientViewProtocolException("entity visual encode failed", e);
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > ClientViewProtocol.MAX_ENTITY_VISUAL_BYTES) {
            throw new ClientViewProtocolException("entity visual of " + bytes.length + " bytes exceeds the cap");
        }
        return bytes;
    }

    public static EntityVisual entityFromBytes(byte[] bytes) throws ClientViewProtocolException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            EntityVisual visual = EntityVisual.read(in);
            if (in.available() != 0) {
                throw new ClientViewProtocolException("trailing bytes after entity visual");
            }
            return visual;
        } catch (IOException | RuntimeException e) {
            throw new ClientViewProtocolException("corrupt entity visual", e);
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
