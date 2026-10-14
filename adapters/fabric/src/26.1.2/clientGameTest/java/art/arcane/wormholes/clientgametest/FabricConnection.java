package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestClientLevelContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class FabricConnection implements AutoCloseable {
    private final TestClientLevelContext level;
    private final TestServerContext serverContext;
    private final MinecraftServer server;
    private final TestServerConnection dedicated;

    private FabricConnection(TestClientLevelContext level, TestServerContext serverContext, MinecraftServer server, TestServerConnection dedicated) {
        this.level = level;
        this.serverContext = serverContext;
        this.server = server;
        this.dedicated = dedicated;
    }

    static FabricConnection of(TestSingleplayerContext world) {
        return new FabricConnection(world.getClientLevel(), world.getServer(), null, null);
    }

    static FabricConnection connect(TestDedicatedServerContext server) {
        TestServerConnection connection = server.connect();
        return new FabricConnection(connection.getClientLevel(), server, server.computeOnServer(minecraftServer -> minecraftServer), connection);
    }

    int waitForChunksDownload() {
        return level.waitForChunksDownload();
    }

    int waitForChunksRender() {
        return level.waitForChunksRender();
    }

    void waitForClientboundPackets(ClientGameTestContext context) {
        int id = ClientboundSync.next();
        serverContext.runOnServer(minecraftServer -> getServerPlayer().connection.send(new ClientboundPingPacket(id)));
        context.waitFor(minecraft -> ClientboundSync.reached(id));
    }

    ServerPlayer getServerPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer owner = server != null ? server : minecraft.getSingleplayerServer();
        LocalPlayer player = minecraft.player;
        return player == null || owner == null ? null : owner.getPlayerList().getPlayer(player.getUUID());
    }

    ServerLevel getServerLevel() {
        ServerPlayer player = getServerPlayer();
        return player == null ? null : player.level();
    }

    @Override
    public void close() {
        if (dedicated != null) {
            dedicated.close();
        }
    }
}
