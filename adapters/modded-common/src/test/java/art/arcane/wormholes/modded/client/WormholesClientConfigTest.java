package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
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
import art.arcane.wormholes.modded.clientview.MinecraftClientViewExtensions;

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
        receiver.receive(MinecraftClientViewExtensions.CODEC.encodeS2C(offer(), 1, 0), replies::add);
        receiver.receive(MinecraftClientViewExtensions.CODEC.encodeS2C(offer(), 2, 0), replies::add);
        assertTrue(replies.isEmpty());
        assertEquals(0, session.clientCapabilities());
        assertEquals(ClientViewSession.State.VANILLA, session.state());
        receiver.receive(MinecraftClientViewExtensions.CODEC.encodeS2C(new ViewStreamMessage.Accept(1, ViewStreamCapability.ALL, 20,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8), 3, 0), replies::add);
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
        receiver.receive(MinecraftClientViewExtensions.CODEC.encodeS2C(offer(), 1, 0), replies::add);
        assertEquals(1, replies.size());
        ViewStreamMessage.Hello hello = (ViewStreamMessage.Hello) MinecraftClientViewExtensions.CODEC.decodeC2S(replies.getFirst());
        assertTrue(ViewStreamCapability.MESH_RENDER.in(hello.clientCaps()));
        assertTrue(ViewStreamCapability.ENTITY_FRAMES.in(hello.clientCaps()));
        assertTrue(ViewStreamCapability.CLIENT_MIRROR.in(hello.clientCaps()));
    }

    private static ClientViewSession session(WormholesClientConfig config) {
        return new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
    }

    private static ViewStreamMessage.Offer offer() {
        return new ViewStreamMessage.Offer(ViewStreamLimits.WIRE_VERSION, 1, ViewStreamCapability.ALL,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 0);
    }
}
