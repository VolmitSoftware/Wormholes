package art.arcane.wormholes.network.client;

import art.arcane.optics.stream.EnvironmentStateCodec;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;

public final class SeamlessTravelCodec implements TravelExtension.Seamless {
    public static final SeamlessTravelCodec INSTANCE = new SeamlessTravelCodec();

    private SeamlessTravelCodec() {
    }

    @Override
    public boolean serverbound(int id) {
        return id == TravelMessage.REMOTE_VIEW_ACK;
    }

    @Override
    public boolean clientbound(int id) {
        return switch (id) {
            case TravelMessage.REMOTE_LEVEL_OPEN, TravelMessage.REMOTE_LEVEL_CLOSE, TravelMessage.ROUTED_PACKET, TravelMessage.TRAVEL_ACCEPT -> true;
            default -> false;
        };
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
            case TravelMessage.RemoteLevelOpen open -> {
                out.u8(open.levelHandle());
                TravelExtension.world(out, open.world());
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
                TravelExtension.identity(out, accept.token(), accept.generation());
                out.i64(accept.contentRevision());
                TravelExtension.pose(out, accept.pose());
                TravelExtension.vector(out, accept.velocity());
                out.u8(accept.levelHandle());
                out.u8(accept.dimensionChanged() ? 1 : 0);
                out.i64(accept.serverTick());
            }
            case TravelMessage.RemoteViewAck ack -> {
                out.u8(ack.levelHandle());
                out.i32(ack.lastSequence());
                out.u8(ack.chunksPerTickHint());
            }
            default -> throw new ViewStreamProtocolException("Unexpected seamless travel message " + message.id());
        }
    }

    @Override
    public TravelMessage decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
        return switch (id) {
            case TravelMessage.REMOTE_LEVEL_OPEN -> new TravelMessage.RemoteLevelOpen(in.u8(), TravelExtension.world(in),
                EnvironmentStateCodec.read(in), in.u8(), new TravelMessage.TravelCoordinate(in.i32(), in.i32()));
            case TravelMessage.REMOTE_LEVEL_CLOSE -> new TravelMessage.RemoteLevelClose(in.u8());
            case TravelMessage.ROUTED_PACKET -> {
                int handle = in.u8();
                int sequence = in.i32();
                int index = in.u16();
                int fragments = in.u16();
                int total = in.i32();
                yield new TravelMessage.RoutedPacket(handle, sequence, index, fragments, total, TravelExtension.fragment(in));
            }
            case TravelMessage.TRAVEL_ACCEPT -> new TravelMessage.TravelAccept(TravelExtension.uuid(in), in.i64(), in.i64(), TravelExtension.pose(in),
                TravelExtension.vector(in), in.u8(), TravelExtension.bool(in), in.i64());
            case TravelMessage.REMOTE_VIEW_ACK -> new TravelMessage.RemoteViewAck(in.u8(), in.i32(), in.u8());
            default -> throw new ViewStreamProtocolException("Unknown travel message " + id);
        };
    }
}
