package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.Portal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.math.Face;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.optics.volume.LodProfile;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.TransitionProfile;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MinecraftSubsystemMenusTest {
    @Test
    public void portalKeepsAccessGroupsAndDirectoryFlag() {
        MinecraftPortal portal = portal();
        assertTrue(portal.isListed());
        assertTrue(portal.getGroups().isEmpty());
        assertFalse(portal.clearGroups());
        assertTrue(portal.addGroup(" group.one "));
        assertFalse(portal.addGroup("group.one"));
        assertFalse(portal.addGroup(" "));
        assertEquals(List.of("group.one"), portal.getGroups());
        assertTrue(portal.clearGroups());
        assertTrue(portal.getGroups().isEmpty());
        portal.setListed(false);
        assertFalse(portal.isListed());
        MinecraftPortal restored = MinecraftPortal.read(portal.write());
        assertFalse(restored.isListed());
    }

    @Test
    public void portalReadsBackItsTransitAndFidelityOverrides() {
        MinecraftPortal portal = portal();
        assertNull(portal.getMomentum());
        assertNull(portal.getOrientation());
        assertFalse(portal.isMembrane());
        assertFalse(portal.isBounce());
        assertTrue(portal.getTransitionProfile().isNone());
        assertNull(portal.getAtmosphereMode());
        assertNull(portal.getAcousticsProfile());
        assertNull(portal.getLodProfile());
        assertNull(portal.getBlockEntities());

        MomentumPolicy momentum = MomentumPolicy.of(MomentumPolicy.Mode.values()[1]).withFactor(2.5D);
        TransitionProfile profile = TransitionProfile.NONE.withArrivalSound("minecraft:block.bell.use").withMaskOverrideTicks(40);
        portal.setMomentum(momentum);
        portal.setOrientation(OrientationPolicy.values()[1]);
        portal.setMembrane(true);
        portal.setBounce(true);
        portal.setTransitionProfile(profile);
        portal.setAtmosphereMode(AtmosphereMode.values()[0]);
        portal.setAcousticsProfile(AcousticsProfile.values()[0]);
        portal.setLodProfile(LodProfile.values()[0]);
        portal.setBlockEntities(Boolean.FALSE);

        assertEquals(momentum, portal.getMomentum());
        assertEquals(OrientationPolicy.values()[1], portal.getOrientation());
        assertTrue(portal.isMembrane());
        assertTrue(portal.isBounce());
        assertEquals(profile, portal.getTransitionProfile());
        assertEquals(AtmosphereMode.values()[0], portal.getAtmosphereMode());
        assertEquals(AcousticsProfile.values()[0], portal.getAcousticsProfile());
        assertEquals(LodProfile.values()[0], portal.getLodProfile());
        assertEquals(Boolean.FALSE, portal.getBlockEntities());
    }

    private static MinecraftPortal portal() {
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(new Vec3d(0, 64, 0), new Vec3d(0, 65, 0)));
        UUID id = UUID.randomUUID();
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, geometry.getApertureCenter(), "Access",
            Frame.canonical(Face.N), true), geometry, "minecraft:overworld", Map.of("owner", UUID.randomUUID().toString(), "type", "PORTAL")));
    }
}
