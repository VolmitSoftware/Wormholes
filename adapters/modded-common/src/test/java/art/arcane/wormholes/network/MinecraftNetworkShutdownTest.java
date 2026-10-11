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
import art.arcane.wormholes.modded.MinecraftViewServer;
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
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MinecraftNetworkShutdownTest extends MinecraftTestBase {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void childFailuresStillStopTransportRemoveRegistrationsAndAllowRestart() throws Exception {
        RuntimeException handoffFailure = new IllegalStateException("handoff cleanup failed");
        RuntimeException entityFailure = new IllegalStateException("entity cleanup failed");
        try (MockedConstruction<MinecraftPlayerHandoffs> handoffs = mockConstruction(MinecraftPlayerHandoffs.class, (service, context) -> {
            if (context.getCount() == 1) {
                doThrow(handoffFailure).when(service).close();
            }
        }); MockedConstruction<MinecraftEntityTransfers> entities = mockConstruction(MinecraftEntityTransfers.class, (service, context) -> {
            if (context.getCount() == 1) {
                doThrow(entityFailure).when(service).close();
            }
        }); MockedConstruction<MinecraftViewServer> views = mockConstruction(MinecraftViewServer.class)) {
            ShutdownFixture fixture = new ShutdownFixture();
            fixture.service.start();
            NetworkManager original = fixture.service.manager();
            try {
                assertTrue(original.isRunning());
                assertSame(original, registeredServers().get(fixture.server));
                RuntimeException failure = assertThrows(RuntimeException.class, fixture.service::close);
                assertSame(handoffFailure, failure);
                assertEquals(1, failure.getSuppressed().length);
                assertSame(entityFailure, failure.getSuppressed()[0]);
                verify(handoffs.constructed().getFirst()).close();
                verify(entities.constructed().getFirst()).close();
                verify(views.constructed().getFirst()).close();
                assertFalse(original.isRunning());
                assertNull(MinecraftNetworkService.forServer(fixture.server));
                assertNull(registeredServers().get(fixture.server));
                assertThrows(NullPointerException.class, fixture.service::manager);
                fixture.service.close();
                try (ServerSocket probe = new ServerSocket()) {
                    probe.bind(new InetSocketAddress("127.0.0.1", fixture.port));
                }
                fixture.service.start();
                NetworkManager replacement = fixture.service.manager();
                assertNotSame(original, replacement);
                assertTrue(replacement.isRunning());
                assertSame(replacement, registeredServers().get(fixture.server));
                assertEquals(fixture.port, replacement.getBoundListenPort());
                fixture.service.close();
                assertFalse(replacement.isRunning());
                assertNull(registeredServers().get(fixture.server));
            } finally {
                try {
                    fixture.service.close();
                } finally {
                    original.stop();
                }
            }
        }
    }

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

    @SuppressWarnings("unchecked")
    private static Map<MinecraftServer, NetworkManager> registeredServers() throws ReflectiveOperationException {
        Field servers = MinecraftNetworkService.class.getDeclaredField("SERVERS");
        servers.setAccessible(true);
        return (Map<MinecraftServer, NetworkManager>) servers.get(null);
    }

    private final class ShutdownFixture {
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final MinecraftNetworkService service = new MinecraftNetworkService(runtime);
        private final int port;

        private ShutdownFixture() throws Exception {
            try (ServerSocket available = new ServerSocket(0)) {
                port = available.getLocalPort();
            }
            PlayerList players = mock(PlayerList.class);
            WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
            NetworkConfig config = new NetworkConfig();
            config.enabled = true;
            config.listenEnabled = true;
            config.listenPort = port;
            config.serverName = "shutdown-fixture";
            config.advertiseHostOverride = "127.0.0.1";
            config.transport.udsEnabled = false;
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
            when(runtime.network()).thenReturn(service);
        }
    }
}
