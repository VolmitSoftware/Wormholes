package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.mesh.DestinationPolicy;
import art.arcane.wormholes.network.mesh.SelectionStrategy;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftNetworkToolsTest extends MinecraftTestBase {
    @Test
    public void policyParserPreservesStrategyThresholdsAndRejectsInvalidCandidates() {
        UUID destination = UUID.randomUUID();
        DestinationPolicy policy = MinecraftNetworkTools.parsePolicy("alpha:" + destination + ":3,beta:lobby", "least_loaded", 2, 18.5, false);
        assertEquals(SelectionStrategy.LEAST_LOADED, policy.strategy());
        assertEquals(destination, policy.candidates().getFirst().portalId());
        assertEquals(3, policy.candidates().getFirst().weight());
        assertEquals("lobby", policy.candidates().getLast().tag());
        assertEquals(2, policy.minHeadroom());
        assertEquals(18.5D, policy.minTps(), 0.001D);
        assertFalse(policy.queue());
        assertEquals(policy, DestinationPolicy.decode(policy.encode()));
        assertNull(MinecraftNetworkTools.parsePolicy("", "FIRST_AVAILABLE", 1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> MinecraftNetworkTools.parsePolicy("alpha:lobby:0", "FIRST_AVAILABLE", 1, 0, true));
    }

    @Test
    public void nativeNetworkCommandsRegisterAndApplyGatewayPolicy() throws Exception {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        MinecraftNetworkService service = mock(MinecraftNetworkService.class);
        NetworkManager network = mock(NetworkManager.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        MinecraftPortal portal = mock(MinecraftPortal.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(runtime.access()).thenReturn(access);
        when(access.permission(any(CommandSourceStack.class), anyString())).thenReturn(true);
        when(runtime.network()).thenReturn(service);
        when(service.manager()).thenReturn(network);
        when(network.activeConfig()).thenReturn(new NetworkConfig());
        when(network.peers()).thenReturn(new ArrayList<>());
        when(runtime.portals()).thenReturn(portals);
        when(portals.snapshot()).thenReturn(List.of(portal));
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getName()).thenReturn("gateway");
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        new MinecraftNetworkTools(runtime).registerCommands(dispatcher);
        assertNotNull(dispatcher.getRoot().getChild("wormholes").getChild("server").getChild("connect"));
        for (String command : List.of("members", "pending", "accept", "reject", "versions", "drain", "policy")) {
            assertNotNull(dispatcher.getRoot().getChild("wormholes").getChild("network").getChild(command));
        }
        assertEquals(1, dispatcher.execute("wormholes network members", source));
        UUID destination = UUID.randomUUID();
        assertEquals(1, dispatcher.execute("wormholes network policy gateway \"alpha:" + destination + "\" LEAST_LOADED 2 18 true", source));
        verify(portal).setMeshPolicy(MinecraftNetworkTools.parsePolicy("alpha:" + destination, "LEAST_LOADED", 2, 18, true).encode());
        verify(portals).save(portal);
    }
}
