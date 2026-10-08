package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.shape.ShapeDescriptor;
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
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        begin(harness);
        ViewStreamMessage.MeshSection update = new ViewStreamMessage.MeshSection(1, 1, 0, 4, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE);
        harness.receive(update, 0);
        harness.tick(1.5, 65.5, 15.5);

        assertNotNull(harness.session.meshes().view(1).section(SectionPos.asLong(0, 4, 0)));
        assertTrue(harness.sent.contains(new ViewStreamMessage.MeshAck(1, 1, 0, 4, 0, 1)));
        assertEquals(0, harness.tick.applier().writes());
        assertEquals(0, harness.tick.overlay().size());
        assertEquals(0, harness.tick.protocolFailures());
    }

    @Test
    public void portalDropDiscardsSectionsAndIgnoresDelayedData() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        begin(harness);
        harness.receive(new ViewStreamMessage.MeshSection(1, 1, 0, 4, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE), 0);
        harness.tick(1.5, 65.5, 15.5);
        harness.receive(new ViewStreamMessage.PortalDrop(1), 0);
        harness.receive(new ViewStreamMessage.MeshSection(1, 1, 0, 4, 0, 2, 3, Brick.single(0, 3), SectionBiomes.NONE), 0);
        harness.tick(1.5, 65.5, 15.5);

        assertNull(harness.session.meshes().view(1));
        assertEquals(0, harness.session.meshes().bytes());
        assertEquals(0, harness.tick.protocolFailures());
    }

    @Test
    public void destinationAtmosphereWorksWithoutAVoxelSweep() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        begin(harness);
        harness.receive(new ViewStreamMessage.Atmosphere(1, 18000L, 0.8F, 0.5F,
            ViewStreamMessage.Atmosphere.FLAG_TIME | ViewStreamMessage.Atmosphere.FLAG_WEATHER), 0);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        assertEquals(1, harness.tick.atmosphere().dominant());
        assertEquals(0.8F, harness.scene.rain, 0.0F);
        assertEquals(18000L, harness.scene.clock);
        harness.receive(new ViewStreamMessage.PortalDrop(1), 0);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, 11.0D);
        assertEquals(0, harness.tick.atmosphere().dominant());
    }

    @Test
    public void memoryFailureRefusesOnlyCurrentGenerationOnceAndKeepsNativeOwnership() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        begin(harness);
        harness.receive(new ViewStreamMessage.Portal(2, 1, ClientViewHarness.geometry()), 0);
        harness.receive(new ViewStreamMessage.MeshBegin(2, 1, new BlockBox(-16, 48, -16, 48, 48, 48), 27), 0);
        harness.receive(new ViewStreamMessage.MeshBegin(1, 2, new BlockBox(-16, 48, -16, 48, 48, 48), 27), 0);
        harness.tick(1.5, 65.5, 15.5);
        harness.session.refuseMesh(1, 1, harness.tick);
        assertNotNull(harness.session.meshes().view(1));
        assertTrue(harness.sent.stream().noneMatch(message -> message instanceof ViewStreamMessage.PlateRefused));
        harness.session.refuseMesh(1, 2, harness.tick);
        harness.session.refuseMesh(1, 2, harness.tick);
        assertNull(harness.session.meshes().view(1));
        assertNotNull(harness.session.portal(1));
        assertEquals(ClientViewSession.State.CLIENT_VIEW, harness.session.state());
        assertEquals(ClientViewSession.MeshFailure.MEMORY, harness.session.meshFailure(1));
        assertNotNull(harness.session.meshes().view(2));
        assertEquals(1, harness.sent.stream().filter(message -> message.equals(new ViewStreamMessage.PlateRefused(1, 2))).count());
    }

    @Test
    public void exhaustedNativeMemoryRetriesAtTheSameDistanceWithoutPacketProjection() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        begin(harness);
        harness.tick(1.5, 65.5, 15.5);
        double distance = harness.session.portal(1).geometry().depthBlocks();
        harness.session.meshes().otherMemory(harness.config::plateMemoryBytes);
        harness.receive(new ViewStreamMessage.MeshSection(1, 1, 0, 4, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE), 0);
        harness.tick(1.5, 65.5, 15.5);
        assertEquals(ClientViewSession.State.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.session.nativeSelected());
        assertEquals(ClientViewSession.MeshFailure.MEMORY, harness.session.meshFailure(1));
        assertEquals(distance, harness.session.portal(1).geometry().depthBlocks(), 0);
        assertEquals(0, harness.surface.changedCells());
        harness.session.meshes().otherMemory(() -> 0L);
        harness.receive(new ViewStreamMessage.MeshBegin(1, 2, new BlockBox(-16, 48, -16, 48, 48, 48), 27), 0);
        harness.receive(new ViewStreamMessage.MeshSection(1, 2, 0, 4, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE), 0);
        harness.tick(1.5, 65.5, 15.5);
        assertNull(harness.session.meshFailure(1));
        assertNotNull(harness.session.meshes().view(1).section(SectionPos.asLong(0, 4, 0)));
        assertTrue(harness.sent.contains(new ViewStreamMessage.MeshAck(1, 2, 0, 4, 0, 1)));
        assertEquals(distance, harness.session.portal(1).geometry().depthBlocks(), 0);
        assertEquals(0, harness.surface.changedCells());
    }

    @Test
    public void nativeMirrorsAndNestedGeometryWaitForMeshDataWithoutWritingWorldBlocks() throws Exception {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        ApertureDescriptor child = mirror(List.of()).withParent(1);
        harness.receive(new ViewStreamMessage.Portal(1, 1, mirror(List.of(child))), 0);
        for (int key = 2; key <= 17; key++) {
            harness.receive(new ViewStreamMessage.Portal(key, 1, child), ViewStreamLimits.FLAG_LAST);
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
        harness.receive(new ViewStreamMessage.MeshBegin(1, 1, new BlockBox(-16, 48, -16, 48, 48, 48), 27), 0);
        harness.receive(new ViewStreamMessage.MeshSection(1, 1, 0, 4, 0, 1, 3, Brick.single(0, 0), SectionBiomes.NONE), 0);
        harness.tick(1.5, 65.5, 15.5);
        assertNotNull(harness.session.meshes().view(1).section(SectionPos.asLong(0, 4, 0)));
        assertEquals(0, harness.surface.writes.size());
    }

    @Test
    public void nativeAcceptanceDiscardsQueuedPlateStreamAndStandbyHandles() throws Exception {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ViewStreamMessage.PlatePatch(1, 1, 2, List.of()), 0);
        harness.receive(new ViewStreamMessage.PlateHandle(1, 2, 999L), ViewStreamLimits.FLAG_LAST);
        harness.session.accept(new ViewStreamMessage.Accept(2, ViewStreamCapability.ALL, 20,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 9L, 8));
        harness.tick(1.5, 65.5, 15.5);
        assertNotNull(harness.session.portal(1));
        assertNull(harness.session.portal(1).content());
        assertEquals(0, harness.session.plates().size());
        assertEquals(0, harness.session.plates().bytes());
        assertEquals(0, harness.tick.protocolFailures());
        assertEquals(0, harness.surface.writes.size());
        assertTrue(harness.sent.stream().noneMatch(message -> message instanceof ViewStreamMessage.BrickMiss
            || message instanceof ViewStreamMessage.PlateRefused));
    }

    @Test
    public void nativeAcceptanceRestoresExistingLegacyProjectionAndKeepsGeometryPending() throws Exception {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ViewStreamMessage.Portal(2, 1, mirror(List.of())), ViewStreamLimits.FLAG_LAST);
        harness.tick(1.5, 65.5, 15.5);
        assertTrue(harness.surface.changedCells() > 0);
        assertNotNull(harness.tick.mirror(2));
        harness.session.accept(new ViewStreamMessage.Accept(2, ViewStreamCapability.ALL, 20,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 9L, 8));
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

    private static ApertureDescriptor mirror(List<ApertureDescriptor> nested) {
        ApertureDescriptor source = ClientViewHarness.geometry();
        return new ApertureDescriptor(source.originX(), source.originY(), source.originZ(), source.facing(), source.frontSide(),
            source.quarterTurns(), true, source.apertureWidth(), source.apertureHeight(), source.apertureMask(), ShapeDescriptor.FULL,
            source.nearPlanePadding(), source.aperturePadding(), source.frustumCullingRatio(), source.depthBlocks(), 3,
            source.blackoutPolicy(), source.blackoutState(), source.maskAirPolicy(), source.lightingPolicy(), source.fidelityFlags(),
            source.kind(), source.planeOffset(), 0, 0L, nested);
    }

    private static void begin(ClientViewHarness harness) throws Exception {
        harness.receive(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:stone"))), 0);
        harness.receive(new ViewStreamMessage.Portal(1, 1, ClientViewHarness.geometry()), 0);
        harness.receive(new ViewStreamMessage.MeshBegin(1, 1, new BlockBox(-16, 48, -16, 48, 48, 48), 27), 0);
    }
}
