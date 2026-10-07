package art.arcane.wormholes.modded;

import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class MinecraftPortalArrivalTest extends MinecraftTestBase {
    private static final UUID EXIT = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    @Test
    public void exitDuringCooldownRemainsRecordedWhenPlayerReturnsAfterExpiry() {
        MinecraftPortalRegistry.Arrival arrival = new MinecraftPortalRegistry.Arrival(EXIT, 1_000L, 60_000L);
        assertFalse(arrival.release(true, 100L));
        assertFalse(arrival.release(false, 500L));
        assertTrue(arrival.blocks(EXIT, true, 999L));
        assertTrue(arrival.release(true, 1_000L));
        assertFalse(arrival.blocks(EXIT, true, 1_000L));
    }

    @Test
    public void remainingInArrivalApertureStillBlocksImmediateBounce() {
        MinecraftPortalRegistry.Arrival arrival = new MinecraftPortalRegistry.Arrival(EXIT, 1_000L, 60_000L);
        assertFalse(arrival.release(true, 999L));
        assertFalse(arrival.release(true, 1_000L));
        assertTrue(arrival.blocks(EXIT, true, 1_000L));
        assertFalse(arrival.blocks(OTHER, true, 1_000L));
        assertTrue(arrival.release(false, 1_001L));
    }

    @Test
    public void leavingArrivalDoesNotBypassConfiguredCooldownOnOtherRoutes() {
        MinecraftPortalRegistry.Arrival arrival = new MinecraftPortalRegistry.Arrival(EXIT, 1_000L, 60_000L);
        assertFalse(arrival.release(false, 100L));
        assertTrue(arrival.blocks(OTHER, false, 999L));
        assertFalse(arrival.blocks(OTHER, false, 1_000L));
    }

    @Test
    public void entitiesWithoutAPlayerAboardCrossAgainWithoutTheTeleportCooldown() {
        Entity pig = mock(Entity.class);
        Entity boat = mock(Entity.class);
        ServerPlayer rider = mock(ServerPlayer.class);
        assertEquals(0L, MinecraftPortalRegistry.arrivalCooldown(List.of(pig), true, 1_000L));
        assertEquals(1_000L, MinecraftPortalRegistry.arrivalCooldown(List.of(pig), false, 1_000L));
        assertEquals(1_000L, MinecraftPortalRegistry.arrivalCooldown(List.of(boat, rider), true, 1_000L));
        assertEquals(1_000L, MinecraftPortalRegistry.arrivalCooldown(List.of(rider), true, 1_000L));
    }

    @Test
    public void abandonedArrivalStillExpiresWhilePlayerRemainsInside() {
        MinecraftPortalRegistry.Arrival arrival = new MinecraftPortalRegistry.Arrival(EXIT, 1_000L, 60_000L);
        assertFalse(arrival.release(true, 59_999L));
        assertTrue(arrival.release(true, 60_000L));
    }

    @Test
    public void arrivalRulesCarryThePortalOrientationAndTheEffectiveMomentumCeiling() {
        TravelMessage.ArrivalRules look = MinecraftPortalRegistry.arrivalRules(OrientationPolicy.LOOK,
            MomentumPolicy.of(MomentumPolicy.Mode.CLAMP), true, 4.0D);

        assertEquals(OrientationRule.LOOK, look.orientation());
        assertTrue(look.gravityFlip());
        assertEquals(MomentumRule.Mode.CLAMP, look.momentum().mode());
        assertEquals(4.0D, look.momentum().maxSpeed(), 0.0D);
    }

    @Test
    public void portalMomentumCeilingOverridesTheConfiguredDefault() {
        TravelMessage.ArrivalRules scaled = MinecraftPortalRegistry.arrivalRules(OrientationPolicy.SNAP,
            new MomentumPolicy(MomentumPolicy.Mode.SCALE, 2.0D, 1.5D, new Vec3d(0, 0, 0)), false, 4.0D);

        assertEquals(OrientationRule.SNAP, scaled.orientation());
        assertFalse(scaled.gravityFlip());
        assertEquals(2.0D, scaled.momentum().factor(), 0.0D);
        assertEquals(1.5D, scaled.momentum().maxSpeed(), 0.0D);
    }
}
