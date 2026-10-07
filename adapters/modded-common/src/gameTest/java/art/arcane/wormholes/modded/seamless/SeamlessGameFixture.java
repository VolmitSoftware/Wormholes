package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamHandshake;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.wormholes.modded.MinecraftGameTestPlayer;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewExtensions;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class SeamlessGameFixture implements AutoCloseable {
    static final long CLIENT_CAPS = ViewStreamCapability.MESH_RENDER.mask() | ClientViewExtensions.PREPARED_TRAVEL | ClientViewExtensions.REMOTE_VIEW
        | ClientViewExtensions.SEAMLESS_TRAVEL;

    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer player;
    private final ProtocolInfo<ClientGamePacketListener> protocol;
    private final List<MinecraftPortal> portals = new ArrayList<>();
    private final Map<ServerLevel, Map<BlockPos, BlockState>> physical = new HashMap<>();
    private final Map<Integer, List<TravelMessage.RoutedPacket>> fragments = new HashMap<>();
    private final List<TravelMessage> travel = new ArrayList<>();
    private final List<Object> vanilla = new ArrayList<>();
    private final List<Routed> routed = new ArrayList<>();
    private long caps;

    private SeamlessGameFixture(WormholesModRuntime runtime, MinecraftGameTestPlayer player) {
        this.runtime = runtime;
        this.player = player;
        this.protocol = RoutedPackets.protocol(runtime.server().registryAccess());
    }

    static SeamlessGameFixture connect(WormholesModRuntime runtime, ServerLevel level, String name) {
        return new SeamlessGameFixture(runtime, MinecraftGameTestPlayer.connect(runtime, level, name));
    }

    ServerPlayer player() {
        return player.player();
    }

    MinecraftGameTestPlayer connection() {
        return player;
    }

    MinecraftPortal portal(ServerLevel level, BlockPos min) {
        clear(level, min.offset(-2, -1, -4), min.offset(4, 4, 4));
        for (int x = -2; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                set(level, min.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
        }
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                cells.add(min.offset(x, y, 0));
            }
        }
        MinecraftPortal portal = runtime.portals().create(player().getUUID(), level, cells, PortalType.PORTAL, new Vec3(0, 0, -1));
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portals.add(portal);
        return portal;
    }

    boolean link(MinecraftPortal source, MinecraftPortal destination) {
        return runtime.portals().link(player(), source.getId(), destination.getId());
    }

    void stand(MinecraftPortal portal, double offset) {
        Vec3d origin = portal.getOrigin();
        player().setPos(new Vec3(origin.x(), Math.floor(origin.y()) - 1.0D, origin.z() + offset));
        player().setYRot(0.0F);
        player().setYHeadRot(0.0F);
        player().setYBodyRot(0.0F);
        player().setXRot(0.0F);
        player().connection.resetPosition();
    }

    void shift(double x, double z) {
        player().setPos(player().position().add(x, 0.0D, z));
        player().connection.resetPosition();
    }

    void negotiate() throws ViewStreamProtocolException {
        runtime.clientViews().channelRegistered(player());
        ViewStreamMessage.Offer offer = null;
        for (Object packet : player.drainPackets()) {
            ViewStreamMessage message = frame(packet, ViewStreamCapability.ALL);
            if (message instanceof ViewStreamMessage.Offer found) {
                offer = found;
            }
        }
        if (offer == null) {
            throw new IllegalStateException("No ClientView OFFER reached the seamless test player");
        }
        caps = offer.serverCaps() & CLIENT_CAPS;
        byte[] hello = MinecraftClientViewExtensions.CODEC.encodeC2S(ViewStreamHandshake.clientHello(offer,
            SharedConstants.getCurrentVersion().dataVersion().version(), CLIENT_CAPS, 512 * 1024, 256, 0L, "fabric"));
        runtime.clientViews().receive(player().connection, hello);
        for (Object packet : player.drainPackets()) {
            if (frame(packet, ViewStreamCapability.ALL) instanceof ViewStreamMessage.Accept accept) {
                caps = accept.caps();
            }
        }
        if ((caps & ClientViewExtensions.SEAMLESS_TRAVEL) == 0L) {
            throw new IllegalStateException("Seamless travel was not negotiated: caps " + Long.toBinaryString(caps));
        }
    }

    void pump() {
        for (Object packet : player.drainPackets()) {
            ViewStreamMessage message = frame(packet, caps);
            if (message instanceof ViewStreamMessage.Extension extension && extension.payload() instanceof TravelMessage travelMessage) {
                receive(travelMessage);
            } else if (message == null) {
                vanilla.add(packet);
            }
        }
    }

    void send(TravelMessage message) throws ViewStreamProtocolException {
        payload(message).handle(player().connection);
    }

    void sendFromNetwork(TravelMessage message, List<Packet<? super ServerGamePacketListener>> following) throws ViewStreamProtocolException {
        List<Packet<? super ServerGamePacketListener>> burst = new ArrayList<>(following.size() + 2);
        burst.add(payload(message));
        burst.addAll(following);
        burst.add(ServerboundClientTickEndPacket.INSTANCE);
        runtime.server().packetProcessor().scheduleIfPossible(player().connection, new NetworkBurst(burst));
    }

    List<TravelMessage> travel() {
        return travel;
    }

    List<Object> vanilla() {
        return vanilla;
    }

    List<Routed> routed() {
        return routed;
    }

    <T extends TravelMessage> T last(Class<T> type) {
        for (int index = travel.size() - 1; index >= 0; index--) {
            if (type.isInstance(travel.get(index))) {
                return type.cast(travel.get(index));
            }
        }
        return null;
    }

    boolean vanillaContains(Class<?> type) {
        for (Object packet : vanilla) {
            if (type.isInstance(packet)) {
                return true;
            }
        }
        return false;
    }

    boolean acknowledge() {
        ClientboundPlayerPositionPacket latest = null;
        for (Object packet : vanilla) {
            if (packet instanceof ClientboundPlayerPositionPacket position) {
                latest = position;
            }
        }
        if (latest == null) {
            return false;
        }
        ServerPlayer target = player();
        target.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(latest.id(), target.getX(), target.getY(),
            target.getZ(), target.getYRot(), target.getXRot()));
        return true;
    }

    void forget() {
        travel.clear();
        vanilla.clear();
        routed.clear();
    }

    void forgetResolved() {
        int resolved = -1;
        for (int index = 0; index < travel.size(); index++) {
            if (travel.get(index) instanceof TravelMessage.TravelCancel || travel.get(index) instanceof TravelMessage.TravelAccept) {
                resolved = index;
            }
        }
        travel.subList(0, resolved + 1).clear();
        vanilla.clear();
        routed.clear();
    }

    @Override
    public void close() {
        for (MinecraftPortal portal : portals) {
            runtime.portals().remove(player(), portal.getId());
        }
        player.close();
        for (Map.Entry<ServerLevel, Map<BlockPos, BlockState>> level : physical.entrySet()) {
            for (Map.Entry<BlockPos, BlockState> entry : level.getValue().entrySet()) {
                level.getKey().setBlockAndUpdate(entry.getKey(), entry.getValue());
            }
        }
    }

    private void receive(TravelMessage message) {
        travel.add(message);
        if (!(message instanceof TravelMessage.RoutedPacket fragment)) {
            return;
        }
        List<TravelMessage.RoutedPacket> parts = fragments.computeIfAbsent(fragment.sequence(), ignored -> new ArrayList<>());
        parts.add(fragment);
        if (parts.size() == fragment.fragmentCount()) {
            fragments.remove(fragment.sequence());
            routed.add(new Routed(fragment.levelHandle(), RoutedPackets.decode(protocol, RoutedPackets.join(parts))));
        }
    }

    private void clear(ServerLevel level, BlockPos from, BlockPos to) {
        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int y = from.getY(); y <= to.getY(); y++) {
                for (int z = from.getZ(); z <= to.getZ(); z++) {
                    set(level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private void set(ServerLevel level, BlockPos position, BlockState state) {
        level.getChunk(position.getX() >> 4, position.getZ() >> 4);
        physical.computeIfAbsent(level, ignored -> new LinkedHashMap<>()).putIfAbsent(position.immutable(), level.getBlockState(position));
        level.setBlockAndUpdate(position, state);
    }

    private static ServerboundCustomPayloadPacket payload(TravelMessage message) throws ViewStreamProtocolException {
        return new ServerboundCustomPayloadPacket(new ClientViewPayload(MinecraftClientViewExtensions.CODEC.encodeC2S(
            new ViewStreamMessage.Extension(message.id(), message))));
    }

    private static ViewStreamMessage frame(Object packet, long caps) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !(custom.payload() instanceof ClientViewPayload payload)) {
            return null;
        }
        try {
            return MinecraftClientViewExtensions.CODEC.decodeS2C(payload.data(), caps).message();
        } catch (ViewStreamProtocolException failure) {
            throw new IllegalStateException("Seamless test player could not decode a ClientView frame", failure);
        }
    }

    record Routed(int handle, Packet<? super ClientGamePacketListener> packet) {
    }

    private record NetworkBurst(List<Packet<? super ServerGamePacketListener>> packets) implements Packet<ServerGamePacketListener> {
        @Override
        public PacketType<ServerboundClientTickEndPacket> type() {
            return GamePacketTypes.SERVERBOUND_CLIENT_TICK_END;
        }

        @Override
        public void handle(ServerGamePacketListener listener) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread network = new Thread(() -> deliver(listener, failure), "seamless-test-network");
            network.start();
            try {
                network.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while delivering seamless test packets", interrupted);
            }
            if (failure.get() != null) {
                throw new IllegalStateException("Seamless test network delivery failed", failure.get());
            }
        }

        private void deliver(ServerGamePacketListener listener, AtomicReference<Throwable> failure) {
            try {
                for (Packet<? super ServerGamePacketListener> packet : packets) {
                    handleQueued(packet, listener);
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        }

        private static void handleQueued(Packet<? super ServerGamePacketListener> packet, ServerGamePacketListener listener) {
            try {
                packet.handle(listener);
            } catch (RunningOnDifferentThreadException scheduled) {
                return;
            }
            if (!(packet instanceof ServerboundCustomPayloadPacket)) {
                throw new IllegalStateException(packet.type() + " was handled off the server thread instead of being queued");
            }
        }
    }
}
