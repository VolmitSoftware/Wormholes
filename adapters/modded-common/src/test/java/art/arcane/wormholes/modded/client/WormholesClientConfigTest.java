package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WormholesClientConfigTest extends MinecraftTestBase {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void startupConfigurationSelectsNativeOrStandardBlockPackets() throws Exception {
        Path configDirectory = directory.newFolder().toPath();
        WormholesClientConfig defaults = WormholesClientConfig.load(configDirectory);
        assertEquals(WormholesClientConfig.Renderer.NATIVE, defaults.rendererMode());
        Path configFile = configDirectory.resolve(WormholesClientConfig.FILE_NAME);
        assertTrue(Files.readString(configFile).contains("renderer = \"native\""));
        Files.writeString(configFile, "renderer = \"block-packets\"\n");
        WormholesClientConfig compatibility = WormholesClientConfig.load(configDirectory);
        assertEquals(WormholesClientConfig.Renderer.BLOCK_PACKETS, compatibility.rendererMode());
        Files.writeString(configFile, "renderer = \"unknown\"\n");
        assertThrows(IllegalArgumentException.class, () -> WormholesClientConfig.load(configDirectory));
    }

    @Test
    public void blockPacketRendererDoesNotNegotiateOrAcceptClientView() throws Exception {
        WormholesClientConfig config = new WormholesClientConfig();
        config.renderer = "block-packets";
        config.normalize();
        ClientViewSession session = session(config);
        ClientViewReceiver receiver = new ClientViewReceiver(session);
        List<byte[]> replies = new ArrayList<>();
        receiver.receive(ClientViewCodec.encodeS2C(offer(), 1, 0), replies::add);
        receiver.receive(ClientViewCodec.encodeS2C(offer(), 2, 0), replies::add);
        assertTrue(replies.isEmpty());
        assertEquals(0, session.clientCapabilities());
        assertEquals(ClientViewSession.State.VANILLA, session.state());
        receiver.receive(ClientViewCodec.encodeS2C(new ClientViewMessage.Accept(1, ClientViewCapability.ALL, 20,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 7L, 8), 3, 0), replies::add);
        assertEquals(ClientViewSession.State.VANILLA, session.state());
        assertFalse(session.active());
        assertNull(session.acceptMessage());
        assertEquals(0L, receiver.decodeFailures());
    }

    @Test
    public void nativeRendererStillNegotiatesTheMeshAndSceneCapabilities() throws Exception {
        ClientViewSession session = session(new WormholesClientConfig());
        ClientViewReceiver receiver = new ClientViewReceiver(session);
        List<byte[]> replies = new ArrayList<>();
        receiver.receive(ClientViewCodec.encodeS2C(offer(), 1, 0), replies::add);
        assertEquals(1, replies.size());
        ClientViewMessage.Hello hello = (ClientViewMessage.Hello) ClientViewCodec.decodeC2S(replies.getFirst());
        assertTrue(ClientViewCapability.MESH_RENDER.in(hello.clientCaps()));
        assertTrue(ClientViewCapability.ENTITY_FRAMES.in(hello.clientCaps()));
        assertTrue(ClientViewCapability.CLIENT_MIRROR.in(hello.clientCaps()));
    }

    private static ClientViewSession session(WormholesClientConfig config) {
        return new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
    }

    private static ClientViewMessage.Offer offer() {
        return new ClientViewMessage.Offer(ClientViewProtocol.WIRE_VERSION, 1, ClientViewCapability.ALL,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 0);
    }
}
