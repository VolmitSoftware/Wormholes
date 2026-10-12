package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewExtensions;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.LocalDestination;
import art.arcane.wormholes.network.client.LocalDestinationExtension;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class ClientViewLocalDestinationTest extends MinecraftTestBase {
    @Test
    public void ordinaryAndNestedDestinationsWaitForCurrentGeometryRevision() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.ALL);
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        session.handle(new ViewStreamMessage.Portal(8, 1, ClientViewHarness.geometry().withParent(7)), sink);
        assertNull(session.localDestinationWorld(7));
        assertNull(session.localDestinationWorld(8));
        destination(session, sink, 7, 1, "minecraft:overworld");
        destination(session, sink, 8, 1, "minecraft:the_nether");
        assertEquals("minecraft:overworld", session.localDestinationWorld(7));
        assertEquals("minecraft:the_nether", session.localDestinationWorld(8));
        session.handle(new ViewStreamMessage.Portal(8, 2, ClientViewHarness.geometry().withParent(7)), sink);
        assertNull(session.localDestinationWorld(8));
        destination(session, sink, 8, 1, "minecraft:the_nether");
        assertNull(session.localDestinationWorld(8));
        destination(session, sink, 8, 2, "minecraft:the_end");
        assertEquals("minecraft:the_end", session.localDestinationWorld(8));
    }

    @Test
    public void conflictingAndFutureRevisionMetadataCannotReplaceTheCurrentOwnership() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.ALL);
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        destination(session, sink, 7, 1, "minecraft:overworld");
        assertThrows(ViewStreamProtocolException.class, () -> destination(session, sink, 7, 1, "minecraft:the_nether"));
        assertThrows(ViewStreamProtocolException.class, () -> destination(session, sink, 7, 2, "minecraft:the_nether"));
        assertEquals("minecraft:overworld", session.localDestinationWorld(7));
    }

    @Test
    public void dropResetAndLateMessagesLeaveNoDestinationOwnership() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.ALL);
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        destination(session, sink, 7, 1, "minecraft:overworld");
        session.handle(new ViewStreamMessage.PortalDrop(7), sink);
        destination(session, sink, 7, 1, "minecraft:overworld");
        assertNull(session.localDestinationWorld(7));
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        assertNull(session.localDestinationWorld(7));
        destination(session, sink, 7, 1, "minecraft:the_nether");
        session.handle(new ViewStreamMessage.SessionReset(ViewStreamMessage.ResetReason.DIMENSION), sink);
        assertNull(session.localDestinationWorld(7));
    }

    @Test
    public void destinationMetadataRequiresNegotiationAndTheModdedCodecOwnsItsFreeId() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.ALL & ~ClientViewExtensions.LOCAL_DESTINATION);
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.handle(new ViewStreamMessage.Portal(7, 1, ClientViewHarness.geometry()), sink);
        assertThrows(ViewStreamProtocolException.class, () -> destination(session, sink, 7, 1, "minecraft:overworld"));
        assertNull(session.localDestinationWorld(7));
        assertTrue((session.clientCapabilities() & ClientViewExtensions.LOCAL_DESTINATION) != 0L);
        ViewStreamMessage.Extension message = LocalDestinationExtension.INSTANCE.wrap(new LocalDestination(7, 1, "minecraft:overworld"));
        assertEquals(message, MinecraftClientViewExtensions.CODEC.decodeS2C(
            MinecraftClientViewExtensions.CODEC.encodeS2C(message, 1, 0), ViewStreamCapability.ALL).message());
    }

    private static ClientViewSession session(long caps) {
        WormholesClientConfig config = new WormholesClientConfig();
        config.normalize();
        ClientViewSession session = new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
        session.accept(new ViewStreamMessage.Accept(1, caps, 20, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        return session;
    }

    private static void destination(ClientViewSession session, ClientViewSession.Sink sink, int key, int revision, String world) throws Exception {
        session.handle(LocalDestinationExtension.INSTANCE.wrap(new LocalDestination(key, revision, world)), sink);
    }
}
