package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.frame.OpticTransform;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClientMeshLocalContentTest extends MinecraftTestBase {
    private static final BlockBox BOUNDS = new BlockBox(-32, -32, -32, 64, 64, 64);
    private static final ProjectionEnvironment ENVIRONMENT = PortalEnvironmentTest.environment(OpticTransform.IDENTITY);

    @Test
    public void unchangedHaloCapturePreservesTheGpuRevisionWithoutPublishingNeighborInvalidation() throws Exception {
        ClientMeshSections store = store();
        ClientMeshSections.Section original = capture(store, Brick.single(0, 3), SectionBiomes.NONE);
        assertTrue(store.local(7, 0L, original));
        ClientMeshSections.View view = store.view(7);
        view.changed().clear();
        long revision = view.contentRevision();
        long bytes = store.bytes();
        for (int attempt = 0; attempt < 32; attempt++) {
            ClientMeshSections.Section recaptured = capture(store, Brick.single(0, 3), SectionBiomes.NONE);
            assertNotSame(original, recaptured);
            assertTrue(store.local(7, 0L, recaptured));
            assertSame(original, view.section(0L));
        }
        assertEquals(revision, view.contentRevision());
        assertEquals(bytes, store.bytes());
        assertTrue(view.changed().isEmpty());
    }

    @Test
    public void coverageRetractionAndEqualRecaptureKeepTheSameRenderedSection() throws Exception {
        ClientMeshSections store = store();
        ClientMeshSections.Section original = capture(store, Brick.single(0, 3), SectionBiomes.NONE);
        store.local(7, 0L, original);
        ClientMeshSections.View view = store.view(7);
        view.changed().clear();
        long revision = view.contentRevision();
        assertTrue(store.local(7, 0L, null));
        assertSame(original, view.section(0L));
        assertTrue(view.sectionKeys().contains(0L));
        assertTrue(store.local(7, 0L, capture(store, Brick.single(0, 3), SectionBiomes.NONE)));
        assertSame(original, view.section(0L));
        assertEquals(revision, view.contentRevision());
        assertTrue(view.changed().isEmpty());
    }

    @Test
    public void sideGenerationRetainsTheSameRevisionWhenLocalCoverageIsReconciled() throws Exception {
        ClientMeshSections store = store();
        ClientMeshSections.Section original = capture(store, Brick.single(0, 3), SectionBiomes.NONE);
        store.local(7, 0L, original);
        ClientMeshSections.View view = store.view(7);
        view.changed().clear();
        long revision = view.contentRevision();
        assertTrue(store.retainLocal(7, 2, BOUNDS, 8));
        store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11));
        store.local(7, 0L, null);
        store.local(7, 0L, capture(store, Brick.single(0, 3), SectionBiomes.NONE));
        assertSame(view, store.view(7));
        assertSame(original, view.section(0L));
        assertEquals(revision, view.contentRevision());
        assertTrue(view.changed().isEmpty());
    }

    @Test
    public void actualBlockLightSkyLightBlockEntityAndBiomeChangesPublishNewContent() throws Exception {
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] changedBlock = block.clone();
        byte[] changedSky = sky.clone();
        changedBlock[17] = 3;
        changedSky[29] = 7;
        Brick baseline = Brick.single(0, 3).withLight(block, sky);
        Brick entity = baseline.withBlockEntities(new Brick.BlockEntityCell[] {
            new Brick.BlockEntityCell(12, BlockEntitySample.encode(new BlockEntitySample("minecraft:sign", new byte[] {10, 0, 0, 0})))
        });
        for (Brick changed : List.of(Brick.single(0, 4).withLight(block, sky),
            baseline.withLight(changedBlock, sky), baseline.withLight(block, changedSky), entity)) {
            assertChanges(baseline, changed, SectionBiomes.NONE);
        }
        assertChanges(baseline, baseline, new SectionBiomes(List.of("minecraft:plains"), new byte[0]));
    }

    @Test
    public void differentTargetIdentityCannotRetainLocalContentAsItsPreview() throws Exception {
        ClientMeshSections store = store();
        store.local(7, 0L, capture(store, Brick.single(0, 3), SectionBiomes.NONE));
        assertTrue(store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 12)).isEmpty());
        assertNull(store.view(7).section(0L));
        assertTrue(store.view(7).changed().contains(0L));
        assertEquals(1, store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11)).size());
    }

    private static void assertChanges(Brick originalBrick, Brick changedBrick, SectionBiomes changedBiomes) throws Exception {
        ClientMeshSections store = store();
        ClientMeshSections.Section original = capture(store, originalBrick, SectionBiomes.NONE);
        store.local(7, 0L, original);
        ClientMeshSections.View view = store.view(7);
        view.changed().clear();
        long revision = view.contentRevision();
        ClientMeshSections.Section changed = capture(store, changedBrick, changedBiomes);
        assertTrue(store.local(7, 0L, changed));
        assertSame(changed, view.section(0L));
        assertTrue(changed.revision() > original.revision());
        assertEquals(revision + 1, view.contentRevision());
        assertEquals(1, view.changed().size());
        assertTrue(view.changed().contains(0L));
    }

    private static ClientMeshSections.Section capture(ClientMeshSections store, Brick brick, SectionBiomes biomes) throws ViewStreamProtocolException {
        return store.localSection(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, brick, biomes));
    }

    private static ClientMeshSections store() throws ViewStreamProtocolException {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:stone"),
            new ViewStreamMessage.PaletteEntry(4, "minecraft:dirt"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1024 * 1024);
        store.epoch(71);
        store.begin(7, 1, BOUNDS, 8);
        store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11));
        return store;
    }
}
