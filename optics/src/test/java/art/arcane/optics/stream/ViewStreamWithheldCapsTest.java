package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

final class ViewStreamWithheldCapsTest {
    @Test
    void withheldCapabilitiesNeverReachTheOfferOrTheSession() throws ViewStreamProtocolException {
        SessionHarness offered = new SessionHarness(SessionHarness.defaults(), Runnable::run, 0L, List.of(TestEffects.INSTANCE));
        offered.handshake(ViewStreamCapability.of(ViewStreamCapability.PLATES) | TestEffects.CAPABILITY);
        assertEquals(TestEffects.CAPABILITY, offered.client.offer.serverCaps() & TestEffects.CAPABILITY);
        assertEquals(TestEffects.CAPABILITY, offered.client.accept.caps() & TestEffects.CAPABILITY);

        ViewStreamOptions defaults = SessionHarness.defaults();
        ViewStreamOptions withheld = new ViewStreamOptions(true, defaults.configurationHandshake(), defaults.helloGraceMillis(),
            defaults.maxFrameBytes(), defaults.ackWindowFrames(), defaults.brickCache(), defaults.destinationLight(), defaults.entityFrames(),
            defaults.zeroCopy(), defaults.standbyPrestream(), defaults.viewStats(), defaults.clientMirror(), defaults.clientRecursion(),
            defaults.interestGraceTicks(), TestEffects.CAPABILITY);
        SessionHarness disabled = new SessionHarness(withheld, Runnable::run, 0L, List.of(TestEffects.INSTANCE));
        disabled.handshake(ViewStreamCapability.of(ViewStreamCapability.PLATES) | TestEffects.CAPABILITY);
        assertEquals(0L, disabled.client.offer.serverCaps() & TestEffects.CAPABILITY);
        assertTrue(ViewStreamCapability.PLATES.in(disabled.client.offer.serverCaps()));
        assertEquals(0L, disabled.client.accept.caps() & TestEffects.CAPABILITY);
    }

    @Test
    void withheldCapabilitiesAreMaskedToKnownBits() {
        ViewStreamOptions defaults = SessionHarness.defaults();
        ViewStreamOptions options = new ViewStreamOptions(true, defaults.configurationHandshake(), defaults.helloGraceMillis(),
            defaults.maxFrameBytes(), defaults.ackWindowFrames(), defaults.brickCache(), defaults.destinationLight(), defaults.entityFrames(),
            defaults.zeroCopy(), defaults.standbyPrestream(), defaults.viewStats(), defaults.clientMirror(), defaults.clientRecursion(),
            defaults.interestGraceTicks(), -1L);
        assertEquals(ViewStreamCapability.ALL, options.withheldCaps());
    }
}
