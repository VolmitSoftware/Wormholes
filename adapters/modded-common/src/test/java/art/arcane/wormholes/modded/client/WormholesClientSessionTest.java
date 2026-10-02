package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WormholesClientSessionTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void pauseAndWindowFocusGateParticleCreationWithoutChangingClientSettings() throws Exception {
        WormholesClient client = WormholesClient.initialize(folder.newFolder().toPath(), bytes -> { });
        ClientViewHarness harness = new ClientViewHarness();
        client.session().accept(new ClientViewMessage.Accept(1, ClientViewCapability.ALL, 20,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        client.tickState().attach(new Object(), harness.surface, harness.scene);
        Minecraft minecraft = mock(Minecraft.class);
        ClientViewMessage.Fx burst = new ClientViewMessage.Fx(ClientViewProtocol.WORLD_FX_KEY,
            List.of(ClientViewEmitters.burst("minecraft:reverse_portal", 1.5D, 65.0D, 10.5D, 12, 0.4D, 0.6D, 0.4D)));
        try {
            when(minecraft.isPaused()).thenReturn(true);
            when(minecraft.isWindowActive()).thenReturn(true);
            client.receive(ClientViewCodec.encodeS2C(burst, 1, ClientViewProtocol.FLAG_LAST), null);
            client.tick(minecraft);
            assertEquals(0, harness.scene.particles.size());
            when(minecraft.isPaused()).thenReturn(false);
            when(minecraft.isWindowActive()).thenReturn(false);
            client.receive(ClientViewCodec.encodeS2C(burst, 2, ClientViewProtocol.FLAG_LAST), null);
            client.tick(minecraft);
            assertEquals(0, harness.scene.particles.size());
            client.receive(ClientViewCodec.encodeS2C(burst, 3, ClientViewProtocol.FLAG_LAST), null);
            when(minecraft.isWindowActive()).thenReturn(true);
            client.tick(minecraft);
            assertEquals(0, harness.scene.particles.size());
            client.receive(ClientViewCodec.encodeS2C(burst, 4, ClientViewProtocol.FLAG_LAST), null);
            client.tick(minecraft);
            assertEquals(List.of("burst minecraft:reverse_portal x12"), harness.scene.particles);
        } finally {
            client.disconnected();
            ProjectionOverlay.deactivate(harness.tick.overlay());
        }
    }

    @Test
    public void reconfiguringTheConnectionStartsAFreshSession() throws IOException {
        WormholesClient client = WormholesClient.initialize(folder.newFolder().toPath(), bytes -> { });
        ClientViewSession previous = client.session();
        previous.offer(new ClientViewMessage.Offer(ClientViewProtocol.WIRE_VERSION, 1, ClientViewCapability.ALL,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 0L));
        previous.accept(new ClientViewMessage.Accept(1, ClientViewCapability.ALL, 20, ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        assertEquals(ClientViewSession.State.CLIENT_VIEW, previous.state());

        WormholesClient.reconfiguring();

        assertSame(client, WormholesClient.instance());
        assertNotSame(previous, client.session());
        assertEquals(ClientViewSession.State.INIT, client.session().state());
    }
}
