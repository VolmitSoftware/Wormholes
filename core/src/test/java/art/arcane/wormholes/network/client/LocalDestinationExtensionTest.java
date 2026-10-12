package art.arcane.wormholes.network.client;

import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDestinationExtensionTest {
    private static final ViewStreamCodec CODEC = new ViewStreamCodec(List.of(LocalDestinationExtension.INSTANCE));

    @Test
    void destinationRevisionAndCustomDimensionRoundTripInItsNegotiatedCodec() throws Exception {
        LocalDestination destination = new LocalDestination(7, 4, "wormholes:door_pockets/owner_12");
        ViewStreamMessage.Extension message = LocalDestinationExtension.INSTANCE.wrap(destination);
        assertEquals(message, CODEC.decodeS2C(
            CODEC.encodeS2C(message, 9, 0), ViewStreamCapability.ALL).message());
        assertEquals(65, LocalDestinationExtension.ID);
        assertTrue(LocalDestinationExtension.INSTANCE.clientbound(LocalDestinationExtension.ID));
        assertFalse(LocalDestinationExtension.INSTANCE.serverbound(LocalDestinationExtension.ID));
        assertEquals(ClientViewExtensions.LOCAL_DESTINATION, LocalDestinationExtension.INSTANCE.capabilities());
        assertEquals(ClientViewExtensions.LOCAL_DESTINATION,
            CODEC.capabilities() & ClientViewExtensions.LOCAL_DESTINATION);
        assertEquals(0L, ClientViewExtensions.CODEC.capabilities() & ClientViewExtensions.LOCAL_DESTINATION);
    }

    @Test
    void invalidLocalDestinationPayloadsAreRejectedAtTheTypedBoundary() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new LocalDestination(0, 1, "minecraft:overworld"));
        assertThrows(IllegalArgumentException.class, () -> new LocalDestination(7, 0, "minecraft:overworld"));
        assertThrows(IllegalArgumentException.class, () -> new LocalDestination(7, 1, "OVERWORLD"));
        assertThrows(IllegalArgumentException.class, () -> new LocalDestination(7, 1, "minecraft:" + "a".repeat(257)));
        ViewStreamWriter out = new ViewStreamWriter(64);
        out.i32(7);
        out.i32(1);
        out.string("remote-world");
        assertThrows(ViewStreamProtocolException.class, () -> LocalDestinationExtension.INSTANCE.decode(
            LocalDestinationExtension.ID, new ViewStreamReader(out.toByteArray())));
        assertThrows(ViewStreamProtocolException.class,
            () -> CODEC.encodeC2S(LocalDestinationExtension.INSTANCE.wrap(new LocalDestination(7, 1, "minecraft:overworld"))));
    }
}
