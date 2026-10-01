package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.view.EntityDeltaCodec;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.portal.effects.PortalAnimation;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.acoustics.AcousticsBridge;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientViewSceneTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @After
    public void deactivate() {
        ProjectionOverlay overlay = ProjectionOverlay.active();
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
    }

    @Test
    public void destinationLightLandsOnOverlaidCellsAndLeavesWithThem() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        ProjectionOverlay overlay = harness.tick.overlay();
        long cell = overlay.keys().getLong(0);
        int x = ProjectionCellKey.unpackX(cell);
        int y = ProjectionCellKey.unpackY(cell);
        int z = ProjectionCellKey.unpackZ(cell);
        assertEquals(ClientViewHarness.DESTINATION_BLOCK_LIGHT, harness.surface.blockLight(x, y, z));
        assertEquals(ClientViewHarness.DESTINATION_SKY_LIGHT, harness.surface.skyLight(x, y, z));
        int outsideZ = 15;
        assertFalse(overlay.get(ProjectionCellKey.pack(x, y, outsideZ)) != null);
        assertEquals(ClientViewHarness.LOCAL_BLOCK_LIGHT, harness.surface.blockLight(x, y, outsideZ));
        assertEquals(ClientViewHarness.LOCAL_SKY_LIGHT, harness.surface.skyLight(x, y, outsideZ));
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 5.0D);
        assertEquals(0, overlay.size());
        assertEquals(ClientViewHarness.LOCAL_BLOCK_LIGHT, harness.surface.blockLight(x, y, z));
        assertEquals(ClientViewHarness.LOCAL_SKY_LIGHT, harness.surface.skyLight(x, y, z));
        assertEquals(0, harness.tick.light().size());
    }

    @Test
    public void realLightChangesUnderAProjectionShowThroughAtOnceAndAfterTheProjectionLeaves() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        long cell = harness.tick.overlay().keys().getLong(0);
        int x = ProjectionCellKey.unpackX(cell);
        int y = ProjectionCellKey.unpackY(cell);
        int z = ProjectionCellKey.unpackZ(cell);
        assertEquals(ClientViewHarness.DESTINATION_SKY_LIGHT, harness.surface.skyLight(x, y, z));
        int realBlock = 5;
        int realSky = 11;
        harness.surface.replaceLight(x >> 4, y >> 4, z >> 4, realBlock, realSky);
        assertEquals(ClientViewHarness.DESTINATION_SKY_LIGHT, harness.surface.skyLight(x, y, z));
        assertEquals(ClientViewHarness.DESTINATION_BLOCK_LIGHT, harness.surface.blockLight(x, y, z));
        int outsideZ = 15;
        assertEquals(realSky, harness.surface.skyLight(x, y, outsideZ));
        assertEquals(realBlock, harness.surface.blockLight(x, y, outsideZ));
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(ClientViewHarness.DESTINATION_SKY_LIGHT, harness.surface.skyLight(x, y, z));
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 5.0D);
        assertEquals(0, harness.tick.overlay().size());
        assertEquals(realSky, harness.surface.skyLight(x, y, z));
        assertEquals(realBlock, harness.surface.blockLight(x, y, z));
        assertEquals(0, harness.tick.light().size());
    }

    @Test
    public void destinationAirOverRealAirInsideTheConeCarriesDestinationLightAndGivesItBack() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        int y = (int) Math.floor(ClientViewHarness.EYE_Y);
        ClientPortal portal = harness.session.portal(ClientViewHarness.PORTAL_KEY);
        assertTrue(portal.sweep().applied(ClientViewHarness.AIR_COLUMN_X, y, ClientViewHarness.REAL_AIR_Z));
        assertTrue(harness.tick.overlay().get(ProjectionCellKey.pack(ClientViewHarness.AIR_COLUMN_X, y, ClientViewHarness.REAL_AIR_Z)) == null);
        assertEquals(ClientViewHarness.DESTINATION_SKY_LIGHT, harness.surface.skyLight(ClientViewHarness.AIR_COLUMN_X, y, ClientViewHarness.REAL_AIR_Z));
        assertEquals(ClientViewHarness.DESTINATION_BLOCK_LIGHT, harness.surface.blockLight(ClientViewHarness.AIR_COLUMN_X, y, ClientViewHarness.REAL_AIR_Z));
        int outsideZ = 15;
        assertEquals(ClientViewHarness.LOCAL_SKY_LIGHT, harness.surface.skyLight(ClientViewHarness.AIR_COLUMN_X, y, outsideZ));
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 5.0D);
        assertEquals(0, harness.tick.overlay().size());
        assertEquals(ClientViewHarness.LOCAL_SKY_LIGHT, harness.surface.skyLight(ClientViewHarness.AIR_COLUMN_X, y, ClientViewHarness.REAL_AIR_Z));
        assertEquals(ClientViewHarness.LOCAL_BLOCK_LIGHT, harness.surface.blockLight(ClientViewHarness.AIR_COLUMN_X, y, ClientViewHarness.REAL_AIR_Z));
        assertEquals(0, harness.tick.light().size());
    }

    @Test
    public void destinationSkyDarkenFromAtmosphereRebasesProjectedSkyLightWithoutTakingTheSky() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        long cell = harness.tick.overlay().keys().getLong(0);
        int x = ProjectionCellKey.unpackX(cell);
        int y = ProjectionCellKey.unpackY(cell);
        int z = ProjectionCellKey.unpackZ(cell);
        assertEquals(ClientViewHarness.DESTINATION_SKY_LIGHT, harness.surface.skyLight(x, y, z));
        harness.receive(new ClientViewMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 0L, 0.0F, 0.0F,
            ClientViewMessage.Atmosphere.withSkyDarken(0, 4)), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(ClientViewHarness.DESTINATION_SKY_LIGHT - 4, harness.surface.skyLight(x, y, z));
        assertEquals(0, harness.tick.atmosphere().dominant());
        assertEquals(0.0F, harness.scene.rain, 0.0F);
    }

    @Test
    public void localSkyDarkeningIsRebasedLikeTheServerOverlay() {
        assertEquals(15, ClientLightPatches.skyWithLocalDarken(9, 11));
        assertEquals(9, ClientLightPatches.blockWithLocalDarken(9, 2, 11));
        assertEquals(2, ClientLightPatches.blockWithLocalDarken(9, 2, 0));
        assertEquals(15, ClientLightPatches.blockWithLocalDarken(15, 15, 0));
    }

    @Test
    public void entitiesSpawnInsideTheConeFollowDeltasAndLeaveWithThePortal() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        long cell = harness.tick.overlay().keys().getLong(0);
        UUID inside = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        EntityVisual stand = stand(inside, ProjectionCellKey.unpackX(cell) + 0.5D, ProjectionCellKey.unpackY(cell), ProjectionCellKey.unpackZ(cell) + 0.5D);
        EntityVisual far = stand(outside, 500.5D, 64.0D, 500.5D);
        harness.receive(new ClientViewMessage.EntityFrame(ClientViewHarness.PORTAL_KEY, 1, List.of(stand, far), List.of(inside, outside), true),
            ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        ClientProjectedEntities entities = harness.tick.entities();
        assertEquals(2, entities.tracked());
        assertEquals(1, entities.spawned());
        int entityId = entities.entityId(ClientViewHarness.PORTAL_KEY, inside);
        assertTrue(ClientEntityIds.isProjected(entityId));
        assertEquals(stand.x(), harness.scene.entities.get(entityId).x(), 1.0E-9D);
        EntityVisual moved = stand(inside, stand.x(), stand.y(), stand.z() - 0.25D);
        EntityVisual delta = EntityDeltaCodec.buildDelta(moved, stand, 2, EntityDeltaCodec.computeMask(moved, stand));
        harness.receive(new ClientViewMessage.EntityFrame(ClientViewHarness.PORTAL_KEY, 2, List.of(delta), List.of(), false),
            ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(1, harness.scene.moves);
        assertEquals(moved.z(), harness.scene.entities.get(entityId).z(), 1.0E-3D);
        harness.receive(new ClientViewMessage.EntityFrame(ClientViewHarness.PORTAL_KEY, 3, List.of(), List.of(inside), true), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(1, entities.tracked());
        harness.receive(new ClientViewMessage.PortalDrop(ClientViewHarness.PORTAL_KEY), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(0, entities.tracked());
        assertTrue(harness.scene.entities.isEmpty());
        assertTrue(harness.scene.events.contains("remove " + entityId));
    }

    @Test
    public void entityFramesForUnknownPortalsAreIgnored() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        UUID id = UUID.randomUUID();
        harness.receive(new ClientViewMessage.EntityFrame(99, 1, List.of(stand(id, 1.5D, 64.0D, 5.5D)), List.of(id), true), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(0, harness.tick.entities().tracked());
        assertEquals(1L, harness.session.ignoredSceneMessages());
    }

    @Test
    public void emittersFireOnTheirCadenceAndOneShotsFireOnce() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        ClientViewMessage.FxEmitter rim = new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.RIM_DUST, "", 0.0D, 64.0D, 10.0D, 0x00FF00, 1.0F,
            5, 1);
        ClientViewMessage.FxEmitter sparks = new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.SURFACE, ClientViewEmitters.SPARK_PARTICLE,
            0.0D, 64.0D, 10.0D, 0.3F, 0.0F, 1, ClientViewEmitters.SURFACE_OPEN_FLAG | (1 << ClientViewEmitters.SURFACE_INTERVAL_SHIFT));
        ClientViewMessage.FxEmitter chime = new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.SOUND, "minecraft:block.note_block.chime",
            1.0D, 65.0D, 10.0D, 1.0F, 1.0F, 0, 0);
        harness.receive(new ClientViewMessage.Fx(ClientViewHarness.PORTAL_KEY, List.of(rim, sparks, chime)), ClientViewProtocol.FLAG_LAST);
        for (int i = 0; i < 10; i++) {
            harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        }
        long dust = harness.scene.particles.stream().filter(entry -> entry.equals("dust ff00")).count();
        long mycelium = harness.scene.particles.stream().filter(entry -> entry.equals(ClientViewEmitters.SPARK_PARTICLE + " x4")).count();
        assertEquals(2L, dust);
        assertEquals(10L, mycelium);
        assertEquals(1L, harness.scene.events.stream().filter(entry -> entry.startsWith("sound ")).count());
        assertEquals("one-shots never join the continuous set", 2, harness.tick.fx().emitters());
        harness.receive(new ClientViewMessage.Fx(ClientViewHarness.PORTAL_KEY, List.of(rim, sparks)), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(1L, harness.scene.events.stream().filter(entry -> entry.startsWith("sound ")).count());
        harness.receive(new ClientViewMessage.Fx(ClientViewHarness.PORTAL_KEY, List.of(rim, sparks, chime)), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals("every arriving one-shot fires", 2L, harness.scene.events.stream().filter(entry -> entry.startsWith("sound ")).count());
        assertEquals(2, harness.tick.fx().emitters());
        harness.receive(new ClientViewMessage.Fx(ClientViewHarness.PORTAL_KEY, List.of()), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(0, harness.tick.fx().emitters());
    }

    @Test
    public void worldOneShotsRunAnimationsAndBurstsWithoutAPortal() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        ClientViewMessage.FxEmitter rim = new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.RIM_DUST, "", 0.0D, 64.0D, 10.0D, 0x00FF00, 1.0F,
            5, 1);
        harness.receive(new ClientViewMessage.Fx(ClientViewHarness.PORTAL_KEY, List.of(rim)), ClientViewProtocol.FLAG_LAST);
        ClientViewMessage.FxEmitter open = ClientViewEmitters.animation(PortalAnimation.Mode.OPEN, new GeometryVector(1.5D, 65.0D, 10.5D),
            new GeometryVector(3.0D, 4.0D, 0.0D), VisualQualityProfile.BALANCED);
        ClientViewMessage.FxEmitter sync = ClientViewEmitters.burst("minecraft:reverse_portal", 1.5D, 65.0D, 10.5D, 12, 0.4D, 0.6D, 0.4D);
        ClientViewMessage.FxEmitter glitch = ClientViewEmitters.animation(PortalAnimation.Mode.GLITCH, new GeometryVector(1.5D, 65.0D, 10.5D),
            new GeometryVector(1.0D, 1.0D, 1.0D), VisualQualityProfile.BALANCED);
        ClientViewMessage.FxEmitter chime = ClientViewEmitters.sound(new AcousticsBridge.Playback("minecraft:block.stone.break",
            AcousticsProfile.SoundClass.WORLD, 1.5D, 65.0D, 10.5D, 1.0F, 0.8F), 0);
        harness.receive(new ClientViewMessage.Fx(ClientViewProtocol.WORLD_FX_KEY, List.of(open, sync, glitch, chime)), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);

        assertEquals(0L, harness.session.ignoredSceneMessages());
        assertEquals("world one-shots leave the portal emitters alone", 1, harness.tick.fx().emitters());
        assertEquals("the glitch finished on arrival and the opening keeps running", 1, harness.tick.fx().animations());
        assertTrue(harness.scene.particles.contains("burst minecraft:reverse_portal x12"));
        assertTrue(harness.scene.particles.contains("animation " + PortalAnimation.Particle.WHITE_FLASH));
        assertTrue(harness.scene.particles.contains("animation " + PortalAnimation.Particle.PORTAL));
        assertTrue(harness.scene.events.contains("sound minecraft:block.stone.break WORLD"));
        for (int i = 0; i < 60; i++) {
            harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        }
        assertTrue("the opening reaches its impact", harness.scene.particles.contains("animation " + PortalAnimation.Particle.PURPLE_FLASH));
        assertEquals(0, harness.tick.fx().animations());
        assertTrue("animation sounds stay on the server", harness.scene.events.stream().noneMatch(entry -> entry.contains("sonic_boom")));
    }

    @Test
    public void destinationSkyHoldsOnlyWhileThePortalDominates() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ClientViewMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 18000L, 0.8F, 0.5F,
            ClientViewMessage.Atmosphere.FLAG_TIME | ClientViewMessage.Atmosphere.FLAG_WEATHER), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        assertEquals(ClientViewHarness.PORTAL_KEY, harness.tick.atmosphere().dominant());
        assertEquals(0.8F, harness.scene.rain, 0.0F);
        assertEquals(18000L, harness.scene.clock);
        harness.scene.gameTime += 20L;
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        assertEquals(18020L, harness.scene.clock);
        for (int i = 0; i < ClientAtmosphere.WEATHER_BURST_TICKS * 40 && harness.tick.atmosphere().weatherParticles() == 0L; i++) {
            harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        }
        assertTrue(harness.tick.atmosphere().weatherParticles() > 0L);
        assertTrue(harness.scene.particles.contains(ClientAtmosphere.RAIN_PARTICLE + " x1"));
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 30.0D);
        assertEquals(0, harness.tick.atmosphere().dominant());
        assertEquals(0.0F, harness.scene.rain, 0.0F);
        assertEquals(1000L + 20L, harness.scene.clock);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        harness.receive(new ClientViewMessage.PortalDrop(ClientViewHarness.PORTAL_KEY), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        assertEquals(0.0F, harness.scene.rain, 0.0F);
        assertFalse(harness.tick.atmosphere().holds(ClientViewHarness.PORTAL_KEY));
    }

    @Test
    public void localWeatherAndTimeThatChangeWhileAPortalDominatesSurviveTheRestore() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ClientViewMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 18000L, 0.8F, 0.5F,
            ClientViewMessage.Atmosphere.FLAG_WEATHER), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        assertEquals(ClientViewHarness.PORTAL_KEY, harness.tick.atmosphere().dominant());
        assertEquals(0.8F, harness.scene.rain, 0.0F);

        harness.scene.weather(0.3F, 0.1F);
        harness.scene.clock = 6000L;
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        assertEquals(0.8F, harness.scene.rain, 0.0F);
        assertEquals(0.5F, harness.scene.thunder, 0.0F);

        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 30.0D);
        assertEquals(0, harness.tick.atmosphere().dominant());
        assertEquals(0.3F, harness.scene.rain, 0.0F);
        assertEquals(0.1F, harness.scene.thunder, 0.0F);
        assertEquals(6000L, harness.scene.clock);
    }

    @Test
    public void aTimeOnlyPortalNeverWritesTheLocalWeatherBack() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ClientViewMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 18000L, 0.0F, 0.0F,
            ClientViewMessage.Atmosphere.FLAG_TIME), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        assertEquals(ClientViewHarness.PORTAL_KEY, harness.tick.atmosphere().dominant());
        assertEquals(18000L, harness.scene.clock);

        harness.scene.weather(0.6F, 0.0F);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 30.0D);

        assertEquals(0, harness.tick.atmosphere().dominant());
        assertEquals(0.6F, harness.scene.rain, 0.0F);
        assertEquals(1000L, harness.scene.clock);
    }

    private static EntityVisual stand(UUID id, double x, double y, double z) {
        return EntityVisual.full(id, "minecraft:armor_stand", x, y, z, 1.975D, 0.0D, 0.0D, -1.0D, 180.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "",
            "", null, null, EntityVisual.EMPTY, EntityVisual.EMPTY, 1);
    }
}
