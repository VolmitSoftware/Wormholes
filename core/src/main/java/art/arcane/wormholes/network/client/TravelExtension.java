package art.arcane.wormholes.network.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentStateCodec;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamExtension;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;

public final class TravelExtension implements ViewStreamExtension<TravelMessage> {
    public static final TravelExtension PREPARED = new TravelExtension(Seamless.NONE);

    private final Seamless seamless;

    public TravelExtension(Seamless seamless) {
        this.seamless = Objects.requireNonNull(seamless, "seamless");
    }

    @Override
    public int firstId() {
        return TravelMessage.FIRST_ID;
    }

    @Override
    public int lastId() {
        return TravelMessage.LAST_ID;
    }

    @Override
    public boolean serverbound(int id) {
        return switch (id) {
            case TravelMessage.TRAVEL_READY, TravelMessage.TRAVEL_CANCEL, TravelMessage.TRAVEL_CROSS, TravelMessage.TRAVEL_CACHED -> true;
            default -> seamless.serverbound(id);
        };
    }

    @Override
    public boolean clientbound(int id) {
        return switch (id) {
            case TravelMessage.TRAVEL_BEGIN, TravelMessage.TRAVEL_CHUNK, TravelMessage.TRAVEL_END, TravelMessage.TRAVEL_COMMIT,
                 TravelMessage.TRAVEL_CANCEL, TravelMessage.TRAVEL_REUSE -> true;
            default -> seamless.clientbound(id);
        };
    }

    @Override
    public Class<TravelMessage> type() {
        return TravelMessage.class;
    }

    @Override
    public String name(int id) {
        return switch (id) {
            case TravelMessage.TRAVEL_BEGIN -> "TRAVEL_BEGIN";
            case TravelMessage.TRAVEL_CHUNK -> "TRAVEL_CHUNK";
            case TravelMessage.TRAVEL_END -> "TRAVEL_END";
            case TravelMessage.TRAVEL_READY -> "TRAVEL_READY";
            case TravelMessage.TRAVEL_COMMIT -> "TRAVEL_COMMIT";
            case TravelMessage.TRAVEL_CANCEL -> "TRAVEL_CANCEL";
            case TravelMessage.TRAVEL_CROSS -> "TRAVEL_CROSS";
            case TravelMessage.TRAVEL_REUSE -> "TRAVEL_REUSE";
            case TravelMessage.TRAVEL_CACHED -> "TRAVEL_CACHED";
            case TravelMessage.REMOTE_LEVEL_OPEN -> "REMOTE_LEVEL_OPEN";
            case TravelMessage.REMOTE_LEVEL_CLOSE -> "REMOTE_LEVEL_CLOSE";
            case TravelMessage.ROUTED_PACKET -> "ROUTED_PACKET";
            case TravelMessage.TRAVEL_ACCEPT -> "TRAVEL_ACCEPT";
            case TravelMessage.REMOTE_VIEW_ACK -> "REMOTE_VIEW_ACK";
            default -> "TRAVEL(" + id + ")";
        };
    }

    @Override
    public int id(TravelMessage message) {
        return message.id();
    }

    @Override
    public long capabilities() {
        return ClientViewExtensions.PREPARED_TRAVEL | ClientViewExtensions.PREPARED_TRAVEL_CACHE
            | seamless.capabilities();
    }

    @Override
    public long requires(long capability) {
        if (capability == ClientViewExtensions.PREPARED_TRAVEL_CACHE) {
            return ClientViewExtensions.PREPARED_TRAVEL | ViewStreamCapability.MESH_RENDER.mask();
        }
        return seamless.requires(capability);
    }

    @Override
    public void encode(TravelMessage message, ViewStreamWriter out) throws ViewStreamProtocolException {
        switch (message) {
            case TravelMessage.TravelBegin begin -> {
                identity(out, begin.token(), begin.generation());
                uuid(out, begin.sourcePortal());
                out.string(begin.sourceWorld());
                ViewStreamCodec.writeGeometry(out, begin.sourceGeometry(), 0);
                EnvironmentStateCodec.writeTransform(out, begin.destinationToSource());
                world(out, begin.world());
                pose(out, begin.arrival());
                out.u16(begin.chunks().size());
                for (TravelMessage.TravelCoordinate chunk : begin.chunks()) {
                    out.i32(chunk.x());
                    out.i32(chunk.z());
                }
                EnvironmentStateCodec.write(out, begin.environment());
                out.i32(begin.expiresMillis());
                rules(out, begin.rules());
                out.u8(begin.resident() ? 1 : 0);
                out.u8(begin.levelHandle());
                out.u8(begin.seamless() ? 1 : 0);
            }
            case TravelMessage.TravelChunk chunk -> {
                identity(out, chunk.token(), chunk.generation());
                out.i32(chunk.chunkX());
                out.i32(chunk.chunkZ());
                out.i32(chunk.revision());
                out.u16(chunk.fragmentIndex());
                out.u16(chunk.fragmentCount());
                out.i32(chunk.totalBytes());
                byte[] payload = chunk.payload();
                out.i32(payload.length);
                out.bytes(payload);
            }
            case TravelMessage.TravelEnd end -> {
                identity(out, end.token(), end.generation());
                out.i64(end.contentRevision());
                out.u16(end.chunks().size());
                for (TravelMessage.TravelChunkRevision chunk : end.chunks()) {
                    out.i32(chunk.x());
                    out.i32(chunk.z());
                    out.i32(chunk.revision());
                }
            }
            case TravelMessage.TravelReady ready -> {
                identity(out, ready.token(), ready.generation());
                out.i64(ready.contentRevision());
            }
            case TravelMessage.TravelCommit commit -> {
                identity(out, commit.token(), commit.generation());
                out.i64(commit.contentRevision());
                out.string(commit.sourceWorld());
                out.string(commit.destinationWorld());
                pose(out, commit.arrival());
                vector(out, commit.velocity());
            }
            case TravelMessage.TravelCross cross -> {
                identity(out, cross.token(), cross.generation());
                out.i64(cross.contentRevision());
                pose(out, cross.sourcePose());
                vector(out, cross.previousEye());
                vector(out, cross.currentEye());
            }
            case TravelMessage.TravelReuse reuse -> {
                identity(out, reuse.token(), reuse.generation());
                out.i32(reuse.chunkX());
                out.i32(reuse.chunkZ());
                out.i32(reuse.revision());
                out.bytes(reuse.hash());
            }
            case TravelMessage.TravelCached cached -> {
                identity(out, cached.token(), cached.generation());
                out.i32(cached.chunkX());
                out.i32(cached.chunkZ());
                out.i32(cached.revision());
                out.bytes(cached.hash());
                out.u8(cached.available() ? 1 : 0);
            }
            case TravelMessage.TravelCancel cancel -> identity(out, cancel.token(), cancel.generation());
            default -> seamless.encode(message, out);
        }
    }

    @Override
    public TravelMessage decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
        try {
            return switch (id) {
                case TravelMessage.TRAVEL_BEGIN, TravelMessage.TRAVEL_CHUNK, TravelMessage.TRAVEL_END, TravelMessage.TRAVEL_READY,
                     TravelMessage.TRAVEL_COMMIT, TravelMessage.TRAVEL_CANCEL, TravelMessage.TRAVEL_CROSS, TravelMessage.TRAVEL_REUSE,
                     TravelMessage.TRAVEL_CACHED -> decodeTravel(id, uuid(in), in.i64(), in);
                default -> seamless.decode(id, in);
            };
        } catch (IllegalArgumentException invalid) {
            throw new ViewStreamProtocolException("Invalid travel message " + name(id), invalid);
        }
    }

    private static TravelMessage decodeTravel(int id, UUID token, long generation, ViewStreamReader in) throws ViewStreamProtocolException {
        return switch (id) {
            case TravelMessage.TRAVEL_BEGIN -> {
                UUID portal = uuid(in);
                String source = in.string();
                ApertureDescriptor geometry = ViewStreamCodec.readGeometry(in, 0);
                OpticTransform transform = EnvironmentStateCodec.readTransform(in);
                TravelMessage.TravelWorld world = world(in);
                TravelMessage.TravelPose pose = pose(in);
                int count = count(in);
                List<TravelMessage.TravelCoordinate> chunks = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    chunks.add(new TravelMessage.TravelCoordinate(in.i32(), in.i32()));
                }
                yield new TravelMessage.TravelBegin(token, generation, portal, source, geometry, transform, world, pose, chunks,
                    EnvironmentStateCodec.read(in), in.i32(), rules(in), bool(in), in.u8(), bool(in));
            }
            case TravelMessage.TRAVEL_CHUNK -> {
                int x = in.i32();
                int z = in.i32();
                int revision = in.i32();
                int index = in.u16();
                int fragments = in.u16();
                int total = in.i32();
                yield new TravelMessage.TravelChunk(token, generation, x, z, revision, index, fragments, total, fragment(in));
            }
            case TravelMessage.TRAVEL_END -> {
                long revision = in.i64();
                int count = count(in);
                List<TravelMessage.TravelChunkRevision> chunks = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    chunks.add(new TravelMessage.TravelChunkRevision(in.i32(), in.i32(), in.i32()));
                }
                yield new TravelMessage.TravelEnd(token, generation, revision, chunks);
            }
            case TravelMessage.TRAVEL_READY -> new TravelMessage.TravelReady(token, generation, in.i64());
            case TravelMessage.TRAVEL_COMMIT -> new TravelMessage.TravelCommit(token, generation, in.i64(), in.string(), in.string(), pose(in), vector(in));
            case TravelMessage.TRAVEL_CANCEL -> new TravelMessage.TravelCancel(token, generation);
            case TravelMessage.TRAVEL_REUSE -> new TravelMessage.TravelReuse(token, generation, in.i32(), in.i32(), in.i32(),
                in.bytes(TravelMessage.TRAVEL_HASH_BYTES));
            case TravelMessage.TRAVEL_CACHED -> new TravelMessage.TravelCached(token, generation, in.i32(), in.i32(), in.i32(),
                in.bytes(TravelMessage.TRAVEL_HASH_BYTES), bool(in));
            case TravelMessage.TRAVEL_CROSS -> new TravelMessage.TravelCross(token, generation, in.i64(), pose(in), vector(in), vector(in));
            default -> throw new ViewStreamProtocolException("Unknown travel message " + id);
        };
    }

    static byte[] fragment(ViewStreamReader in) throws ViewStreamProtocolException {
        int size = in.i32();
        if (size <= 0 || size > TravelMessage.TRAVEL_FRAGMENT_BYTES) {
            throw new ViewStreamProtocolException("Travel fragment size");
        }
        return in.bytes(size);
    }

    private static void rules(ViewStreamWriter out, TravelMessage.ArrivalRules rules) {
        out.u8(rules.orientation().ordinal());
        out.u8(rules.gravityFlip() ? 1 : 0);
        out.u8(rules.momentum().mode().ordinal());
        out.f64(rules.momentum().factor());
        out.f64(rules.momentum().maxSpeed());
        vector(out, rules.momentum().impulse());
    }

    private static TravelMessage.ArrivalRules rules(ViewStreamReader in) throws ViewStreamProtocolException {
        int orientation = in.u8();
        boolean gravityFlip = bool(in);
        int mode = in.u8();
        double factor = in.f64();
        double maxSpeed = in.f64();
        Vec3d impulse = vector(in);
        if (orientation >= OrientationRule.values().length || mode >= MomentumRule.Mode.values().length || !Double.isFinite(factor)
            || !Double.isFinite(maxSpeed) || maxSpeed < 0.0D) {
            throw new ViewStreamProtocolException("Travel arrival rules");
        }
        return new TravelMessage.ArrivalRules(OrientationRule.values()[orientation], gravityFlip,
            new MomentumRule(MomentumRule.Mode.values()[mode], factor, maxSpeed, impulse));
    }

    private static int count(ViewStreamReader in) throws ViewStreamProtocolException {
        int count = in.u16();
        if (count <= 0 || count > TravelMessage.MAX_TRAVEL_CHUNKS) {
            throw new ViewStreamProtocolException("Travel manifest count");
        }
        return count;
    }

    static void identity(ViewStreamWriter out, UUID token, long generation) {
        uuid(out, token);
        out.i64(generation);
    }

    static void uuid(ViewStreamWriter out, UUID value) {
        out.i64(value.getMostSignificantBits());
        out.i64(value.getLeastSignificantBits());
    }

    static UUID uuid(ViewStreamReader in) throws ViewStreamProtocolException {
        return new UUID(in.i64(), in.i64());
    }

    static void world(ViewStreamWriter out, TravelMessage.TravelWorld world) throws ViewStreamProtocolException {
        out.string(world.dimension());
        out.string(world.dimensionType());
        out.i64(world.seed());
        out.u8(world.debug() ? 1 : 0);
        out.u8(world.flat() ? 1 : 0);
        out.i32(world.seaLevel());
        out.i32(world.minY());
        out.i32(world.height());
    }

    static TravelMessage.TravelWorld world(ViewStreamReader in) throws ViewStreamProtocolException {
        return new TravelMessage.TravelWorld(in.string(), in.string(), in.i64(), bool(in), bool(in), in.i32(), in.i32(), in.i32());
    }

    static boolean bool(ViewStreamReader in) throws ViewStreamProtocolException {
        int value = in.u8();
        if (value > 1) {
            throw new ViewStreamProtocolException("Travel boolean");
        }
        return value == 1;
    }

    static void vector(ViewStreamWriter out, Vec3d vector) {
        out.f64(vector.x());
        out.f64(vector.y());
        out.f64(vector.z());
    }

    static Vec3d vector(ViewStreamReader in) throws ViewStreamProtocolException {
        return new Vec3d(in.f64(), in.f64(), in.f64());
    }

    static void pose(ViewStreamWriter out, TravelMessage.TravelPose pose) {
        out.f64(pose.x());
        out.f64(pose.y());
        out.f64(pose.z());
        out.f32(pose.yaw());
        out.f32(pose.pitch());
    }

    static TravelMessage.TravelPose pose(ViewStreamReader in) throws ViewStreamProtocolException {
        return new TravelMessage.TravelPose(in.f64(), in.f64(), in.f64(), in.f32(), in.f32());
    }

    public interface Seamless {
        Seamless NONE = new Seamless() {
            @Override
            public boolean serverbound(int id) {
                return false;
            }

            @Override
            public boolean clientbound(int id) {
                return false;
            }

            @Override
            public long capabilities() {
                return ViewStreamCapability.NONE;
            }

            @Override
            public long requires(long capability) {
                return ViewStreamCapability.NONE;
            }

            @Override
            public void encode(TravelMessage message, ViewStreamWriter out) throws ViewStreamProtocolException {
                throw new ViewStreamProtocolException("Seamless travel is unavailable for travel message " + message.id());
            }

            @Override
            public TravelMessage decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
                throw new ViewStreamProtocolException("Unknown travel message " + id);
            }
        };

        boolean serverbound(int id);

        boolean clientbound(int id);

        long capabilities();

        long requires(long capability);

        void encode(TravelMessage message, ViewStreamWriter out) throws ViewStreamProtocolException;

        TravelMessage decode(int id, ViewStreamReader in) throws ViewStreamProtocolException;
    }
}
