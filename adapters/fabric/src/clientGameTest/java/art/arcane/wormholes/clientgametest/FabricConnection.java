package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class FabricConnection implements AutoCloseable {
    private final TestServerConnection connection;
    private final TestDedicatedServerConnection dedicated;

    private FabricConnection(TestServerConnection connection, TestDedicatedServerConnection dedicated) {
        this.connection = connection;
        this.dedicated = dedicated;
    }

    static FabricConnection of(TestSingleplayerContext world) {
        return new FabricConnection(world.getConnection(), null);
    }

    static FabricConnection connect(TestDedicatedServerContext server) {
        TestDedicatedServerConnection connection = server.connect();
        return new FabricConnection(connection, connection);
    }

    int waitForChunksDownload() {
        return connection.waitForChunksDownload();
    }

    int waitForChunksRender() {
        return connection.waitForChunksRender();
    }

    void waitForClientboundPackets(ClientGameTestContext context) {
        connection.waitForClientboundPackets();
    }

    ServerPlayer getServerPlayer() {
        return connection.getServerPlayer();
    }

    ServerLevel getServerLevel() {
        return connection.getServerLevel();
    }

    @Override
    public void close() {
        if (dedicated != null) {
            dedicated.close();
        }
    }
}
