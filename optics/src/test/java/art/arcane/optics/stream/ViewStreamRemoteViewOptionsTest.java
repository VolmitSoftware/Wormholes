package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

final class ViewStreamRemoteViewOptionsTest {
    private static final long RESIDENT_CAPS = ViewStreamCapability.of(ViewStreamCapability.REMOTE_VIEW, ViewStreamCapability.SEAMLESS_TRAVEL);

    @Test
    void remoteViewBudgetsAreClamped() {
        ViewStreamOptions.RemoteView low = new ViewStreamOptions.RemoteView(true, 0, 0, 1);
        ViewStreamOptions.RemoteView high = new ViewStreamOptions.RemoteView(true, 99, 900, Integer.MAX_VALUE);

        assertEquals(1, low.routes());
        assertEquals(1, low.chunksPerTick());
        assertEquals(ViewStreamOptions.RemoteView.MIN_BYTES_PER_TICK, low.bytesPerTick());
        assertEquals(ViewStreamOptions.RemoteView.MAX_ROUTES, high.routes());
        assertEquals(ViewStreamOptions.RemoteView.MAX_CHUNKS_PER_TICK, high.chunksPerTick());
        assertEquals(ViewStreamOptions.RemoteView.MAX_BYTES_PER_TICK, high.bytesPerTick());
        assertEquals(new ViewStreamOptions.RemoteView(true, 2, 8, 192 * 1024), ViewStreamOptions.RemoteView.DEFAULT);
    }

    @Test
    void disabledRemoteViewWithholdsResidentCapabilitiesFromTheOffer() throws ViewStreamProtocolException {
        SessionHarness enabled = new SessionHarness(SessionHarness.defaults(), Runnable::run, 0L, List.of(TestEffects.INSTANCE, ResidentCaps.INSTANCE));
        enabled.handshake(ViewStreamCapability.of(ViewStreamCapability.PLATES));
        assertEquals(RESIDENT_CAPS, enabled.client.offer.serverCaps() & RESIDENT_CAPS);

        ViewStreamOptions defaults = SessionHarness.defaults();
        ViewStreamOptions off = new ViewStreamOptions(true, defaults.configurationHandshake(), defaults.helloGraceMillis(), defaults.maxFrameBytes(),
            defaults.ackWindowFrames(), defaults.brickCache(), defaults.destinationLight(), defaults.entityFrames(), defaults.zeroCopy(),
            defaults.standbyPrestream(), defaults.viewStats(), defaults.clientMirror(), defaults.clientRecursion(), defaults.interestGraceTicks(),
            new ViewStreamOptions.RemoteView(false, 2, 8, 192 * 1024));
        SessionHarness disabled = new SessionHarness(off, Runnable::run, 0L, List.of(TestEffects.INSTANCE, ResidentCaps.INSTANCE));
        disabled.handshake(ViewStreamCapability.of(ViewStreamCapability.PLATES));
        assertEquals(0L, disabled.client.offer.serverCaps() & RESIDENT_CAPS);
        assertTrue(ViewStreamCapability.PLATES.in(disabled.client.offer.serverCaps()));
        assertFalse(ViewStreamCapability.SEAMLESS_TRAVEL.in(disabled.client.accept.caps()));
    }

    private static final class ResidentCaps implements ViewStreamExtension<Object> {
        private static final ResidentCaps INSTANCE = new ResidentCaps();

        @Override
        public int firstId() {
            return 60;
        }

        @Override
        public int lastId() {
            return 60;
        }

        @Override
        public boolean serverbound(int id) {
            return false;
        }

        @Override
        public boolean clientbound(int id) {
            return false;
        }

        @Override
        public Class<Object> type() {
            return Object.class;
        }

        @Override
        public String name(int id) {
            return "RESIDENT";
        }

        @Override
        public int id(Object message) {
            return 60;
        }

        @Override
        public void encode(Object message, ViewStreamWriter out) throws ViewStreamProtocolException {
            throw new ViewStreamProtocolException("No resident messages");
        }

        @Override
        public Object decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
            throw new ViewStreamProtocolException("No resident messages");
        }

        @Override
        public long capabilities() {
            return RESIDENT_CAPS;
        }
    }
}
