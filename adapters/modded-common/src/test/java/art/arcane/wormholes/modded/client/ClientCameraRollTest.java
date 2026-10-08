package art.arcane.wormholes.modded.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientCameraRollTest {
    @Test
    public void theEaseStartsAtTheCountedRollAndDecaysMonotonicallyToLevel() {
        ClientCameraRoll roll = new ClientCameraRoll();
        roll.start(90.0F, 0.35D, 1_000L);

        assertEquals(-90.0F, roll.degrees(1_000L), 1.0E-4F);
        float previous = Math.abs(roll.degrees(1_000L));
        for (long now = 1_010L; now <= 1_350L; now += 10L) {
            float current = Math.abs(roll.degrees(now));
            assertTrue(now + ": " + current + " after " + previous, current <= previous);
            previous = current;
        }
        assertEquals(0.0F, roll.degrees(1_350L), 0.0F);
        assertFalse(roll.active(1_351L));
    }

    @Test
    public void theCurveIsCubicOut() {
        ClientCameraRoll roll = new ClientCameraRoll();
        roll.start(-40.0F, 1.0D, 0L);

        assertEquals(40.0F * 0.125F, roll.degrees(500L), 1.0E-3F);
    }

    @Test
    public void zeroSecondsOrNegligibleRollDisableTheEase() {
        ClientCameraRoll disabled = new ClientCameraRoll();
        disabled.start(90.0F, 0.0D, 0L);
        assertFalse(disabled.active(0L));
        assertEquals(0.0F, disabled.degrees(0L), 0.0F);
        ClientCameraRoll small = new ClientCameraRoll();
        small.start(0.4F, 0.35D, 0L);
        assertFalse(small.active(0L));
    }

    @Test
    public void cancellingOnRollbackLevelsTheCameraAtOnce() {
        ClientCameraRoll roll = new ClientCameraRoll();
        roll.start(180.0F, 0.35D, 0L);
        assertTrue(roll.active(10L));

        roll.cancel();

        assertFalse(roll.active(10L));
        assertEquals(0.0F, roll.degrees(10L), 0.0F);
    }
}
