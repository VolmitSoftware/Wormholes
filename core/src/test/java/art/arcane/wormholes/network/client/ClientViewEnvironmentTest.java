package art.arcane.wormholes.network.client;

import art.arcane.wormholes.network.WireCodec;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.RemoteViewCodec;
import art.arcane.wormholes.render.view.RemoteProjectionView;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import static org.mockito.Mockito.mock;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.render.client.session.ClientViewSceneFx;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientViewEnvironmentTest {
    @Test
    void peerEnvironmentRoundTripRetainsDestinationSkyWeatherAndDimension() throws Exception {
        WireMessage.ViewEnvironment sent = new WireMessage.ViewEnvironment(UUID.randomUUID(), ClientViewFixtures.environment());
        WireMessage received = WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(WireCodec.encodeFrame(sent))));
        assertEquals(sent, received);
    }

    @Test
    void peerEnvironmentUsesTheViewingPortalTransformAndExpiresWithItsSubscription() {
        RemoteViewCache<String, Object, Object> cache = new RemoteViewCache<>(mock(RemoteViewCodec.class), RemoteViewCache.Options.defaults());
        UUID portal = UUID.randomUUID();
        RemoteViewCache.RemoteView<String, Object, Object> remote = cache.getOrCreate("peer", portal);
        RemoteProjectionView<String, String, Object, Object> view = new RemoteProjectionView<>(remote,
            new RemoteProjectionView.Options<>("minecraft:air", value -> value));
        assertNull(view.environment(ClientViewEnvironment.Transform.IDENTITY));
        ClientViewEnvironment destination = ClientViewFixtures.environment();
        cache.applyEnvironment("peer", portal, destination);
        ClientViewEnvironment projected = view.environment(ClientViewEnvironment.Transform.IDENTITY);
        assertEquals(destination.sky(), projected.sky());
        assertEquals(destination.lighting(), projected.lighting());
        assertEquals(destination.fog(), projected.fog());
        assertEquals(destination.dimension(), projected.dimension());
        assertEquals(destination.gameTime(), projected.gameTime());
        assertEquals(destination.world(), projected.world());
        assertEquals(ClientViewEnvironment.Transform.IDENTITY, projected.transform());
        cache.remove("peer", portal);
        assertNull(cache.getOrCreate("peer", portal).environment());
    }

    @Test
    void completeSnapshotPreservesFloatColorsAndNegativeTransform() throws Exception {
        ClientViewMessage.Environment message = new ClientViewMessage.Environment(71, ClientViewFixtures.environment());
        assertEquals(message, ClientViewCodec.decodeS2C(ClientViewCodec.encodeS2C(message, 3, 0), ClientViewCapability.ALL).message());
        assertEquals(1.25F, message.environment().lighting().blockTint().blue());
        assertEquals(new ClientViewEnvironment.World("test:destination", 72000L, "minecraft:plains", 63, 7, 15, 256, true, 0.1F, ClientViewEnvironment.EyeMedium.WATER, true), message.environment().world());
    }

    @Test
    void rejectsMalformedValuesAndNonOrthogonalTransforms() throws Exception {
        ClientViewWriter out = new ClientViewWriter();
        ClientViewEnvironmentCodec.write(out, ClientViewFixtures.environment());
        byte[] invalidSky = out.toByteArray();
        invalidSky[8] = 3;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewEnvironmentCodec.read(new ClientViewReader(invalidSky)));
        byte[] invalidMedium = out.toByteArray();
        invalidMedium[invalidMedium.length - 2] = 4;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewEnvironmentCodec.read(new ClientViewReader(invalidMedium)));
        byte[] invalidCeiling = out.toByteArray();
        invalidCeiling[invalidCeiling.length - 7] = 2;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewEnvironmentCodec.read(new ClientViewReader(invalidCeiling)));
        byte[] invalidFixedTime = out.toByteArray();
        invalidFixedTime[invalidFixedTime.length - 1] = 2;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewEnvironmentCodec.read(new ClientViewReader(invalidFixedTime)));
        assertThrows(IllegalArgumentException.class, () -> new ClientViewEnvironment.Color(Float.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClientViewEnvironment.World("test:destination", 0, "minecraft:plains", 63, 7, 15, 256, true, Float.NaN, ClientViewEnvironment.EyeMedium.NONE, false));
        assertThrows(IllegalArgumentException.class, () -> new ClientViewEnvironment.World("missing_namespace", 0, "minecraft:plains", 63, 7, 15, 256, true, 0.1F, ClientViewEnvironment.EyeMedium.NONE, false));
        assertThrows(IllegalArgumentException.class, () -> new ClientViewEnvironment.World("test:../ world", 0, "minecraft:plains", 63, 7, 15, 256, true, 0.1F, ClientViewEnvironment.EyeMedium.NONE, false));
        assertThrows(IllegalArgumentException.class, () -> new ClientViewEnvironment.Transform(Direction.N, Direction.S, Direction.U,
            new GeometryVector(0, 0, 0)));
    }

    @Test
    void rootAndNestedSamplesHaveIndependentCadenceAndResendOnFull() {
        EnvironmentEffects effects = new EnvironmentEffects();
        ClientViewSceneFx<String> source = new ClientViewSceneFx<String>(effects);
        UUID portal = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        assertNotNull(source.environment("viewer", portal, 1, 10, false));
        assertNotNull(source.nestedEnvironment("viewer", parent, portal, 2, 10, false));
        assertNull(source.environment("viewer", portal, 1, 11, false));
        assertNull(source.nestedEnvironment("viewer", parent, portal, 2, 11, false));
        assertEquals(2, effects.samples);
        assertNull(source.environment("viewer", portal, 1, 15, false));
        assertNotNull(source.environment("viewer", portal, 1, 16, true));
        assertEquals(4, effects.samples);
    }

    private static final class EnvironmentEffects implements ClientViewSceneFx.Effects<String> {
        private int samples;

        @Override
        public List<ClientViewMessage.FxEmitter> emitters(String observer, UUID portal, long tick) {
            return List.of();
        }

        @Override
        public ClientViewSceneFx.Sample atmosphere(String observer, UUID portal, long tick) {
            return null;
        }

        @Override
        public ClientViewEnvironment environment(String observer, UUID portal, long tick) {
            samples++;
            return ClientViewFixtures.environment();
        }

        @Override
        public ClientViewEnvironment nestedEnvironment(String observer, UUID parent, UUID portal, long tick) {
            samples++;
            return ClientViewFixtures.environment();
        }
    }
}
