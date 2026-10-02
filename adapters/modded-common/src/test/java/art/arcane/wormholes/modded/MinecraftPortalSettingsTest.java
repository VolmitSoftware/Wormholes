package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.portal.MirrorRotation;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.lod.LodProfile;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.TransitionProfile;
import art.arcane.wormholes.util.Direction;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftPortalSettingsTest {
    @Test
    public void settingsSyncKeepsBounceLocalAndClearsFidelityOverrides() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        when(runtime.portals()).thenReturn(mock(MinecraftPortalRegistry.class));
        MinecraftPortalSyncAccess sync = new MinecraftPortalSyncAccess(runtime);
        MinecraftPortal source = portal();
        source.setAtmosphereMode(AtmosphereMode.FULL);
        source.setAcousticsProfile(AcousticsProfile.FULL);
        source.setLodProfile(LodProfile.FAR);
        source.setBlockEntities(false);
        source.setMomentum(MomentumPolicy.of(MomentumPolicy.Mode.SCALE).withFactor(2.5));
        source.setOrientation(OrientationPolicy.LOOK);
        source.setMembrane(true);
        source.setBounce(true);
        source.setTransitionProfile(new TransitionProfile("minecraft:portal", "minecraft:block.portal.travel", 20));
        Map<String, String> wire = sync.collectSettings(source);
        assertFalse(wire.containsKey("transit.bounce"));
        MinecraftPortal destination = portal();
        sync.applySettings(destination, wire);
        assertEquals("full", destination.setting("fidelity.atmosphere"));
        assertEquals(Boolean.FALSE, destination.setting("fidelity.block_entities"));
        assertEquals(2.5D, MomentumPolicy.decode((String) destination.setting("transit.momentum")).factor(), 0D);
        assertEquals(Boolean.TRUE, destination.setting("transit.membrane"));
        assertEquals(20, TransitionProfile.decode((String) destination.setting("transit.profile")).maskOverrideTicks());
        assertNull(destination.setting("transit.bounce"));
        source.setAtmosphereMode(null);
        source.setAcousticsProfile(null);
        source.setLodProfile(null);
        source.setBlockEntities(null);
        sync.applySettings(destination, sync.collectSettings(source));
        assertNull(destination.setting("fidelity.atmosphere"));
        assertNull(destination.setting("fidelity.acoustics"));
        assertNull(destination.setting("fidelity.lod"));
        assertNull(destination.setting("fidelity.block_entities"));
    }

    @Test
    public void gatewayModeChangesClearIncompatibleLinksAndMirrorStopsTravel() {
        MinecraftPortal source = portal();
        MinecraftPortal target = portal();
        source.link(target);
        source.setType(PortalType.GATEWAY);
        assertNull(source.getDestinationId());
        assertTrue(source.linkRemote("peer", UUID.randomUUID()));
        source.setType(PortalType.PORTAL);
        assertNull(source.getDestinationId());
        source.setType(PortalType.RTP);
        source.setMirrorMode(true);
        assertEquals(PortalType.PORTAL, source.getType());
        assertTrue(source.isMirrorMode());
    }

    @Test
    public void wallMirrorQuarterTurnsSurvivePersistenceAndSettingsSync() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        when(runtime.portals()).thenReturn(mock(MinecraftPortalRegistry.class));
        MinecraftPortalSyncAccess sync = new MinecraftPortalSyncAccess(runtime);
        MinecraftProjectorPortalAccess packets = new MinecraftProjectorPortalAccess(runtime);
        for (MirrorRotation rotation : List.of(MirrorRotation.DEGREES_90, MirrorRotation.DEGREES_270)) {
            MinecraftPortal source = portal();
            source.setMirrorMode(true);
            source.setMirrorRotation(rotation);
            MinecraftPortal loaded = MinecraftPortal.read(source.write());
            assertEquals(rotation, loaded.getMirrorRotation());
            MinecraftPortal destination = portal();
            sync.applySettings(destination, sync.collectSettings(loaded));
            assertEquals(rotation, destination.getMirrorRotation());
            assertEquals(rotation.coherentFor(destination.getFrame()).getQuarterTurns(), packets.mirrorQuarterTurns(destination));
        }
    }

    @Test
    public void presetsApplyAllBudgetsAndCustomSelectionSurvivesPersistence() {
        MinecraftPortal portal = portal();
        portal.setNetworkViewQuality(NetworkViewQuality.CINEMATIC);
        assertEquals(96, portal.getNetworkViewDepth());
        assertEquals(20, portal.getNetworkViewHeartbeatTicks());
        assertEquals(2, portal.getNetworkViewEntityIntervalTicks());
        assertEquals(45, portal.getNetworkViewUnsubscribeGraceSeconds());
        portal.setNetworkViewQuality(NetworkViewQuality.CUSTOM);
        MinecraftPortal loaded = MinecraftPortal.read(portal.write());
        assertEquals(NetworkViewQuality.CUSTOM, loaded.getNetworkViewQuality());
        assertEquals(96, loaded.getNetworkViewDepth());
        loaded.setNetworkViewQuality(NetworkViewQuality.STANDARD);
        assertEquals(NetworkViewQuality.STANDARD, loaded.getNetworkViewQuality());
    }

    private static MinecraftPortal portal() {
        PortalGeometry geometry = new PortalGeometry();
        geometry.setBlocks(List.of(new GeometryVector(0, 64, 0), new GeometryVector(0, 65, 0)));
        UUID id = UUID.randomUUID();
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, geometry.getApertureCenter(), "Settings",
            PortalFrame.canonical(Direction.N), true), geometry, "minecraft:overworld", Map.of("owner", id.toString(), "type", "PORTAL")));
    }
}
