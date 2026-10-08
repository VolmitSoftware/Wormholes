package art.arcane.wormholes.modded.client;

import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientTravelCrossingTest {
    @Test
    public void interpolatedCameraSegmentCrossesOnlyTheAuthoritativeOpenAperture() {
        assertTrue(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(),
            new Vec3(0.5, 1.62, 0.3), new Vec3(0.5, 1.62, 0.6)));
        assertFalse(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(),
            new Vec3(0.5, 1.62, 0.3), new Vec3(0.5, 1.62, 0.4)));
        assertFalse(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(),
            new Vec3(2.5, 1.62, 0.3), new Vec3(2.5, 1.62, 0.6)));
        assertFalse(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(true, 59),
            new Vec3(0.5, 1.62, 0.3), new Vec3(0.5, 1.62, 0.6)));
    }

    @Test
    public void reverseCrossingUsesItsAuthorizedSideAndDoesNotCrossTwice() {
        assertTrue(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(false, 63),
            new Vec3(0.5, 1.62, 0.7), new Vec3(0.5, 1.62, 0.4)));
        assertFalse(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(),
            new Vec3(0.5, 1.62, 0.7), new Vec3(0.5, 1.62, 0.4)));
        assertFalse(ClientSeamlessTravel.crossed(ClientTravelTestFixtures.geometry(),
            new Vec3(0.5, 1.62, 0.5), new Vec3(0.5, 1.62, 0.7)));
    }
}
