package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

final class FabricSeamlessServer implements SeamlessServer {
    private final TestServerContext server;
    private final TestServerConnection connection;

    FabricSeamlessServer(TestServerContext server, TestServerConnection connection) {
        this.server = server;
        this.connection = connection;
    }

    @Override
    public SeamlessScenario.Route build(SeamlessScenario.RouteSpec spec) {
        return server.computeOnServer(minecraftServer -> SeamlessScenario.build(connection.getServerPlayer(), minecraftServer, spec));
    }

    @Override
    public void approach(SeamlessScenario.Route route) {
        server.runOnServer(minecraftServer -> SeamlessScenario.teleportToApproach(connection.getServerPlayer(),
            minecraftServer.getLevel(route.sourceLevel()), route.sourceMin()));
    }

    @Override
    public void remove(SeamlessScenario.Route route) {
        server.runOnServer(minecraftServer -> {
            SeamlessScenario.removePortal(connection.getServerPlayer(), minecraftServer, route.source());
            SeamlessScenario.removePortal(connection.getServerPlayer(), minecraftServer, route.destination());
        });
    }
}
