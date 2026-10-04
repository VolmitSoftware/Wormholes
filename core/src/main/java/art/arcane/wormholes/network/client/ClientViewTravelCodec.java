package art.arcane.wormholes.network.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.util.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ClientViewTravelCodec {
    private ClientViewTravelCodec() {
    }

    static void write(ClientViewWriter out, ClientViewMessage message) throws ClientViewProtocolException {
        switch (message) {
            case ClientViewMessage.TravelBegin begin -> {
                identity(out, begin.token(), begin.generation());
                uuid(out, begin.sourcePortal());
                out.string(begin.sourceWorld());
                ClientViewCodec.writeGeometry(out, begin.sourceGeometry(), 0);
                transform(out, begin.destinationToSource());
                world(out, begin.world());
                pose(out, begin.arrival());
                out.u16(begin.chunks().size());
                for (ClientViewMessage.TravelCoordinate chunk : begin.chunks()) {
                    out.i32(chunk.x());
                    out.i32(chunk.z());
                }
                ClientViewEnvironmentCodec.write(out, begin.environment());
                out.i32(begin.expiresMillis());
            }
            case ClientViewMessage.TravelChunk chunk -> {
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
            case ClientViewMessage.TravelEnd end -> {
                identity(out, end.token(), end.generation());
                out.i64(end.contentRevision());
                out.u16(end.chunks().size());
                for (ClientViewMessage.TravelChunkRevision chunk : end.chunks()) {
                    out.i32(chunk.x());
                    out.i32(chunk.z());
                    out.i32(chunk.revision());
                }
            }
            case ClientViewMessage.TravelReady ready -> {
                identity(out, ready.token(), ready.generation());
                out.i64(ready.contentRevision());
            }
            case ClientViewMessage.TravelCommit commit -> {
                identity(out, commit.token(), commit.generation());
                out.i64(commit.contentRevision());
                out.string(commit.sourceWorld());
                out.string(commit.destinationWorld());
                pose(out, commit.arrival());
                vector(out, commit.velocity());
            }
            case ClientViewMessage.TravelCross cross -> {
                identity(out, cross.token(), cross.generation());
                out.i64(cross.contentRevision());
                pose(out, cross.sourcePose());
                vector(out, cross.previousEye());
                vector(out, cross.currentEye());
            }
            case ClientViewMessage.TravelReuse reuse -> {
                identity(out, reuse.token(), reuse.generation());
                out.i32(reuse.chunkX());
                out.i32(reuse.chunkZ());
                out.i32(reuse.revision());
                out.bytes(reuse.hash());
            }
            case ClientViewMessage.TravelCached cached -> {
                identity(out, cached.token(), cached.generation());
                out.i32(cached.chunkX());
                out.i32(cached.chunkZ());
                out.i32(cached.revision());
                out.bytes(cached.hash());
                out.u8(cached.available() ? 1 : 0);
            }
            case ClientViewMessage.TravelCancel cancel -> identity(out, cancel.token(), cancel.generation());
            default -> throw new ClientViewProtocolException("Unexpected travel message " + message.type());
        }
    }

    static ClientViewMessage read(ClientViewReader in, ClientViewMessageType type) throws ClientViewProtocolException {
        try {
            UUID token = uuid(in);
            long generation = in.i64();
            return switch (type) {
                case TRAVEL_BEGIN -> {
                    UUID portal = uuid(in);
                    String source = in.string();
                    ClientPortalGeometry geometry = ClientViewCodec.readGeometry(in, 0);
                    ClientViewEnvironment.Transform transform = transform(in);
                    ClientViewMessage.TravelWorld world = world(in);
                    ClientViewMessage.TravelPose pose = pose(in);
                    int count = count(in);
                    List<ClientViewMessage.TravelCoordinate> chunks = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        chunks.add(new ClientViewMessage.TravelCoordinate(in.i32(), in.i32()));
                    }
                    yield new ClientViewMessage.TravelBegin(token, generation, portal, source, geometry, transform, world, pose, chunks,
                        ClientViewEnvironmentCodec.read(in), in.i32());
                }
                case TRAVEL_CHUNK -> {
                    int x = in.i32();
                    int z = in.i32();
                    int revision = in.i32();
                    int index = in.u16();
                    int fragments = in.u16();
                    int total = in.i32();
                    int size = in.i32();
                    if (size <= 0 || size > ClientViewProtocol.TRAVEL_FRAGMENT_BYTES) {
                        throw new ClientViewProtocolException("Travel fragment size");
                    }
                    yield new ClientViewMessage.TravelChunk(token, generation, x, z, revision, index, fragments, total, in.bytes(size));
                }
                case TRAVEL_END -> {
                    long revision = in.i64();
                    int count = count(in);
                    List<ClientViewMessage.TravelChunkRevision> chunks = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        chunks.add(new ClientViewMessage.TravelChunkRevision(in.i32(), in.i32(), in.i32()));
                    }
                    yield new ClientViewMessage.TravelEnd(token, generation, revision, chunks);
                }
                case TRAVEL_READY -> new ClientViewMessage.TravelReady(token, generation, in.i64());
                case TRAVEL_COMMIT -> new ClientViewMessage.TravelCommit(token, generation, in.i64(), in.string(), in.string(), pose(in), vector(in));
                case TRAVEL_CANCEL -> new ClientViewMessage.TravelCancel(token, generation);
                case TRAVEL_REUSE -> new ClientViewMessage.TravelReuse(token, generation, in.i32(), in.i32(), in.i32(), in.bytes(ClientViewProtocol.TRAVEL_HASH_BYTES));
                case TRAVEL_CACHED -> new ClientViewMessage.TravelCached(token, generation, in.i32(), in.i32(), in.i32(), in.bytes(ClientViewProtocol.TRAVEL_HASH_BYTES), bool(in));
                case TRAVEL_CROSS -> new ClientViewMessage.TravelCross(token, generation, in.i64(), pose(in), vector(in), vector(in));
                default -> throw new ClientViewProtocolException("Unexpected travel message " + type);
            };
        } catch (IllegalArgumentException invalid) {
            throw new ClientViewProtocolException("Invalid travel message", invalid);
        }
    }

    private static int count(ClientViewReader in) throws ClientViewProtocolException {
        int count = in.u16();
        if (count <= 0 || count > ClientViewProtocol.MAX_TRAVEL_CHUNKS) {
            throw new ClientViewProtocolException("Travel manifest count");
        }
        return count;
    }

    private static void identity(ClientViewWriter out, UUID token, long generation) {
        uuid(out, token);
        out.i64(generation);
    }

    private static void uuid(ClientViewWriter out, UUID value) {
        out.i64(value.getMostSignificantBits());
        out.i64(value.getLeastSignificantBits());
    }

    private static UUID uuid(ClientViewReader in) throws ClientViewProtocolException {
        return new UUID(in.i64(), in.i64());
    }

    private static void world(ClientViewWriter out, ClientViewMessage.TravelWorld world) throws ClientViewProtocolException {
        out.string(world.dimension());
        out.string(world.dimensionType());
        out.i64(world.seed());
        out.u8(world.debug() ? 1 : 0);
        out.u8(world.flat() ? 1 : 0);
        out.i32(world.seaLevel());
        out.i32(world.minY());
        out.i32(world.height());
    }

    private static ClientViewMessage.TravelWorld world(ClientViewReader in) throws ClientViewProtocolException {
        return new ClientViewMessage.TravelWorld(in.string(), in.string(), in.i64(), bool(in), bool(in), in.i32(), in.i32(), in.i32());
    }

    private static boolean bool(ClientViewReader in) throws ClientViewProtocolException {
        int value = in.u8();
        if (value > 1) {
            throw new ClientViewProtocolException("Travel boolean");
        }
        return value == 1;
    }

    private static void transform(ClientViewWriter out, ClientViewEnvironment.Transform transform) {
        out.u8(transform.xAxis().ordinal());
        out.u8(transform.yAxis().ordinal());
        out.u8(transform.zAxis().ordinal());
        vector(out, transform.translation());
    }

    private static ClientViewEnvironment.Transform transform(ClientViewReader in) throws ClientViewProtocolException {
        Direction[] directions = Direction.values();
        int x = in.u8();
        int y = in.u8();
        int z = in.u8();
        if (x >= directions.length || y >= directions.length || z >= directions.length) {
            throw new ClientViewProtocolException("Travel transform axis");
        }
        return new ClientViewEnvironment.Transform(directions[x], directions[y], directions[z], vector(in));
    }

    private static void vector(ClientViewWriter out, GeometryVector vector) {
        out.f64(vector.x());
        out.f64(vector.y());
        out.f64(vector.z());
    }

    private static GeometryVector vector(ClientViewReader in) throws ClientViewProtocolException {
        return new GeometryVector(in.f64(), in.f64(), in.f64());
    }

    private static void pose(ClientViewWriter out, ClientViewMessage.TravelPose pose) {
        out.f64(pose.x());
        out.f64(pose.y());
        out.f64(pose.z());
        out.f32(pose.yaw());
        out.f32(pose.pitch());
    }

    private static ClientViewMessage.TravelPose pose(ClientViewReader in) throws ClientViewProtocolException {
        return new ClientViewMessage.TravelPose(in.f64(), in.f64(), in.f64(), in.f32(), in.f32());
    }
}
