package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.mixin.ConnectionChannelAccess;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Predicate;

final class ServerPacketDelivery {
    private ServerPacketDelivery() {
    }

    static void tick(SeamlessClient client) {
        client.waitTicks(1);
        for (Channel channel : client.computeOnClient(ServerPacketDelivery::channels)) {
            channel.eventLoop().submit(() -> { }).syncUninterruptibly();
        }
        client.runOnClient(minecraft -> minecraft.packetProcessor().processQueuedPackets());
    }

    static void ticks(SeamlessClient client, int count) {
        for (int tick = 0; tick < count; tick++) {
            tick(client);
        }
    }

    static void waitFor(SeamlessClient client, Predicate<Minecraft> condition, int timeoutTicks, String failure) {
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            if (client.computeOnClient(condition::test)) {
                return;
            }
            tick(client);
        }
        throw new AssertionError(failure + " within " + timeoutTicks + " ticks");
    }

    private static List<Channel> channels(Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null || minecraft.getConnection() == null) {
            throw new AssertionError("server packet delivery needs a singleplayer connection");
        }
        ServerPlayer player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
        if (player == null) {
            throw new AssertionError("the integrated server has no player for the local client");
        }
        Channel sent = ((ConnectionChannelAccess) ((ServerConnectionAccess) player.connection).wormholesConnection()).wormholesChannel();
        Channel received = ((ConnectionChannelAccess) minecraft.getConnection().getConnection()).wormholesChannel();
        return List.of(sent, received);
    }
}
