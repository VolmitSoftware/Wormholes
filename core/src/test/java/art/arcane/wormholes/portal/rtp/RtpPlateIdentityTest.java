package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

public final class RtpPlateIdentityTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000e3");

    @Test
    public void identicalRoutesShareOneIdentity() {
        Frame frame = Frame.canonical(Face.N);
        assertEquals(RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 30.5D, frame, 2L),
            RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 30.5D, frame, 2L));
    }

    @Test
    public void everyInputDistinguishesTheIdentity() {
        Frame frame = Frame.canonical(Face.N);
        long base = RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 30.5D, frame, 2L);
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(UUID.fromString("00000000-0000-0000-0000-0000000000e4"), 30.5D, 70.0D, 30.5D, frame, 2L));
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(WORLD, 31.5D, 70.0D, 30.5D, frame, 2L));
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 71.0D, 30.5D, frame, 2L));
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 31.5D, frame, 2L));
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 30.5D, Frame.canonical(Face.S), 2L));
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 30.5D, frame, 3L));
    }

    @Test
    public void identityIsNeverZero() {
        for (long revision = 0L; revision < 4096L; revision++) {
            assertNotEquals(0L, RtpProjectionGeometry.plateIdentity(new UUID(0L, 0L), 0.0D, 0.0D, 0.0D, Frame.canonical(Face.N), revision));
        }
    }
}
