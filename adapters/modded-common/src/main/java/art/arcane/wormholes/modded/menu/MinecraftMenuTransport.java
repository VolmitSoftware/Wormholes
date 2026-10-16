package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuTransport;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class MinecraftMenuTransport implements MenuTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private volatile Function<MenuPayload, Packet<?>> packets = ClientboundCustomPayloadPacket::new;

    public MinecraftMenuTransport(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public void packets(Function<MenuPayload, Packet<?>> factory) {
        packets = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public boolean send(UUID viewerId, byte[] frame) {
        if (!runtime.running()) {
            return false;
        }
        ServerPlayer player = runtime.server().getPlayerList().getPlayer(viewerId);
        if (player == null) {
            return false;
        }
        player.connection.send(packets.apply(new MenuPayload(frame)));
        return true;
    }

    public void receive(ServerPlayer player, byte[] frame) {
        if (!runtime.running()) {
            return;
        }
        MinecraftServer server = runtime.server();
        if (!server.isSameThread()) {
            server.execute(() -> receiveDeferred(player, frame));
            return;
        }
        deliver(player, frame);
    }

    private void receiveDeferred(ServerPlayer player, byte[] frame) {
        if (!runtime.running() || player.hasDisconnected() || runtime.server().getPlayerList().getPlayer(player.getUUID()) != player) {
            return;
        }
        deliver(player, frame);
    }

    private void deliver(ServerPlayer player, byte[] frame) {
        if (frame.length > MenuLimits.MAX_C2S_BYTES) {
            LOGGER.warn("Disconnecting {}: menu frame of {} bytes exceeds {}", player.getGameProfile().name(), frame.length, MenuLimits.MAX_C2S_BYTES);
            player.connection.disconnect(Component.translatable("disconnect.packetError"));
            return;
        }
        runtime.menus().receive(player.getUUID(), frame);
    }
}
