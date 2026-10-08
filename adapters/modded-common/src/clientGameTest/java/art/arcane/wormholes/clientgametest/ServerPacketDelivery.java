package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.mixin.ConnectionChannelAccess;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Function;

final class ServerPacketDelivery {
    private ServerPacketDelivery() {
    }

    static TickStepper stepper(SeamlessClient client) {
        return new Stepper(client);
    }

    static Channels channels(Minecraft minecraft) {
        IntegratedServer integrated = minecraft.getSingleplayerServer();
        if (integrated == null || minecraft.player == null || minecraft.getConnection() == null) {
            throw new AssertionError("server packet delivery needs a singleplayer connection");
        }
        ServerPlayer player = integrated.getPlayerList().getPlayer(minecraft.player.getUUID());
        if (player == null) {
            throw new AssertionError("the integrated server has no player for the local client");
        }
        Channel server = ((ConnectionChannelAccess) ((ServerConnectionAccess) player.connection).wormholesConnection()).wormholesChannel();
        Channel client = ((ConnectionChannelAccess) minecraft.getConnection().getConnection()).wormholesChannel();
        return new Channels(server, client);
    }

    static void drain(Channel first, Channel second) {
        first.eventLoop().submit(() -> { }).syncUninterruptibly();
        second.eventLoop().submit(() -> { }).syncUninterruptibly();
    }

    record Channels(Channel server, Channel client) {
    }

    private record Stepper(SeamlessClient client) implements TickStepper {
        @Override
        public <T> T step(Function<Minecraft, T> sample) {
            client.waitTicks(1);
            Channels channels = client.computeOnClient(ServerPacketDelivery::channels);
            drain(channels.server(), channels.client());
            return client.computeOnClient(minecraft -> {
                minecraft.packetProcessor().processQueuedPackets();
                return sample.apply(minecraft);
            });
        }

        @Override
        public void close() {
        }
    }
}
