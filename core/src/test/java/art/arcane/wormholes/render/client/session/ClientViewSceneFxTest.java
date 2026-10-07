package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.network.client.FxMessage;

class ClientViewSceneFxTest {
    private static final UUID PORTAL = UUID.nameUUIDFromBytes("rtp".getBytes());

    @Test
    void emitterSetsTravelOnlyWhenTheyChange() {
        List<FxMessage.FxEmitter> emitters = new ArrayList<FxMessage.FxEmitter>();
        Scene scene = new Scene(emitters);
        ClientViewSceneFx<String> fx = new ClientViewSceneFx<String>(scene);
        assertNull(effects(fx, 2, 1L, true), "no emitters and nothing to clear");
        emitters.add(rim(1.0D, 0x00FF00));
        FxMessage.Fx first = effects(fx, 2, 2L, false);
        assertEquals(List.of(rim(1.0D, 0x00FF00)), first.emitters());
        assertNull(effects(fx, 2, 3L, false));
        emitters.set(0, rim(1.0D, 0xFF0000));
        assertEquals(0xFF0000, (int) effects(fx, 2, 4L, false).emitters().get(0).paramA());
        emitters.clear();
        assertTrue(effects(fx, 2, 5L, false).emitters().isEmpty(), "an emptied set clears the client");
        assertNull(effects(fx, 2, 6L, false));
        emitters.add(rim(2.0D, 0x00FF00));
        effects(fx, 2, 7L, false);
        assertNotNull(effects(fx, 2, 8L, true), "a full request resends the current set");
        assertNotNull(effects(fx, 5, 9L, false), "a new portal key starts fresh");
    }

    @Test
    void atmosphereFollowsWeatherChangesClockDriftAndLoss() {
        Scene scene = new Scene(List.of());
        ClientViewSceneFx<String> fx = new ClientViewSceneFx<String>(scene);
        scene.sample = new ClientViewSceneFx.Sample(1000L, true, 0.0F, 0.0F, ViewStreamMessage.Atmosphere.FLAG_TIME);
        ViewStreamMessage.Atmosphere first = fx.atmosphere("observer", PORTAL, 1, 10L, true);
        assertEquals(1000L, first.dayTime());
        scene.sample = new ClientViewSceneFx.Sample(1010L, true, 0.0F, 0.0F, ViewStreamMessage.Atmosphere.FLAG_TIME);
        assertNull(fx.atmosphere("observer", PORTAL, 1, 20L, false), "a running clock is extrapolated by the client");
        scene.sample = new ClientViewSceneFx.Sample(1010L, true, 0.5F, 0.0F, ViewStreamMessage.Atmosphere.FLAG_TIME
            | ViewStreamMessage.Atmosphere.FLAG_WEATHER);
        assertEquals(0.5F, fx.atmosphere("observer", PORTAL, 1, 20L, false).rain());
        scene.sample = new ClientViewSceneFx.Sample(6000L, true, 0.5F, 0.0F, ViewStreamMessage.Atmosphere.FLAG_TIME
            | ViewStreamMessage.Atmosphere.FLAG_WEATHER);
        assertEquals(6000L, fx.atmosphere("observer", PORTAL, 1, 21L, false).dayTime(), "a clock jump is resent");
        scene.sample = new ClientViewSceneFx.Sample(6000L + ClientViewSceneFx.ATMOSPHERE_RESYNC_TICKS, true, 0.5F, 0.0F,
            ViewStreamMessage.Atmosphere.FLAG_TIME | ViewStreamMessage.Atmosphere.FLAG_WEATHER);
        assertNotNull(fx.atmosphere("observer", PORTAL, 1, 21L + ClientViewSceneFx.ATMOSPHERE_RESYNC_TICKS, false), "periodic resync");
        scene.sample = null;
        ViewStreamMessage.Atmosphere restore = fx.atmosphere("observer", PORTAL, 1, 900L, false);
        assertEquals(ViewStreamMessage.Atmosphere.FLAG_RESTORE, restore.flags());
        assertNull(fx.atmosphere("observer", PORTAL, 1, 901L, false));
    }

    private static FxMessage.FxEmitter rim(double x, int color) {
        return new FxMessage.FxEmitter(FxMessage.FxKind.RIM_DUST, "", x, 64.0D, 20.0D, color, 1.0F, 5, 1);
    }

    private static FxMessage.Fx effects(ClientViewSceneFx<String> fx, int key, long tick, boolean full) {
        ViewStreamMessage.Extension message = fx.effects("observer", PORTAL, key, tick, full);
        return message == null ? null : (FxMessage.Fx) message.payload();
    }

    private static final class Scene implements ClientViewSceneFx.Effects<String> {
        private final List<FxMessage.FxEmitter> emitters;
        private ClientViewSceneFx.Sample sample;

        private Scene(List<FxMessage.FxEmitter> emitters) {
            this.emitters = emitters;
        }

        @Override
        public List<FxMessage.FxEmitter> emitters(String observer, UUID portal, long tick) {
            return emitters;
        }

        @Override
        public ClientViewSceneFx.Sample atmosphere(String observer, UUID portal, long tick) {
            return sample;
        }
    }
}
