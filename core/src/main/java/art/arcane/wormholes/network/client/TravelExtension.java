package art.arcane.wormholes.network.client;

import java.util.UUID;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentStateCodec;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamExtension;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;

public final class TravelExtension implements ViewStreamExtension<TravelMessage> {
    public static final TravelExtension INSTANCE = new TravelExtension();

    private TravelExtension() {
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
            case TravelMessage.TRAVEL_CROSS, TravelMessage.REMOTE_VIEW_ACK, TravelMessage.REMOTE_LEVEL_REOPEN -> true;
            default -> false;
        };
    }

    @Override
    public boolean clientbound(int id) {
        return switch (id) {
            case TravelMessage.TRAVEL_BEGIN, TravelMessage.TRAVEL_CANCEL, TravelMessage.REMOTE_LEVEL_OPEN, TravelMessage.REMOTE_LEVEL_CLOSE,
                 TravelMessage.ROUTED_PACKET, TravelMessage.TRAVEL_ACCEPT, TravelMessage.ENTITY_CROSSED -> true;
            default -> false;
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
            case TravelMessage.TRAVEL_CANCEL -> "TRAVEL_CANCEL";
            case TravelMessage.TRAVEL_CROSS -> "TRAVEL_CROSS";
            case TravelMessage.REMOTE_LEVEL_OPEN -> "REMOTE_LEVEL_OPEN";
            case TravelMessage.REMOTE_LEVEL_CLOSE -> "REMOTE_LEVEL_CLOSE";
            case TravelMessage.ROUTED_PACKET -> "ROUTED_PACKET";
            case TravelMessage.TRAVEL_ACCEPT -> "TRAVEL_ACCEPT";
            case TravelMessage.REMOTE_VIEW_ACK -> "REMOTE_VIEW_ACK";
            case TravelMessage.REMOTE_LEVEL_REOPEN -> "REMOTE_LEVEL_REOPEN";
            case TravelMessage.ENTITY_CROSSED -> "ENTITY_CROSSED";
            default -> "TRAVEL(" + id + ")";
        };
    }

    @Override
    public int id(TravelMessage message) {
        return message.id();
    }

    @Override
    public long capabilities() {
        return ClientViewExtensions.REMOTE_VIEW | ClientViewExtensions.SEAMLESS_TRAVEL;
    }

    @Override
    public long requires(long capability) {
        return capability == ClientViewExtensions.SEAMLESS_TRAVEL
            ? ClientViewExtensions.REMOTE_VIEW | ViewStreamCapability.MESH_RENDER.mask()
            : ViewStreamCapability.NONE;
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
                out.f32(begin.scale());
                world(out, begin.world());
                pose(out, begin.arrival());
                EnvironmentStateCodec.write(out, begin.environment());
                rules(out, begin.rules());
                out.u8(begin.resident() ? 1 : 0);
                out.u8(begin.levelHandle());
                TravelMessage.DoorCollisionTarget door = begin.doorCollision();
                out.u8(door == null ? 0 : 1);
                if (door != null) {
                    out.i32(door.x());
                    out.i32(door.y());
                    out.i32(door.z());
                    out.u8(door.open() ? 1 : 0);
                }
            }
            case TravelMessage.TravelCross cross -> {
                identity(out, cross.token(), cross.generation());
                out.i64(cross.contentRevision());
                pose(out, cross.sourcePose());
                vector(out, cross.previousEye());
                vector(out, cross.currentEye());
            }
            case TravelMessage.TravelCancel cancel -> identity(out, cancel.token(), cancel.generation());
            case TravelMessage.RemoteLevelOpen open -> {
                out.u8(open.levelHandle());
                world(out, open.world());
                EnvironmentStateCodec.write(out, open.environment());
                out.u8(open.viewRadius());
                out.i32(open.center().x());
                out.i32(open.center().z());
            }
            case TravelMessage.RemoteLevelClose close -> out.u8(close.levelHandle());
            case TravelMessage.RoutedPacket packet -> {
                out.u8(packet.levelHandle());
                out.i32(packet.sequence());
                out.u16(packet.fragmentIndex());
                out.u16(packet.fragmentCount());
                out.i32(packet.totalBytes());
                byte[] payload = packet.payload();
                out.i32(payload.length);
                out.bytes(payload);
            }
            case TravelMessage.TravelAccept accept -> {
                identity(out, accept.token(), accept.generation());
                out.i64(accept.contentRevision());
                pose(out, accept.pose());
                vector(out, accept.velocity());
                out.u8(accept.levelHandle());
                out.u8(accept.dimensionChanged() ? 1 : 0);
                out.i64(accept.serverTick());
            }
            case TravelMessage.RemoteViewAck ack -> {
                out.u8(ack.levelHandle());
                out.i32(ack.lastSequence());
                out.u8(ack.chunksPerTickHint());
            }
            case TravelMessage.RemoteLevelReopen reopen -> out.u8(reopen.levelHandle());
            case TravelMessage.EntityCrossed crossed -> {
                out.u8(crossed.levelHandle());
                out.i32(crossed.entityId());
                EnvironmentStateCodec.writeTransform(out, crossed.toward());
                vector(out, crossed.planeOrigin());
                out.u8(crossed.planeNormal().ordinal());
                vector(out, crossed.velocity());
            }
        }
    }

    @Override
    public TravelMessage decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
        try {
            return switch (id) {
                case TravelMessage.TRAVEL_BEGIN, TravelMessage.TRAVEL_CANCEL, TravelMessage.TRAVEL_CROSS, TravelMessage.TRAVEL_ACCEPT
                    -> decodeTravel(id, uuid(in), in.i64(), in);
                case TravelMessage.REMOTE_LEVEL_OPEN -> new TravelMessage.RemoteLevelOpen(in.u8(), world(in), EnvironmentStateCodec.read(in), in.u8(),
                    new TravelMessage.TravelCoordinate(in.i32(), in.i32()));
                case TravelMessage.REMOTE_LEVEL_CLOSE -> new TravelMessage.RemoteLevelClose(in.u8());
                case TravelMessage.ROUTED_PACKET -> {
                    int handle = in.u8();
                    int sequence = in.i32();
                    int index = in.u16();
                    int fragments = in.u16();
                    int total = in.i32();
                    yield new TravelMessage.RoutedPacket(handle, sequence, index, fragments, total, fragment(in));
                }
                case TravelMessage.REMOTE_VIEW_ACK -> new TravelMessage.RemoteViewAck(in.u8(), in.i32(), in.u8());
                case TravelMessage.REMOTE_LEVEL_REOPEN -> new TravelMessage.RemoteLevelReopen(in.u8());
                case TravelMessage.ENTITY_CROSSED -> new TravelMessage.EntityCrossed(in.u8(), in.i32(), EnvironmentStateCodec.readTransform(in),
                    vector(in), face(in.u8()), vector(in));
                default -> throw new ViewStreamProtocolException("Unknown travel message " + id);
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
                float scale = in.f32();
                TravelMessage.TravelWorld world = world(in);
                TravelMessage.TravelPose pose = pose(in);
                yield new TravelMessage.TravelBegin(token, generation, portal, source, geometry, transform, scale, world, pose,
                    EnvironmentStateCodec.read(in), rules(in), bool(in), in.u8(),
                    bool(in) ? new TravelMessage.DoorCollisionTarget(in.i32(), in.i32(), in.i32(), bool(in)) : null);
            }
            case TravelMessage.TRAVEL_CANCEL -> new TravelMessage.TravelCancel(token, generation);
            case TravelMessage.TRAVEL_CROSS -> new TravelMessage.TravelCross(token, generation, in.i64(), pose(in), vector(in), vector(in));
            case TravelMessage.TRAVEL_ACCEPT -> new TravelMessage.TravelAccept(token, generation, in.i64(), pose(in), vector(in), in.u8(), bool(in),
                in.i64());
            default -> throw new ViewStreamProtocolException("Unknown travel message " + id);
        };
    }

    private static Face face(int ordinal) throws ViewStreamProtocolException {
        if (ordinal >= Face.values().length) {
            throw new ViewStreamProtocolException("Entity crossing plane normal " + ordinal);
        }
        return Face.values()[ordinal];
    }

    private static byte[] fragment(ViewStreamReader in) throws ViewStreamProtocolException {
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
        out.u8(rules.scale().mode().ordinal());
        out.f32((float) rules.scale().min());
        out.f32((float) rules.scale().max());
    }

    private static TravelMessage.ArrivalRules rules(ViewStreamReader in) throws ViewStreamProtocolException {
        int orientation = in.u8();
        boolean gravityFlip = bool(in);
        int mode = in.u8();
        double factor = in.f64();
        double maxSpeed = in.f64();
        Vec3d impulse = vector(in);
        int scaleMode = in.u8();
        float scaleMin = in.f32();
        float scaleMax = in.f32();
        if (orientation >= OrientationRule.values().length || mode >= MomentumRule.Mode.values().length || !Double.isFinite(factor)
            || !Double.isFinite(maxSpeed) || maxSpeed < 0.0D || scaleMode >= ScaleRule.Mode.values().length
            || !Float.isFinite(scaleMin) || !Float.isFinite(scaleMax) || scaleMin > scaleMax) {
            throw new ViewStreamProtocolException("Travel arrival rules");
        }
        return new TravelMessage.ArrivalRules(OrientationRule.values()[orientation], gravityFlip,
            new MomentumRule(MomentumRule.Mode.values()[mode], factor, maxSpeed, impulse),
            new ScaleRule(ScaleRule.Mode.values()[scaleMode], scaleMin, scaleMax));
    }

    private static void identity(ViewStreamWriter out, UUID token, long generation) {
        uuid(out, token);
        out.i64(generation);
    }

    private static void uuid(ViewStreamWriter out, UUID value) {
        out.i64(value.getMostSignificantBits());
        out.i64(value.getLeastSignificantBits());
    }

    private static UUID uuid(ViewStreamReader in) throws ViewStreamProtocolException {
        return new UUID(in.i64(), in.i64());
    }

    private static void world(ViewStreamWriter out, TravelMessage.TravelWorld world) throws ViewStreamProtocolException {
        out.string(world.dimension());
        out.string(world.dimensionType());
        out.i64(world.seed());
        out.u8(world.debug() ? 1 : 0);
        out.u8(world.flat() ? 1 : 0);
        out.i32(world.seaLevel());
        out.i32(world.minY());
        out.i32(world.height());
    }

    private static TravelMessage.TravelWorld world(ViewStreamReader in) throws ViewStreamProtocolException {
        return new TravelMessage.TravelWorld(in.string(), in.string(), in.i64(), bool(in), bool(in), in.i32(), in.i32(), in.i32());
    }

    private static boolean bool(ViewStreamReader in) throws ViewStreamProtocolException {
        int value = in.u8();
        if (value > 1) {
            throw new ViewStreamProtocolException("Travel boolean");
        }
        return value == 1;
    }

    private static void vector(ViewStreamWriter out, Vec3d vector) {
        out.f64(vector.x());
        out.f64(vector.y());
        out.f64(vector.z());
    }

    private static Vec3d vector(ViewStreamReader in) throws ViewStreamProtocolException {
        return new Vec3d(in.f64(), in.f64(), in.f64());
    }

    private static void pose(ViewStreamWriter out, TravelMessage.TravelPose pose) {
        out.f64(pose.x());
        out.f64(pose.y());
        out.f64(pose.z());
        out.f32(pose.yaw());
        out.f32(pose.pitch());
    }

    private static TravelMessage.TravelPose pose(ViewStreamReader in) throws ViewStreamProtocolException {
        return new TravelMessage.TravelPose(in.f64(), in.f64(), in.f64(), in.f32(), in.f32());
    }
}
