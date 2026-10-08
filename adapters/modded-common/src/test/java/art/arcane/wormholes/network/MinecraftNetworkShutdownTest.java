package art.arcane.wormholes.network;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.modded.MinecraftJsonDocuments;
import art.arcane.wormholes.modded.MinecraftNetworkService;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftStorePaths;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.phys.Vec3;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MinecraftNetworkShutdownTest extends MinecraftTestBase {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void pendingPlayerHandoffClosesBeforeEntityServiceAndStopsNetwork() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.listenEnabled = false;
        config.serverName = "source";
        config.advertiseHostOverride = "127.0.0.1";
        config.transferMode = "proxy";
        when(runtime.server()).thenReturn(server);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), config));
        when(runtime.portals()).thenReturn(mock(MinecraftPortalRegistry.class));
        when(server.registryAccess()).thenReturn(mock(RegistryAccess.Frozen.class));
        when(runtime.stores()).thenReturn(MinecraftStorePaths.dedicated(directory.getRoot().toPath()));
        when(server.getLocalIp()).thenReturn("127.0.0.1");
        when(server.getPort()).thenReturn(25999);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getMaxPlayers()).thenReturn(10);
        when(server.getAllLevels()).thenReturn(List.of());
        MinecraftNetworkService service = new MinecraftNetworkService(runtime);
        when(runtime.network()).thenReturn(service);
        service.start();
        NetworkManager manager = service.manager();
        NetworkConfig targetConfig = new NetworkConfig();
        targetConfig.serverName = "target";
        targetConfig.listenEnabled = false;
        targetConfig.advertiseHostOverride = "127.0.0.1";
        NetworkManager target = new NetworkManager(Logger.getLogger("WormholesShutdownTest"), new NetworkManager.Options(targetConfig,
            SharedConstants.getCurrentVersion().name(), manager.buildAnnounce(1, System.currentTimeMillis()).pluginVersion(), 25998,
            directory.getRoot().toPath().resolve("target"), MinecraftJsonDocuments.INSTANCE, SharedConstants.getCurrentVersion().protocolVersion()));
        try {
            manager.statusPollInFlight.add("target");
            assertNotNull(manager.handleStatusBridgeRequest(target.createStatusBridgePacket("source", List.of())));
            ServerPlayer player = mock(ServerPlayer.class);
            UUID id = UUID.randomUUID();
            when(player.getUUID()).thenReturn(id);
            when(player.getGameProfile()).thenReturn(new GameProfile(id, "ShutdownProbe"));
            when(player.position()).thenReturn(Vec3.ZERO);
            when(player.createCommandSourceStack()).thenReturn(Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS));
            ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class,
                withSettings().extraInterfaces(ServerConnectionAccess.class));
            when(((ServerConnectionAccess) listener).wormholesConnection()).thenReturn(mock(Connection.class));
            player.connection = listener;
            MinecraftPlayerHandoffs handoffs = service.handoffs();
            assertTrue(handoffs.begin(player, "target", null, null, null));
            assertTrue(handoffs.locked(id));
            when(player.hasDisconnected()).thenReturn(true);
            service.close();
            assertFalse(handoffs.locked(id));
            assertFalse(manager.isRunning());
            assertNull(MinecraftNetworkService.forServer(server));
            service.close();
        } finally {
            target.stop();
            manager.stop();
        }
    }
}
