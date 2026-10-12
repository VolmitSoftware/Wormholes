package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.transform.Affine;
import art.arcane.wormholes.modded.MinecraftTestBase;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

public class ClientViewAffineTest extends MinecraftTestBase {
    private static final ApertureDescriptor GEOMETRY = new ApertureDescriptor(0, 0, 0, Face.S.ordinal(), true, 0, false, 2, 2,
        new long[] {15L}, ShapeDescriptor.FULL, 0, 0, 1, 16, 0, 0, 0, 0, 0, 0, 0, -0.5D, 0, 1, List.of());

    @Test
    public void staleSamplesCannotRewindAndDropRemovesTheTransform() throws Exception {
        ClientViewSession session = session(ViewStreamCapability.of(ViewStreamCapability.MESH_RENDER, ViewStreamCapability.AFFINE_PORTALS));
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        session.handle(new ViewStreamMessage.Portal(1, 1, GEOMETRY), sink);
        ViewStreamMessage.PortalAffine latest = sample(4L);
        session.handle(latest, sink);
        session.handle(sample(3L), sink);
        session.handle(sample(4L), sink);
        assertSame(latest, session.affine(1));

        session.handle(new ViewStreamMessage.PortalDrop(1), sink);
        assertNull(session.affine(1));
        session.handle(sample(5L), sink);
        assertNull(session.affine(1));
    }

    @Test
    public void clientRejectsAffineSamplesWithoutBothNativeCapabilities() throws Exception {
        ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        for (long caps : new long[] {ViewStreamCapability.MESH_RENDER.mask(), ViewStreamCapability.AFFINE_PORTALS.mask()}) {
            ClientViewSession session = session(caps);
            session.handle(new ViewStreamMessage.Portal(1, 1, GEOMETRY), sink);
            assertThrows(ViewStreamProtocolException.class, () -> session.handle(sample(1L), sink));
            assertNull(session.affine(1));
        }
    }

    private static ClientViewSession session(long caps) {
        ClientViewSession session = new ClientViewSession(new WormholesClientConfig(), new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
        session.accept(new ViewStreamMessage.Accept(1, caps, 20, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        return session;
    }

    private static ViewStreamMessage.PortalAffine sample(long revision) {
        return new ViewStreamMessage.PortalAffine(1, revision, Affine.translation(revision, 0, 0), Affine.IDENTITY, ShapeDescriptor.FULL);
    }
}
