package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.render.ProjectionCellKey;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ClientLightPatchesTest {
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
    public void onlyTheBoundEnginesReadPatchedLightAndOnlyForMaskedCells() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        Object blockEngine = new Object();
        Object skyEngine = new Object();
        ClientLightPatches.bind(blockEngine, skyEngine, () -> 0, harness.tick.light());
        try {
            long cell = harness.tick.overlay().keys().getLong(0);
            int x = ProjectionCellKey.unpackX(cell);
            int y = ProjectionCellKey.unpackY(cell);
            int z = ProjectionCellKey.unpackZ(cell);
            assertEquals(ClientViewHarness.DESTINATION_BLOCK_LIGHT, ClientLightPatches.patched(blockEngine, x, y, z));
            assertEquals(ClientViewHarness.DESTINATION_SKY_LIGHT, ClientLightPatches.patched(skyEngine, x, y, z));
            assertEquals(ClientLightPatches.NO_LIGHT, ClientLightPatches.patched(new Object(), x, y, z));
            assertEquals(ClientLightPatches.NO_LIGHT, ClientLightPatches.patched(blockEngine, x, y, 15));
            assertEquals(ClientLightPatches.NO_LIGHT, ClientLightPatches.patched(skyEngine, x, y + 64, z));
        } finally {
            ClientLightPatches.unbind(harness.tick.light());
        }
        assertEquals(ClientLightPatches.NO_LIGHT, ClientLightPatches.patched(blockEngine, 1, 65, 5));
    }

    @Test
    public void theLightEngineSeesTheRealStateUnderProjectedCells() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        Object blockEngine = new Object();
        Object skyEngine = new Object();
        ClientLightPatches.bind(blockEngine, skyEngine, () -> 0, harness.tick.light());
        try {
            ProjectionOverlay overlay = harness.tick.overlay();
            long cell = overlay.keys().getLong(0);
            int x = ProjectionCellKey.unpackX(cell);
            int y = ProjectionCellKey.unpackY(cell);
            int z = ProjectionCellKey.unpackZ(cell);
            ProjectionOverlay.Entry entry = overlay.get(cell);
            assertSame(entry.projected(), harness.surface.state(x, y, z));
            assertNotSame(entry.projected(), ClientViewHarness.FakeSurface.real(x, y, z));
            assertSame(ClientViewHarness.FakeSurface.real(x, y, z), ClientLightPatches.realState(skyEngine, x, y, z));
            assertSame(ClientViewHarness.FakeSurface.real(x, y, z), ClientLightPatches.realState(blockEngine, x, y, z));
            assertNull(ClientLightPatches.realState(new Object(), x, y, z));
            assertNull(ClientLightPatches.realState(blockEngine, x, y, 15));
        } finally {
            ClientLightPatches.unbind(harness.tick.light());
        }
        assertNull(ClientLightPatches.realState(blockEngine, 1, 65, 5));
    }
}
