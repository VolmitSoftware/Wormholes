package art.arcane.wormholes.network.client;

import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.transform.Affine;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootSourceExtensionTest {
    @Test
    void affineViewsCoexistWithAllRuntimeExtensions() throws Exception {
        ViewStreamMessage.PortalAffine message = new ViewStreamMessage.PortalAffine(7, 1L,
            Affine.IDENTITY, Affine.IDENTITY, ShapeDescriptor.FULL);
        assertEquals(message, ClientViewExtensions.CODEC.decodeS2C(
            ClientViewExtensions.CODEC.encodeS2C(message, 9, 0), ViewStreamCapability.ALL).message());
        assertEquals(41, TravelExtension.INSTANCE.firstId());
        assertEquals(64, RootSourceExtension.ID);
    }

    @Test
    void canonicalCustomWorldSourceRoundTripsWithoutChangingTheProtocol() throws Exception {
        RootSource source = new RootSource(7, 4, "wormholes:door_pockets/owner_12");
        ViewStreamMessage.Extension message = RootSourceExtension.INSTANCE.wrap(source);
        assertEquals(message, ClientViewExtensions.CODEC.decodeS2C(
            ClientViewExtensions.CODEC.encodeS2C(message, 9, 0), ViewStreamCapability.ALL).message());
        assertTrue(RootSourceExtension.INSTANCE.clientbound(RootSourceExtension.ID));
        assertFalse(RootSourceExtension.INSTANCE.serverbound(RootSourceExtension.ID));
        assertEquals(ClientViewExtensions.ROOT_SOURCE, RootSourceExtension.INSTANCE.capabilities());
    }

    @Test
    void invalidSourcePayloadIsRejectedAtTheTypedBoundary() throws ViewStreamProtocolException {
        assertThrows(IllegalArgumentException.class, () -> new RootSource(0, 1, "minecraft:overworld"));
        assertThrows(IllegalArgumentException.class, () -> new RootSource(7, 0, "minecraft:overworld"));
        assertThrows(IllegalArgumentException.class, () -> new RootSource(7, 1, "OVERWORLD"));
        ViewStreamWriter out = new ViewStreamWriter(64);
        out.i32(7);
        out.i32(1);
        out.string("world-name");
        assertThrows(ViewStreamProtocolException.class,
            () -> RootSourceExtension.INSTANCE.decode(RootSourceExtension.ID, new ViewStreamReader(out.toByteArray())));
    }

    @Test
    void seamlessTravelRequiresAuthoritativeRootSourcesButOrdinaryMeshDoesNot() {
        long mesh = ViewStreamCapability.MESH_RENDER.mask();
        long caps = mesh | ClientViewExtensions.REMOTE_VIEW | ClientViewExtensions.SEAMLESS_TRAVEL;
        ViewStreamCodec codec = new ViewStreamCodec(List.of(TravelExtension.INSTANCE, RootSourceExtension.INSTANCE));
        assertEquals(ClientViewExtensions.REMOTE_VIEW | mesh,
            codec.settle(caps));
        assertEquals(caps | ClientViewExtensions.ROOT_SOURCE,
            codec.settle(caps | ClientViewExtensions.ROOT_SOURCE));
    }
}
