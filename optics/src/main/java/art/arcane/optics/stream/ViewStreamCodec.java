package art.arcane.optics.stream;

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

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.math.BlockBox;

public final class ViewStreamCodec {
    private static final int MAX_CELL_BOX_EDGE = 65535;

    private final ViewStreamExtension<?>[] owners;
    private final long[] prerequisites;
    private final long capabilities;

    public ViewStreamCodec(List<ViewStreamExtension<?>> extensions) {
        this.owners = new ViewStreamExtension<?>[ViewStreamLimits.MAX_MESSAGE_ID + 1];
        this.prerequisites = new long[ViewStreamCapability.EXTENSION_BITS];
        long offered = ViewStreamCapability.NONE;
        for (ViewStreamExtension<?> extension : extensions) {
            register(extension);
            offered = claimCapabilities(extension, offered);
        }
        this.capabilities = offered;
    }

    public long capabilities() {
        return capabilities;
    }

    public long settle(long caps) {
        long settled = caps & ViewStreamCapability.ALL;
        boolean changed = true;
        while (changed) {
            changed = false;
            long extensions = settled & ViewStreamCapability.EXTENSIONS;
            while (extensions != 0L) {
                int bit = Long.numberOfTrailingZeros(extensions);
                extensions &= extensions - 1L;
                long required = prerequisites[bit - ViewStreamCapability.FIRST_EXTENSION_BIT];
                if ((settled & required) != required) {
                    settled &= ~(1L << bit);
                    changed = true;
                }
            }
        }
        return settled;
    }

    public boolean clientbound(ViewStreamMessage message) {
        return switch (message) {
            case ViewStreamMessage.Projection projection -> projection.type().isClientbound();
            case ViewStreamMessage.Extension extension -> {
                ViewStreamExtension<?> owner = owner(extension.id());
                yield owner != null && owner.clientbound(extension.id());
            }
        };
    }

    public boolean serverbound(ViewStreamMessage message) {
        return switch (message) {
            case ViewStreamMessage.Projection projection -> projection.type().isServerbound();
            case ViewStreamMessage.Extension extension -> {
                ViewStreamExtension<?> owner = owner(extension.id());
                yield owner != null && owner.serverbound(extension.id());
            }
        };
    }

    public String name(ViewStreamMessage message) {
        return name(message.id());
    }

    public String name(int id) {
        ViewStreamMessageType type = ViewStreamMessageType.byId(id);
        if (type != null) {
            return type.name();
        }
        ViewStreamExtension<?> extension = owner(id);
        return extension == null ? "UNKNOWN(" + id + ")" : extension.name(id);
    }

    public byte[] encodeS2C(ViewStreamMessage message, int seq, int flags) throws ViewStreamProtocolException {
        return encodeS2C(message, seq, flags, false);
    }

    public byte[] encodeS2C(ViewStreamMessage message, int seq, int flags, boolean deflate) throws ViewStreamProtocolException {
        if (!clientbound(message)) {
            throw new ViewStreamProtocolException(name(message) + " is not clientbound");
        }
        if ((flags & ~(ViewStreamLimits.FLAG_LAST | ViewStreamLimits.FLAG_RESERVED)) != 0) {
            throw new ViewStreamProtocolException("caller flags " + flags + " are not allowed");
        }
        ViewStreamWriter body = new ViewStreamWriter(256);
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
            throw new ViewStreamProtocolException(name(message) + " frame of " + (bodyLength + ViewStreamLimits.S2C_HEADER_BYTES)
                + " bytes exceeds the hard cap");
        }
        ViewStreamWriter out = new ViewStreamWriter(ViewStreamLimits.S2C_HEADER_BYTES + bodyLength);
        out.u8(message.id());
        out.i32(seq);
        out.u8(outFlags);
        if (payloadBody == null) {
            out.bytes(raw, 0, rawLength);
        } else {
            out.bytes(payloadBody);
        }
        return out.toByteArray();
    }

    public byte[] encodeC2S(ViewStreamMessage message) throws ViewStreamProtocolException {
        if (!serverbound(message)) {
            throw new ViewStreamProtocolException(name(message) + " is not serverbound");
        }
        ViewStreamWriter out = new ViewStreamWriter(64);
        out.u8(message.id());
        writeBody(out, message);
        if (out.size() > ViewStreamLimits.MAX_C2S_BYTES) {
            throw new ViewStreamProtocolException(name(message) + " payload of " + out.size() + " bytes exceeds the C2S cap");
        }
        return out.toByteArray();
    }

    public S2CFrame decodeS2C(byte[] payload, long caps) throws ViewStreamProtocolException {
        return decodeS2C(payload, 0, payload.length, caps);
    }

    public S2CFrame decodeS2C(byte[] payload, int offset, int length, long caps) throws ViewStreamProtocolException {
        if (length > ViewStreamLimits.HARD_MAX_FRAME_BYTES) {
            throw new ViewStreamProtocolException("S2C payload of " + length + " bytes exceeds the hard cap");
        }
        ViewStreamReader header = new ViewStreamReader(payload, offset, length);
        int id = header.u8();
        ViewStreamMessageType type = ViewStreamMessageType.byId(id);
        ViewStreamExtension<?> extension = type == null ? owner(id) : null;
        if (type == null ? extension == null || !extension.clientbound(id) : !type.isClientbound()) {
            throw new ViewStreamProtocolException("unknown clientbound message type " + id);
        }
        int seq = header.i32();
        int flags = header.u8();
        if ((flags & ~ViewStreamLimits.FLAG_MASK) != 0) {
            throw new ViewStreamProtocolException("unknown frame flags " + flags);
        }
        ViewStreamReader body;
        if ((flags & ViewStreamLimits.FLAG_DEFLATED) != 0) {
            byte[] inflated = inflate(payload, offset + header.position(), header.remaining(), ViewStreamLimits.HARD_MAX_FRAME_BYTES);
            body = new ViewStreamReader(inflated);
        } else {
            body = new ViewStreamReader(payload, offset + header.position(), header.remaining());
        }
        ViewStreamMessage message = type == null ? readExtension(extension, id, body) : readProjection(body, type, caps);
        body.expectEnd();
        return new S2CFrame(seq, flags, message);
    }

    public ViewStreamMessage decodeC2S(byte[] payload) throws ViewStreamProtocolException {
        return decodeC2S(payload, 0, payload.length);
    }

    public ViewStreamMessage decodeC2S(byte[] payload, int offset, int length) throws ViewStreamProtocolException {
        if (length > ViewStreamLimits.MAX_C2S_BYTES) {
            throw new ViewStreamProtocolException("C2S payload of " + length + " bytes exceeds the cap");
        }
        ViewStreamReader in = new ViewStreamReader(payload, offset, length);
        int id = in.u8();
        ViewStreamMessageType type = ViewStreamMessageType.byId(id);
        ViewStreamExtension<?> extension = type == null ? owner(id) : null;
        if (type == null ? extension == null || !extension.serverbound(id) : !type.isServerbound()) {
            throw new ViewStreamProtocolException("unknown serverbound message type " + id);
        }
        ViewStreamMessage message = type == null ? readExtension(extension, id, in) : readProjection(in, type, ViewStreamCapability.NONE);
        in.expectEnd();
        return message;
    }

    public byte[] encodeBody(ViewStreamMessage message) throws ViewStreamProtocolException {
        ViewStreamWriter out = new ViewStreamWriter(256);
        writeBody(out, message);
        return out.toByteArray();
    }

    public void writeBody(ViewStreamWriter out, ViewStreamMessage message) throws ViewStreamProtocolException {
        switch (message) {
            case ViewStreamMessage.Projection projection -> writeProjection(out, projection);
            case ViewStreamMessage.Extension extension -> writeExtension(out, extension);
        }
    }

    public static byte[] projectionBody(ViewStreamMessage.Projection message) throws ViewStreamProtocolException {
        ViewStreamWriter out = new ViewStreamWriter(256);
        writeProjection(out, message);
        return out.toByteArray();
    }

    public List<ViewStreamMessage.Extension> coalesce(List<ViewStreamMessage.Extension> messages) throws ViewStreamProtocolException {
        List<ViewStreamMessage.Extension> out = new ArrayList<ViewStreamMessage.Extension>(messages.size());
        int from = 0;
        while (from < messages.size()) {
            ViewStreamExtension<?> extension = owner(messages.get(from).id());
            if (extension == null) {
                throw new ViewStreamProtocolException("no extension owns message " + messages.get(from).id());
            }
            int to = from + 1;
            while (to < messages.size() && owner(messages.get(to).id()) == extension) {
                to++;
            }
            coalesceRun(extension, messages.subList(from, to), out);
            from = to;
        }
        return out;
    }

    public static void writePatchOp(ViewStreamWriter out, ViewStreamMessage.PatchOp op) throws ViewStreamProtocolException {
        out.u16(op.brickIndex());
        out.u8(op.op());
        switch (op) {
            case ViewStreamMessage.FullOp full -> BrickCodec.write(out, full.brick());
            case ViewStreamMessage.SparseOp sparse -> {
                int[] cells = sparse.cellIndices();
                int[] ids = sparse.paletteIds();
                out.u16(cells.length);
                for (int i = 0; i < cells.length; i++) {
                    out.u16(cells[i]);
                    out.varint(ids[i]);
                }
            }
            case ViewStreamMessage.ClearOp clear -> {
            }
        }
    }

    public static ViewStreamMessage.PatchOp readPatchOp(ViewStreamReader in) throws ViewStreamProtocolException {
        int brickIndex = in.u16();
        int op = in.u8();
        return switch (op) {
            case ViewStreamMessage.PatchOp.OP_FULL -> {
                Brick brick = BrickCodec.read(in);
                if (brick.brickIndex() != brickIndex) {
                    throw new ViewStreamProtocolException("FULL op brick index mismatch");
                }
                yield new ViewStreamMessage.FullOp(brick);
            }
            case ViewStreamMessage.PatchOp.OP_SPARSE -> {
                int count = in.checkedCount(in.u16(), ViewStreamLimits.BRICK_CELLS, 3);
                int[] cells = new int[count];
                int[] ids = new int[count];
                for (int i = 0; i < count; i++) {
                    cells[i] = in.u16();
                    if (cells[i] >= ViewStreamLimits.BRICK_CELLS) {
                        throw new ViewStreamProtocolException("sparse cell outside the brick");
                    }
                    ids[i] = in.varint(ViewStreamLimits.MAX_SESSION_PALETTE_SIZE - 1);
                }
                yield new ViewStreamMessage.SparseOp(brickIndex, cells, ids);
            }
            case ViewStreamMessage.PatchOp.OP_CLEAR -> new ViewStreamMessage.ClearOp(brickIndex);
            default -> throw new ViewStreamProtocolException("unknown patch op " + op);
        };
    }

    public static void writeGeometry(ViewStreamWriter out, ApertureDescriptor geometry, int depth) throws ViewStreamProtocolException {
        if (depth >= ViewStreamLimits.MAX_GEOMETRY_DEPTH) {
            throw new ViewStreamProtocolException("portal geometry nested deeper than " + ViewStreamLimits.MAX_GEOMETRY_DEPTH);
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
            throw new ViewStreamProtocolException("aperture mask of " + mask.length + " words");
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
            throw new ViewStreamProtocolException("portal geometry with " + nested.size() + " nested portals");
        }
        out.u8(nested.size());
        for (ApertureDescriptor child : nested) {
            writeGeometry(out, child, depth + 1);
        }
    }

    public static ApertureDescriptor readGeometry(ViewStreamReader in, int depth) throws ViewStreamProtocolException {
        if (depth >= ViewStreamLimits.MAX_GEOMETRY_DEPTH) {
            throw new ViewStreamProtocolException("portal geometry nested deeper than " + ViewStreamLimits.MAX_GEOMETRY_DEPTH);
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

    public static byte[] inflate(byte[] data, int offset, int length, int maxOutput) throws ViewStreamProtocolException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxOutput, Math.max(256, length * 4)));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int produced = inflater.inflate(buffer);
                if (produced == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new ViewStreamProtocolException("truncated deflate stream");
                    }
                    continue;
                }
                if (out.size() + produced > maxOutput) {
                    throw new ViewStreamProtocolException("inflated frame exceeds " + maxOutput + " bytes");
                }
                out.write(buffer, 0, produced);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new ViewStreamProtocolException("corrupt deflate stream", e);
        } finally {
            inflater.end();
        }
    }

    public static byte[] entityBytes(EntitySnapshot visual) throws ViewStreamProtocolException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        DataOutputStream out = new DataOutputStream(buffer);
        try {
            visual.write(out);
            out.flush();
        } catch (IOException e) {
            throw new ViewStreamProtocolException("entity visual encode failed", e);
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length > ViewStreamLimits.MAX_ENTITY_VISUAL_BYTES) {
            throw new ViewStreamProtocolException("entity visual of " + bytes.length + " bytes exceeds the cap");
        }
        return bytes;
    }

    public static EntitySnapshot entityFromBytes(byte[] bytes) throws ViewStreamProtocolException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            EntitySnapshot visual = EntitySnapshot.read(in);
            if (in.available() != 0) {
                throw new ViewStreamProtocolException("trailing bytes after entity visual");
            }
            return visual;
        } catch (IOException | RuntimeException e) {
            throw new ViewStreamProtocolException("corrupt entity visual", e);
        }
    }

    private void register(ViewStreamExtension<?> extension) {
        int first = extension.firstId();
        int last = extension.lastId();
        if (first < 0 || last > ViewStreamLimits.MAX_MESSAGE_ID || first > last) {
            throw new IllegalArgumentException("extension id range " + first + ".." + last + " is invalid");
        }
        for (int id = first; id <= last; id++) {
            if (ViewStreamMessageType.byId(id) != null) {
                throw new IllegalArgumentException("extension id range " + first + ".." + last + " overlaps projection message "
                    + ViewStreamMessageType.byId(id));
            }
            if (owners[id] != null) {
                throw new IllegalArgumentException("extension id range " + first + ".." + last + " overlaps the extension owning "
                    + owners[id].firstId() + ".." + owners[id].lastId());
            }
        }
        for (int id = first; id <= last; id++) {
            owners[id] = extension;
        }
    }

    private long claimCapabilities(ViewStreamExtension<?> extension, long claimed) {
        long declared = extension.capabilities();
        if ((declared & ~ViewStreamCapability.EXTENSIONS) != 0L) {
            throw new IllegalArgumentException("extension " + extension.firstId() + ".." + extension.lastId()
                + " declares capabilities outside the extension range: 0x" + Long.toHexString(declared & ~ViewStreamCapability.EXTENSIONS));
        }
        if ((declared & claimed) != 0L) {
            throw new IllegalArgumentException("extension " + extension.firstId() + ".." + extension.lastId()
                + " declares capabilities another extension owns: 0x" + Long.toHexString(declared & claimed));
        }
        long remaining = declared;
        while (remaining != 0L) {
            int bit = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            long required = extension.requires(1L << bit) & ViewStreamCapability.ALL;
            prerequisites[bit - ViewStreamCapability.FIRST_EXTENSION_BIT] = required;
        }
        return claimed | declared;
    }

    private ViewStreamExtension<?> owner(int id) {
        return id < 0 || id >= owners.length ? null : owners[id];
    }

    private void writeExtension(ViewStreamWriter out, ViewStreamMessage.Extension message) throws ViewStreamProtocolException {
        ViewStreamExtension<?> extension = owner(message.id());
        if (extension == null) {
            throw new ViewStreamProtocolException("no extension owns message " + message.id());
        }
        encodeExtension(extension, message, out);
    }

    private static <X> void encodeExtension(ViewStreamExtension<X> extension, ViewStreamMessage.Extension message, ViewStreamWriter out)
        throws ViewStreamProtocolException {
        if (!extension.type().isInstance(message.payload())) {
            throw new ViewStreamProtocolException("extension message " + message.id() + " carries " + message.payload().getClass().getName());
        }
        X payload = extension.type().cast(message.payload());
        if (extension.id(payload) != message.id()) {
            throw new ViewStreamProtocolException("extension message " + message.id() + " carries payload for " + extension.id(payload));
        }
        extension.encode(payload, out);
    }

    private static <X> ViewStreamMessage.Extension readExtension(ViewStreamExtension<X> extension, int id, ViewStreamReader in)
        throws ViewStreamProtocolException {
        X payload;
        try {
            payload = extension.decode(id, in);
        } catch (IllegalArgumentException invalid) {
            throw new ViewStreamProtocolException("invalid extension message " + id, invalid);
        }
        if (payload == null || extension.id(payload) != id) {
            throw new ViewStreamProtocolException("extension message " + id + " decoded to the wrong payload");
        }
        return new ViewStreamMessage.Extension(id, payload);
    }

    private static <X> void coalesceRun(ViewStreamExtension<X> extension, List<ViewStreamMessage.Extension> run,
                                        List<ViewStreamMessage.Extension> out) throws ViewStreamProtocolException {
        List<X> payloads = new ArrayList<X>(run.size());
        for (ViewStreamMessage.Extension message : run) {
            if (!extension.type().isInstance(message.payload())) {
                throw new ViewStreamProtocolException("extension message " + message.id() + " carries " + message.payload().getClass().getName());
            }
            payloads.add(extension.type().cast(message.payload()));
        }
        for (X payload : extension.coalesce(payloads)) {
            out.add(extension.wrap(payload));
        }
    }

    private static void writeProjection(ViewStreamWriter out, ViewStreamMessage.Projection message) throws ViewStreamProtocolException {
        switch (message) {
            case ViewStreamMessage.Offer m -> {
                out.u16(m.wire());
                out.i32(m.mcDataVersion());
                out.i64(m.serverCaps());
                out.i32(m.maxFrameBytes());
                out.i64(m.zeroCopyNonce());
            }
            case ViewStreamMessage.Hello m -> {
                out.u16(m.wire());
                out.i32(m.mcDataVersion());
                out.i64(m.clientCaps());
                out.i32(m.maxFrameBytes());
                out.u16(m.plateMemoryMb());
                out.i64(m.zeroCopyNonceEcho());
                out.string(m.brandTag());
            }
            case ViewStreamMessage.Accept m -> {
                out.i32(m.sessionId());
                out.i64(m.caps());
                out.u8(m.tickRate());
                out.i32(m.maxFrameBytes());
                out.i64(m.hashSalt());
                out.u8(m.ackWindowFrames());
            }
            case ViewStreamMessage.Decline m -> out.u8(m.reason().ordinal());
            case ViewStreamMessage.Palette m -> {
                List<ViewStreamMessage.PaletteEntry> entries = m.entries();
                if (entries.size() > ViewStreamLimits.MAX_PALETTE_ENTRIES_PER_MESSAGE) {
                    throw new ViewStreamProtocolException("palette message with " + entries.size() + " entries");
                }
                out.varint(entries.size());
                for (ViewStreamMessage.PaletteEntry entry : entries) {
                    out.varint(entry.id());
                    out.string(entry.state());
                }
            }
            case ViewStreamMessage.Portal m -> {
                out.varint(m.portalKey());
                out.i32(m.geometryRevision());
                writeGeometry(out, m.geometry(), 0);
            }
            case ViewStreamMessage.PortalDrop m -> out.varint(m.portalKey());
            case ViewStreamMessage.MeshBegin m -> {
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
            case ViewStreamMessage.MeshSection m -> {
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
            case ViewStreamMessage.MeshDrop m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sectionX());
                out.i32(m.sectionY());
                out.i32(m.sectionZ());
            }
            case ViewStreamMessage.MeshAck m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sectionX());
                out.i32(m.sectionY());
                out.i32(m.sectionZ());
                out.i32(m.revision());
            }
            case ViewStreamMessage.MeshCached m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sequence());
                out.u8(m.available() ? 1 : 0);
                out.u16(m.claims().size());
                for (ViewStreamMessage.MeshClaim claim : m.claims()) {
                    out.i32(claim.x());
                    out.i32(claim.y());
                    out.i32(claim.z());
                    out.i64(claim.hash());
                }
            }
            case ViewStreamMessage.MeshReuse m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sectionX());
                out.i32(m.sectionY());
                out.i32(m.sectionZ());
                out.i32(m.revision());
                out.i64(m.hash());
            }
            case ViewStreamMessage.MeshLocal m -> {
                out.varint(m.portalKey());
                out.i32(m.generation());
                out.i32(m.sequence());
                out.u8(m.available() ? 1 : 0);
                out.u16(m.sections().size());
                for (ViewStreamMessage.MeshCoordinate section : m.sections()) {
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
            case ViewStreamMessage.PlateBegin m -> {
                out.varint(m.portalKey());
                out.i32(m.plateRevision());
                PlateSectionBox sections = m.sections();
                out.i32(sections.minSectionX());
                out.i32(sections.minSectionY());
                out.i32(sections.minSectionZ());
                out.u8(sections.sizeX());
                out.u8(sections.sizeY());
                out.u8(sections.sizeZ());
                BlockBox cells = m.cells();
                if (cells.sizeX() > MAX_CELL_BOX_EDGE || cells.sizeY() > MAX_CELL_BOX_EDGE || cells.sizeZ() > MAX_CELL_BOX_EDGE) {
                    throw new ViewStreamProtocolException("cell box edge exceeds " + MAX_CELL_BOX_EDGE);
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
            case ViewStreamMessage.PlateBricks m -> {
                List<Brick> bricks = m.bricks();
                if (bricks.size() > ViewStreamLimits.MAX_BRICKS_PER_PLATE) {
                    throw new ViewStreamProtocolException("plate bricks message with " + bricks.size() + " bricks");
                }
                out.varint(m.portalKey());
                out.i32(m.plateRevision());
                out.u16(bricks.size());
                for (Brick brick : bricks) {
                    BrickCodec.write(out, brick);
                }
            }
            case ViewStreamMessage.PlateEnd m -> {
                out.varint(m.portalKey());
                out.i32(m.plateRevision());
            }
            case ViewStreamMessage.PlatePatch m -> {
                List<ViewStreamMessage.PatchOp> ops = m.ops();
                if (ops.size() > ViewStreamLimits.MAX_PATCH_OPS) {
                    throw new ViewStreamProtocolException("plate patch with " + ops.size() + " ops");
                }
                out.varint(m.portalKey());
                out.i32(m.fromRevision());
                out.i32(m.toRevision());
                out.u16(ops.size());
                for (ViewStreamMessage.PatchOp op : ops) {
                    writePatchOp(out, op);
                }
            }
            case ViewStreamMessage.PlateHandle m -> {
                out.varint(m.portalKey());
                out.i32(m.plateRevision());
                out.i64(m.handle());
            }
            case ViewStreamMessage.BrickMiss m -> {
                out.u8(m.plates().size());
                for (ViewStreamMessage.BrickMiss.Plate plate : m.plates()) {
                    out.varint(plate.portalKey());
                    out.i32(plate.plateRevision());
                    out.varint(plate.bitset().length);
                    out.longs(plate.bitset());
                }
            }
            case ViewStreamMessage.PlateRefused m -> {
                out.varint(m.portalKey());
                out.i32(m.plateRevision());
            }
            case ViewStreamMessage.EntityEvent m -> {
                out.varint(m.portalKey());
                out.i32(m.eventSeq());
                out.i64(m.entityId().getMostSignificantBits());
                out.i64(m.entityId().getLeastSignificantBits());
                out.u8(m.hurt() ? 1 : 0);
                out.u8(m.animation());
                out.f32(m.yaw());
            }
            case ViewStreamMessage.EntitySelf m -> {
                out.i64(m.projectedId().getMostSignificantBits());
                out.i64(m.projectedId().getLeastSignificantBits());
            }
            case ViewStreamMessage.EntityFrame m -> {
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
            case ViewStreamMessage.Environment m -> {
                out.varint(m.portalKey());
                EnvironmentStateCodec.write(out, m.environment());
            }
            case ViewStreamMessage.Atmosphere m -> {
                out.varint(m.portalKey());
                out.i64(m.dayTime());
                out.f32(m.rain());
                out.f32(m.thunder());
                out.u8(m.flags());
            }
            case ViewStreamMessage.SessionReset m -> out.u8(m.reason().ordinal());
            case ViewStreamMessage.Ack m -> {
                out.i32(m.seq());
                out.i32(m.clientTick());
                out.i32(m.appliedCells());
            }
            case ViewStreamMessage.ViewStats m -> {
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

    private static ViewStreamMessage.Projection readProjection(ViewStreamReader in, ViewStreamMessageType type, long caps) throws ViewStreamProtocolException {
        return switch (type) {
            case OFFER -> new ViewStreamMessage.Offer(in.u16(), in.i32(), in.i64(), in.i32(), in.i64());
            case HELLO -> new ViewStreamMessage.Hello(in.u16(), in.i32(), in.i64(), in.i32(), in.u16(), in.i64(), in.string());
            case ACCEPT -> new ViewStreamMessage.Accept(in.i32(), in.i64(), in.u8(), in.i32(), in.i64(), in.u8());
            case DECLINE -> {
                ViewStreamMessage.DeclineReason reason = ViewStreamMessage.DeclineReason.byId(in.u8());
                if (reason == null) {
                    throw new ViewStreamProtocolException("unknown decline reason");
                }
                yield new ViewStreamMessage.Decline(reason);
            }
            case PALETTE -> {
                int count = in.checkedCount(in.varint(), ViewStreamLimits.MAX_PALETTE_ENTRIES_PER_MESSAGE, 2);
                List<ViewStreamMessage.PaletteEntry> entries = new ArrayList<ViewStreamMessage.PaletteEntry>(count);
                for (int i = 0; i < count; i++) {
                    int id = in.varint(ViewStreamLimits.MAX_SESSION_PALETTE_SIZE - 1);
                    entries.add(new ViewStreamMessage.PaletteEntry(id, in.string()));
                }
                yield new ViewStreamMessage.Palette(entries);
            }
            case PORTAL -> {
                int portalKey = in.varint();
                int revision = in.i32();
                yield new ViewStreamMessage.Portal(portalKey, revision, readGeometry(in, 0));
            }
            case PORTAL_DROP -> new ViewStreamMessage.PortalDrop(in.varint());
            case MESH_BEGIN -> {
                int portalKey = in.varint();
                int generation = in.i32();
                BlockBox bounds = new BlockBox(in.i32(), in.i32(), in.i32(), in.u16(), in.u16(), in.u16());
                int limit = in.varint(Integer.MAX_VALUE);
                if (limit == 0 || bounds.cells() == 0) {
                    throw new ViewStreamProtocolException("mesh view requires nonempty bounds and a resident budget");
                }
                try {
                    yield new ViewStreamMessage.MeshBegin(portalKey, generation, bounds, limit);
                } catch (IllegalArgumentException invalid) {
                    throw new ViewStreamProtocolException("invalid mesh section capacity", invalid);
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
                    throw new ViewStreamProtocolException("mesh section brick index must be zero");
                }
                yield new ViewStreamMessage.MeshSection(portalKey, generation, sectionX, sectionY, sectionZ, revision, backing, brick, readSectionBiomes(in));
            }
            case MESH_DROP -> new ViewStreamMessage.MeshDrop(in.varint(), in.i32(), in.i32(), in.i32(), in.i32());
            case MESH_ACK -> new ViewStreamMessage.MeshAck(in.varint(), in.i32(), in.i32(), in.i32(), in.i32(), in.i32());
            case MESH_CACHED -> {
                int portalKey = in.varint();
                int generation = in.i32();
                int sequence = in.i32();
                int available = in.u8();
                int count = in.u16();
                if (generation <= 0 || sequence <= 0 || available > 1 || count > ViewStreamMessage.MeshCached.MAX_CLAIMS) {
                    throw new ViewStreamProtocolException("Invalid mesh cache claims");
                }
                List<ViewStreamMessage.MeshClaim> claims = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    claims.add(new ViewStreamMessage.MeshClaim(in.i32(), in.i32(), in.i32(), in.i64()));
                }
                yield new ViewStreamMessage.MeshCached(portalKey, generation, sequence, available == 1, claims);
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
                    throw new ViewStreamProtocolException("Invalid mesh reuse acknowledgment");
                }
                yield new ViewStreamMessage.MeshReuse(portalKey, generation, x, y, z, revision, hash);
            }
            case MESH_LOCAL -> {
                int portalKey = in.varint();
                int generation = in.i32();
                int sequence = in.i32();
                int available = in.u8();
                int sectionCount = in.u16();
                if (generation <= 0 || sequence <= 0 || available > 1 || sectionCount > ViewStreamMessage.MeshLocal.MAX_SECTIONS) {
                    throw new ViewStreamProtocolException("Invalid local mesh availability");
                }
                List<ViewStreamMessage.MeshCoordinate> sections = new ArrayList<>(sectionCount);
                for (int index = 0; index < sectionCount; index++) {
                    sections.add(new ViewStreamMessage.MeshCoordinate(in.i32(), in.i32(), in.i32()));
                }
                int entityCount = in.u16();
                if (entityCount > ViewStreamMessage.MeshLocal.MAX_ENTITIES) {
                    throw new ViewStreamProtocolException("Too many local mesh entities");
                }
                List<UUID> entities = new ArrayList<>(entityCount);
                for (int index = 0; index < entityCount; index++) {
                    entities.add(new UUID(in.i64(), in.i64()));
                }
                yield new ViewStreamMessage.MeshLocal(portalKey, generation, sequence, available == 1, sections, entities);
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
                    throw new ViewStreamProtocolException("section box exceeds the brick cap");
                }
                PlateSectionBox sections = new PlateSectionBox(minSectionX, minSectionY, minSectionZ, sizeX, sizeY, sizeZ);
                int minX = in.i32();
                int minY = in.i32();
                int minZ = in.i32();
                BlockBox cells = new BlockBox(minX, minY, minZ, in.u16(), in.u16(), in.u16());
                int backingState = in.varint(ViewStreamLimits.MAX_SESSION_PALETTE_SIZE - 1);
                int brickCount = in.u16();
                if (brickCount != sections.brickCount()) {
                    throw new ViewStreamProtocolException("brick count " + brickCount + " does not match the section box");
                }
                long[] hashes = null;
                if (ViewStreamCapability.BRICK_CACHE.in(caps) && (brickCount == 0 || in.remaining() > 0)) {
                    if (in.remaining() != brickCount * Long.BYTES) {
                        throw new ViewStreamProtocolException("brick hash manifest does not match the brick count");
                    }
                    hashes = in.longs(brickCount);
                }
                yield new ViewStreamMessage.PlateBegin(portalKey, revision, sections, cells, backingState, brickCount, hashes);
            }
            case PLATE_BRICKS -> {
                int portalKey = in.varint();
                int revision = in.i32();
                int count = in.checkedCount(in.u16(), ViewStreamLimits.MAX_BRICKS_PER_PLATE, 5);
                List<Brick> bricks = new ArrayList<Brick>(count);
                for (int i = 0; i < count; i++) {
                    bricks.add(BrickCodec.read(in));
                }
                yield new ViewStreamMessage.PlateBricks(portalKey, revision, bricks);
            }
            case PLATE_END -> new ViewStreamMessage.PlateEnd(in.varint(), in.i32());
            case PLATE_PATCH -> {
                int portalKey = in.varint();
                int from = in.i32();
                int to = in.i32();
                int count = in.checkedCount(in.u16(), ViewStreamLimits.MAX_PATCH_OPS, 3);
                List<ViewStreamMessage.PatchOp> ops = new ArrayList<ViewStreamMessage.PatchOp>(count);
                for (int i = 0; i < count; i++) {
                    ops.add(readPatchOp(in));
                }
                yield new ViewStreamMessage.PlatePatch(portalKey, from, to, ops);
            }
            case PLATE_HANDLE -> new ViewStreamMessage.PlateHandle(in.varint(), in.i32(), in.i64());
            case BRICK_MISS -> {
                int count = in.checkedCount(in.u8(), ViewStreamLimits.MAX_BRICK_MISS_PLATES, 6);
                if (count == 0) {
                    throw new ViewStreamProtocolException("brick miss without plates");
                }
                List<ViewStreamMessage.BrickMiss.Plate> plates = new ArrayList<ViewStreamMessage.BrickMiss.Plate>(count);
                for (int i = 0; i < count; i++) {
                    int portalKey = in.varint();
                    int revision = in.i32();
                    int words = in.checkedCount(in.varint(), ViewStreamLimits.MAX_BRICK_MISS_WORDS, 8);
                    plates.add(new ViewStreamMessage.BrickMiss.Plate(portalKey, revision, in.longs(words)));
                }
                yield new ViewStreamMessage.BrickMiss(plates);
            }
            case ENTITY_EVENT -> {
                int portalKey = in.varint();
                int eventSeq = in.i32();
                UUID entityId = new UUID(in.i64(), in.i64());
                boolean hurt = readFlag(in);
                int animation = in.u8();
                float yaw = in.f32();
                if (!Float.isFinite(yaw) || !hurt && animation != 0 && animation != 2 && animation != 3 && animation != 4 && animation != 5) {
                    throw new ViewStreamProtocolException("invalid entity event");
                }
                yield new ViewStreamMessage.EntityEvent(portalKey, eventSeq, entityId, hurt, animation, yaw);
            }
            case ENTITY_SELF -> new ViewStreamMessage.EntitySelf(new UUID(in.i64(), in.i64()));
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
                    yield new ViewStreamMessage.EntityFrame(portalKey, seq, entities, List.of(), false);
                }
                int present = in.checkedCount(presentCount, ViewStreamLimits.MAX_PRESENT_IDS_PER_FRAME, 16);
                List<UUID> presentIds = new ArrayList<UUID>(present);
                for (int i = 0; i < present; i++) {
                    long most = in.i64();
                    presentIds.add(new UUID(most, in.i64()));
                }
                yield new ViewStreamMessage.EntityFrame(portalKey, seq, entities, presentIds, true);
            }
            case ENVIRONMENT -> new ViewStreamMessage.Environment(in.varint(), EnvironmentStateCodec.read(in));
            case ATMOSPHERE -> new ViewStreamMessage.Atmosphere(in.varint(), in.i64(), in.f32(), in.f32(), in.u8());
            case SESSION_RESET -> {
                ViewStreamMessage.ResetReason reason = ViewStreamMessage.ResetReason.byId(in.u8());
                if (reason == null) {
                    throw new ViewStreamProtocolException("unknown reset reason");
                }
                yield new ViewStreamMessage.SessionReset(reason);
            }
            case ACK -> new ViewStreamMessage.Ack(in.i32(), in.i32(), in.i32());
            case VIEW_STATS -> new ViewStreamMessage.ViewStats(in.i32(), in.u16(), in.i32(), in.u16(), in.u16(), in.u16(), in.u16());
            case PLATE_REFUSED -> new ViewStreamMessage.PlateRefused(in.varint(), in.i32());
        };
    }

    private static SectionBiomes readSectionBiomes(ViewStreamReader in) throws ViewStreamProtocolException {
        int count = in.u16();
        if (count > SectionBiomes.CELLS) {
            throw new ViewStreamProtocolException("section biome palette exceeds " + SectionBiomes.CELLS + " entries");
        }
        List<String> palette = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            palette.add(in.string());
        }
        try {
            return new SectionBiomes(palette, in.bytes(count > 1 ? SectionBiomes.INDEX_BYTES : 0));
        } catch (IllegalArgumentException invalid) {
            throw new ViewStreamProtocolException("invalid section biomes", invalid);
        }
    }

    private static boolean readFlag(ViewStreamReader in) throws ViewStreamProtocolException {
        int value = in.u8();
        if (value > 1) {
            throw new ViewStreamProtocolException("flag byte " + value);
        }
        return value == 1;
    }

    public record S2CFrame(int seq, int flags, ViewStreamMessage message) {
        public boolean last() {
            return (flags & ViewStreamLimits.FLAG_LAST) != 0;
        }
    }
}
