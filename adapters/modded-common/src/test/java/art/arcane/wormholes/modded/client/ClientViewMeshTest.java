package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.render.plate.PlateBox;
import net.minecraft.core.SectionPos;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClientViewMeshTest extends MinecraftTestBase {
    @Test
    public void streamedSectionsAreAcknowledgedWithoutChangingWorldBlocks() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ClientViewCapability.ALL);
        begin(harness);
        ClientViewMessage.MeshSection update = new ClientViewMessage.MeshSection(1, 1, 0, 4, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE);
        harness.receive(update, 0);
        harness.tick(1.5, 65.5, 15.5);

        assertNotNull(harness.session.meshes().view(1).section(SectionPos.asLong(0, 4, 0)));
        assertTrue(harness.sent.contains(new ClientViewMessage.MeshAck(1, 1, 0, 4, 0, 1)));
        assertEquals(0, harness.tick.applier().writes());
        assertEquals(0, harness.tick.overlay().size());
        assertEquals(0, harness.tick.protocolFailures());
    }

    @Test
    public void portalDropDiscardsSectionsAndIgnoresDelayedData() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ClientViewCapability.ALL);
        begin(harness);
        harness.receive(new ClientViewMessage.MeshSection(1, 1, 0, 4, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE), 0);
        harness.tick(1.5, 65.5, 15.5);
        harness.receive(new ClientViewMessage.PortalDrop(1), 0);
        harness.receive(new ClientViewMessage.MeshSection(1, 1, 0, 4, 0, 2, 3, Brick.single(0, 3), SectionBiomes.NONE), 0);
        harness.tick(1.5, 65.5, 15.5);

        assertNull(harness.session.meshes().view(1));
        assertEquals(0, harness.session.meshes().bytes());
        assertEquals(0, harness.tick.protocolFailures());
    }

    @Test
    public void destinationAtmosphereWorksWithoutAVoxelSweep() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ClientViewCapability.ALL);
        begin(harness);
        harness.receive(new ClientViewMessage.Atmosphere(1, 18000L, 0.8F, 0.5F,
            ClientViewMessage.Atmosphere.FLAG_TIME | ClientViewMessage.Atmosphere.FLAG_WEATHER), 0);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        assertEquals(1, harness.tick.atmosphere().dominant());
        assertEquals(0.8F, harness.scene.rain, 0.0F);
        assertEquals(18000L, harness.scene.clock);
        harness.receive(new ClientViewMessage.PortalDrop(1), 0);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        assertEquals(0, harness.tick.atmosphere().dominant());
    }

    @Test
    public void memoryFailureRefusesOnlyCurrentGenerationOnceAndKeepsNativeOwnership() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ClientViewCapability.ALL);
        begin(harness);
        harness.receive(new ClientViewMessage.Portal(2, 1, ClientViewHarness.geometry()), 0);
        harness.receive(new ClientViewMessage.MeshBegin(2, 1, new PlateBox(-16, 48, -16, 48, 48, 48), 27), 0);
        harness.receive(new ClientViewMessage.MeshBegin(1, 2, new PlateBox(-16, 48, -16, 48, 48, 48), 27), 0);
        harness.tick(1.5, 65.5, 15.5);
        harness.session.refuseMesh(1, 1, harness.tick);
        assertNotNull(harness.session.meshes().view(1));
        assertTrue(harness.sent.stream().noneMatch(message -> message instanceof ClientViewMessage.PlateRefused));
        harness.session.refuseMesh(1, 2, harness.tick);
        harness.session.refuseMesh(1, 2, harness.tick);
        assertNull(harness.session.meshes().view(1));
        assertNotNull(harness.session.portal(1));
        assertEquals(ClientViewSession.State.CLIENT_VIEW, harness.session.state());
        assertEquals(ClientViewSession.MeshFailure.MEMORY, harness.session.meshFailure(1));
        assertNotNull(harness.session.meshes().view(2));
        assertEquals(1, harness.sent.stream().filter(message -> message.equals(new ClientViewMessage.PlateRefused(1, 2))).count());
    }

    @Test
    public void exhaustedNativeMemoryRetriesAtTheSameDistanceWithoutPacketProjection() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ClientViewCapability.ALL);
        begin(harness);
        harness.tick(1.5, 65.5, 15.5);
        double distance = harness.session.portal(1).geometry().depthBlocks();
        harness.session.meshes().otherMemory(harness.config::plateMemoryBytes);
        harness.receive(new ClientViewMessage.MeshSection(1, 1, 0, 4, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE), 0);
        harness.tick(1.5, 65.5, 15.5);
        assertEquals(ClientViewSession.State.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.session.nativeSelected());
        assertEquals(ClientViewSession.MeshFailure.MEMORY, harness.session.meshFailure(1));
        assertEquals(distance, harness.session.portal(1).geometry().depthBlocks(), 0);
        assertEquals(0, harness.surface.changedCells());
        harness.session.meshes().otherMemory(() -> 0L);
        harness.receive(new ClientViewMessage.MeshBegin(1, 2, new PlateBox(-16, 48, -16, 48, 48, 48), 27), 0);
        harness.receive(new ClientViewMessage.MeshSection(1, 2, 0, 4, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE), 0);
        harness.tick(1.5, 65.5, 15.5);
        assertNull(harness.session.meshFailure(1));
        assertNotNull(harness.session.meshes().view(1).section(SectionPos.asLong(0, 4, 0)));
        assertTrue(harness.sent.contains(new ClientViewMessage.MeshAck(1, 2, 0, 4, 0, 1)));
        assertEquals(distance, harness.session.portal(1).geometry().depthBlocks(), 0);
        assertEquals(0, harness.surface.changedCells());
    }

    @Test
    public void nativeMirrorsAndNestedGeometryWaitForMeshDataWithoutWritingWorldBlocks() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ClientViewCapability.ALL);
        ClientPortalGeometry child = mirror(List.of()).withParent(1);
        harness.receive(new ClientViewMessage.Portal(1, 1, mirror(List.of(child))), 0);
        for (int key = 2; key <= 17; key++) {
            harness.receive(new ClientViewMessage.Portal(key, 1, child), ClientViewProtocol.FLAG_LAST);
        }
        for (int tick = 0; tick < 4; tick++) {
            harness.tick(1.5, 65.5, 15.5);
        }
        assertEquals(17, harness.session.portals().size());
        assertNull(harness.session.meshes().view(1));
        assertEquals(0, harness.surface.changedCells());
        assertEquals(0, harness.surface.writes.size());
        assertEquals(0, harness.tick.applier().writes());
        assertEquals(0, harness.tick.overlay().size());
        for (ClientPortal portal : harness.session.portals().values()) {
            assertNull(harness.tick.mirror(portal.portalKey()));
            assertNull(portal.content());
            assertNull(portal.sweep());
        }
        harness.receive(new ClientViewMessage.MeshBegin(1, 1, new PlateBox(-16, 48, -16, 48, 48, 48), 27), 0);
        harness.receive(new ClientViewMessage.MeshSection(1, 1, 0, 4, 0, 1, 3, Brick.single(0, 0), SectionBiomes.NONE), 0);
        harness.tick(1.5, 65.5, 15.5);
        assertNotNull(harness.session.meshes().view(1).section(SectionPos.asLong(0, 4, 0)));
        assertEquals(0, harness.surface.writes.size());
    }

    @Test
    public void nativeAcceptanceDiscardsQueuedPlateStreamAndStandbyHandles() throws Exception {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ClientViewMessage.PlatePatch(1, 1, 2, List.of()), 0);
        harness.receive(new ClientViewMessage.PlateHandle(1, 2, 999L), ClientViewProtocol.FLAG_LAST);
        harness.session.accept(new ClientViewMessage.Accept(2, ClientViewCapability.ALL, 20,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 9L, 8));
        harness.tick(1.5, 65.5, 15.5);
        assertNotNull(harness.session.portal(1));
        assertNull(harness.session.portal(1).content());
        assertEquals(0, harness.session.plates().size());
        assertEquals(0, harness.session.plates().bytes());
        assertEquals(0, harness.tick.protocolFailures());
        assertEquals(0, harness.surface.writes.size());
        assertTrue(harness.sent.stream().noneMatch(message -> message instanceof ClientViewMessage.BrickMiss
            || message instanceof ClientViewMessage.PlateRefused));
    }

    @Test
    public void nativeAcceptanceRestoresExistingLegacyProjectionAndKeepsGeometryPending() throws Exception {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ClientViewMessage.Portal(2, 1, mirror(List.of())), ClientViewProtocol.FLAG_LAST);
        harness.tick(1.5, 65.5, 15.5);
        assertTrue(harness.surface.changedCells() > 0);
        assertNotNull(harness.tick.mirror(2));
        harness.session.accept(new ClientViewMessage.Accept(2, ClientViewCapability.ALL, 20,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 9L, 8));
        harness.tick(1.5, 65.5, 15.5);
        assertEquals(2, harness.session.portals().size());
        assertEquals(0, harness.surface.changedCells());
        assertEquals(0, harness.tick.overlay().size());
        assertEquals(0, harness.session.plates().bytes());
        assertNull(harness.tick.mirror(2));
        assertNull(harness.session.portal(1).content());
        assertNull(harness.session.portal(2).content());
        int writes = harness.surface.writes.size();
        harness.tick(1.5, 65.5, 15.5);
        assertEquals(writes, harness.surface.writes.size());
    }

    private static ClientPortalGeometry mirror(List<ClientPortalGeometry> nested) {
        ClientPortalGeometry source = ClientViewHarness.geometry();
        return new ClientPortalGeometry(source.originX(), source.originY(), source.originZ(), source.facing(), source.frontSide(),
            source.quarterTurns(), true, source.apertureWidth(), source.apertureHeight(), source.apertureMask(),
            source.nearPlanePadding(), source.aperturePadding(), source.frustumCullingRatio(), source.depthBlocks(), 3,
            source.blackoutPolicy(), source.blackoutState(), source.maskAirPolicy(), source.lightingPolicy(), source.fidelityFlags(),
            source.kind(), 0, 0L, nested);
    }

    private static void begin(ClientViewHarness harness) throws Exception {
        harness.receive(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:stone"))), 0);
        harness.receive(new ClientViewMessage.Portal(1, 1, ClientViewHarness.geometry()), 0);
        harness.receive(new ClientViewMessage.MeshBegin(1, 1, new PlateBox(-16, 48, -16, 48, 48, 48), 27), 0);
    }
}
