package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    @Override
    public void lightNetherPortal(BlockPos frame) {
        server.runOnServer(minecraftServer -> SeamlessWalkThrough.light(minecraftServer, connection.getServerPlayer(), frame));
    }

    @Override
    public boolean netherPortalLit(BlockPos frame) {
        return server.computeOnServer(minecraftServer -> SeamlessWalkThrough.lit(minecraftServer, frame));
    }

    @Override
    public List<Vec3> netherPortalCenters(BlockPos frame) {
        return server.computeOnServer(minecraftServer -> SeamlessWalkThrough.centers(minecraftServer, frame));
    }

    @Override
    public SeamlessFallLoop.Loop buildFallLoop() {
        return server.computeOnServer(minecraftServer -> SeamlessFallLoop.build(minecraftServer, connection.getServerPlayer()));
    }

    @Override
    public void removeFallLoop(SeamlessFallLoop.Loop loop) {
        server.runOnServer(minecraftServer -> SeamlessFallLoop.remove(minecraftServer, connection.getServerPlayer(), loop));
    }

    @Override
    public boolean chunkEverLoaded(ResourceKey<Level> level, int chunkX, int chunkZ) {
        return server.computeOnServer(minecraftServer -> LoadedChunks.everLoaded(Objects.requireNonNull(minecraftServer.getLevel(level)),
            chunkX, chunkZ));
    }

    @Override
    public void approachFrom(ResourceKey<Level> level, Vec3 position, float yaw) {
        server.runOnServer(minecraftServer -> connection.getServerPlayer().teleportTo(Objects.requireNonNull(minecraftServer.getLevel(level)),
            position.x, position.y, position.z, Set.of(), yaw, 0.0F, false));
    }
}
