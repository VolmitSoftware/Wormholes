package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

public final class RtpPlateIdentityTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000e3");

    @Test
    public void identicalRoutesShareOneIdentity() {
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        assertEquals(RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 30.5D, frame, 2L),
            RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 30.5D, frame, 2L));
    }

    @Test
    public void everyInputDistinguishesTheIdentity() {
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        long base = RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 30.5D, frame, 2L);
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(UUID.fromString("00000000-0000-0000-0000-0000000000e4"), 30.5D, 70.0D, 30.5D, frame, 2L));
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(WORLD, 31.5D, 70.0D, 30.5D, frame, 2L));
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 71.0D, 30.5D, frame, 2L));
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 31.5D, frame, 2L));
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 30.5D, PortalFrame.canonical(Direction.S), 2L));
        assertNotEquals(base, RtpProjectionGeometry.plateIdentity(WORLD, 30.5D, 70.0D, 30.5D, frame, 3L));
    }

    @Test
    public void identityIsNeverZero() {
        for (long revision = 0L; revision < 4096L; revision++) {
            assertNotEquals(0L, RtpProjectionGeometry.plateIdentity(new UUID(0L, 0L), 0.0D, 0.0D, 0.0D, PortalFrame.canonical(Direction.N), revision));
        }
    }
}
