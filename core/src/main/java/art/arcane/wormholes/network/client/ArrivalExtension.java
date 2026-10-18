package art.arcane.wormholes.network.client;

import art.arcane.optics.stream.ViewStreamExtension;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;

import java.util.UUID;

public final class ArrivalExtension implements ViewStreamExtension<ArrivalMessage> {
    public static final ArrivalExtension INSTANCE = new ArrivalExtension();

    private ArrivalExtension() {
    }

    @Override
    public int firstId() {
        return ArrivalMessage.PREPARE;
    }

    @Override
    public int lastId() {
        return ArrivalMessage.CANCEL;
    }

    @Override
    public boolean serverbound(int id) {
        return id == ArrivalMessage.READY;
    }

    @Override
    public boolean clientbound(int id) {
        return id >= firstId() && id <= lastId() && !serverbound(id);
    }

    @Override
    public Class<ArrivalMessage> type() {
        return ArrivalMessage.class;
    }

    @Override
    public String name(int id) {
        return switch (id) {
            case ArrivalMessage.PREPARE -> "ARRIVAL_PREPARE";
            case ArrivalMessage.CHUNK -> "ARRIVAL_CHUNK";
            case ArrivalMessage.READY -> "ARRIVAL_READY";
            case ArrivalMessage.COMMIT -> "ARRIVAL_COMMIT";
            case ArrivalMessage.CANCEL -> "ARRIVAL_CANCEL";
            default -> "ARRIVAL(" + id + ")";
        };
    }

    @Override
    public int id(ArrivalMessage message) {
        return message.id();
    }

    @Override
    public long capabilities() {
        return ClientViewExtensions.ARRIVAL_PREPARATION;
    }

    @Override
    public void encode(ArrivalMessage message, ViewStreamWriter out) throws ViewStreamProtocolException {
        uuid(out, message.token());
        out.i64(message.revision());
        switch (message) {
            case ArrivalMessage.Prepare prepare -> {
                uuid(out, prepare.worldId());
                world(out, prepare.world());
                pose(out, prepare.arrival());
                out.u8(prepare.radius());
                out.string(prepare.registryFingerprint());
                out.i64(prepare.gameTime());
                out.i64(prepare.clockTime());
                out.f32(prepare.rain());
                out.f32(prepare.thunder());
                out.u16(prepare.lifetimeTicks());
            }
            case ArrivalMessage.Chunk chunk -> {
                out.i32(chunk.x());
                out.i32(chunk.z());
                out.u16(chunk.fragmentIndex());
                out.u16(chunk.fragmentCount());
                out.i32(chunk.totalBytes());
                byte[] bytes = chunk.payload();
                out.u16(bytes.length);
                out.bytes(bytes);
            }
            case ArrivalMessage.Ready ready -> out.u8(ready.readiness().ordinal());
            case ArrivalMessage.Commit commit -> pose(out, commit.arrival());
            case ArrivalMessage.Cancel ignored -> { }
        }
    }

    @Override
    public ArrivalMessage decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
        UUID token = uuid(in);
        long revision = in.i64();
        try {
            return switch (id) {
                case ArrivalMessage.PREPARE -> new ArrivalMessage.Prepare(token, revision, uuid(in), world(in), pose(in), in.u8(),
                    in.string(), in.i64(), in.i64(), in.f32(), in.f32(), in.u16());
                case ArrivalMessage.CHUNK -> new ArrivalMessage.Chunk(token, revision, in.i32(), in.i32(), in.u16(), in.u16(),
                    in.i32(), in.bytes(in.checkedCount(in.u16(), ArrivalMessage.FRAGMENT_BYTES, 1)));
                case ArrivalMessage.READY -> new ArrivalMessage.Ready(token, revision, readiness(in.u8()));
                case ArrivalMessage.COMMIT -> new ArrivalMessage.Commit(token, revision, pose(in));
                case ArrivalMessage.CANCEL -> new ArrivalMessage.Cancel(token, revision);
                default -> throw new ViewStreamProtocolException("Unknown arrival message " + id);
            };
        } catch (IllegalArgumentException failure) {
            throw new ViewStreamProtocolException("Invalid arrival message " + id, failure);
        }
    }

    private static ArrivalMessage.Readiness readiness(int ordinal) {
        ArrivalMessage.Readiness[] values = ArrivalMessage.Readiness.values();
        if (ordinal >= values.length) {
            throw new IllegalArgumentException("Invalid arrival readiness");
        }
        return values[ordinal];
    }

    private static void uuid(ViewStreamWriter out, UUID id) {
        out.i64(id.getMostSignificantBits());
        out.i64(id.getLeastSignificantBits());
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
        return new TravelMessage.TravelWorld(in.string(), in.string(), in.i64(), in.u8() != 0, in.u8() != 0,
            in.i32(), in.i32(), in.i32());
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
