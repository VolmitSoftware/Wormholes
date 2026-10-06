package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.optics.plate.PlateBox;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import org.junit.Test;

import java.util.List;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ClientMeshSectionsTest extends MinecraftTestBase {
    private static final PlateBox BOUNDS = new PlateBox(-512, -64, -512, 1024, 384, 1024);

    @Test
    public void largeViewBoundsAllocateOnlyReceivedSections() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        assertTrue(store.begin(7, 1, BOUNDS, 64));
        assertEquals(0, store.bytes());
        assertNull(store.view(7).section(SectionPos.asLong(0, 0, 0)));

        assertEquals(ClientMeshSections.Result.APPLIED, store.put(new ClientViewMessage.MeshSection(7, 1, -1, -1, -1, 1, 3, Brick.single(0, 3), SectionBiomes.NONE)));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(new ClientViewMessage.MeshSection(7, 1, 1, 1, 1, 1, 3, Brick.empty(0), SectionBiomes.NONE)));

        assertEquals(2, store.view(7).sectionKeys().size());
        assertTrue(store.bytes() < 1024);
        assertSame(Blocks.STONE.defaultBlockState(), store.view(7).section(SectionPos.asLong(-1, -1, -1)).state(4095));
        assertSame(Blocks.AIR.defaultBlockState(), store.view(7).section(SectionPos.asLong(1, 1, 1)).state(0));
        assertNull(store.view(7).section(SectionPos.asLong(2, 1, 1)));
    }

    @Test
    public void authoritativeLocalSectionsKeepTheirViewAndRendererRevisionAcrossSideGenerations() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        store.begin(7, 1, BOUNDS, 64);
        ClientMeshSections.Section local = store.localSection(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3,
            Brick.single(0, 3), SectionBiomes.NONE));
        assertTrue(store.local(7, 0L, local));
        ClientMeshSections.View view = store.view(7);
        int revision = local.revision();
        assertTrue(store.retainLocal(7, 2, BOUNDS, 64));
        assertSame(view, store.view(7));
        assertSame(local, view.section(0L));
        assertEquals(revision, view.section(0L).revision());
        assertEquals(ClientMeshSections.Result.STALE, store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 999, 3,
            Brick.empty(0), SectionBiomes.NONE)));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(new ClientViewMessage.MeshSection(7, 2, 0, 0, 0, 1, 3,
            Brick.empty(0), SectionBiomes.NONE)));
        assertSame(local, view.section(0L));
        assertTrue(store.local(7, 0L, null));
        assertSame(Blocks.AIR.defaultBlockState(), view.section(0L).state(0));
        assertTrue(view.section(0L).revision() > revision);
    }

    @Test
    public void remotePacketsDoNotDirtyAnAuthoritativeLocalOverride() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        store.begin(7, 1, BOUNDS, 64);
        ClientMeshSections.Section local = store.localSection(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3,
            Brick.single(0, 3), SectionBiomes.NONE));
        store.local(7, 0L, local);
        store.view(7).changed().clear();
        long revision = store.view(7).contentRevision();
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 3, 3,
            Brick.empty(0), SectionBiomes.NONE)));
        assertTrue(store.view(7).changed().isEmpty());
        assertEquals(revision, store.view(7).contentRevision());
        assertSame(local, store.view(7).section(0L));
    }

    @Test
    public void newGenerationDiscardsOldSectionsAndLateUpdates() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        store.begin(7, 1, BOUNDS, 64);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE));
        assertTrue(store.begin(7, 2, BOUNDS, 64));
        assertEquals(0, store.bytes());
        assertEquals(ClientMeshSections.Result.STALE, store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 2, 3, Brick.single(0, 3), SectionBiomes.NONE)));
        assertFalse(store.begin(7, 1, BOUNDS, 64));
        assertEquals(2, store.view(7).generation());
    }

    @Test
    public void residentLimitRefusesNewSectionsWithoutLosingExistingData() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        store.begin(7, 1, BOUNDS, 1);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE));
        long bytes = store.bytes();

        assertEquals(ClientMeshSections.Result.REFUSED, store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE)));
        assertEquals(bytes, store.bytes());
        assertEquals(1, store.view(7).sectionKeys().size());
        assertSame(Blocks.STONE.defaultBlockState(), store.view(7).section(0L).state(0));
    }

    @Test
    public void incomingWireSectionsReplaceLocalOnlyResidentsAtTheUnionLimit() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        store.begin(7, 1, BOUNDS, 1);
        ClientMeshSections.Section local = store.localSection(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3,
            Brick.single(0, 3), SectionBiomes.NONE));
        assertTrue(store.local(7, 0L, local));
        long revision = store.view(7).contentRevision();
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 1, 3,
            Brick.single(0, 3), SectionBiomes.NONE)));
        assertEquals(1, store.view(7).sectionKeys().size());
        assertNull(store.view(7).section(0L));
        assertTrue(store.view(7).contentRevision() > revision);
        assertTrue(store.view(7).changed().contains(0L));
        assertFalse(store.local(7, 0L, local));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 2, 3,
            Brick.empty(0), SectionBiomes.NONE)));
        assertEquals(1, store.view(7).sectionKeys().size());
    }

    @Test
    public void wireReplacementPreservesItsLocalOverrideAtTheUnionLimit() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        store.begin(7, 1, BOUNDS, 1);
        ClientMeshSections.Section local = store.localSection(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3,
            Brick.single(0, 3), SectionBiomes.NONE));
        assertTrue(store.local(7, 0L, local));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3,
            Brick.empty(0), SectionBiomes.NONE)));
        assertSame(local, store.view(7).section(0L));
        long bytes = store.bytes();
        assertEquals(ClientMeshSections.Result.REFUSED, store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 1, 3,
            Brick.empty(0), SectionBiomes.NONE)));
        assertEquals(bytes, store.bytes());
        assertSame(local, store.view(7).section(0L));
        assertEquals(1, store.view(7).sectionKeys().size());
    }

    @Test
    public void nonuniformLightStillCountsAgainstTheMemoryBudget() throws Exception {
        ClientMeshSections store = store(2048);
        store.begin(7, 1, BOUNDS, 64);
        byte[] light = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        light[0] = 1;
        Brick litAir = Brick.empty(0).withLight(light, light);

        assertEquals(ClientMeshSections.Result.REFUSED, store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, litAir, SectionBiomes.NONE)));
        assertEquals(0, store.bytes());
        assertTrue(store.view(7).sectionKeys().isEmpty());
    }

    @Test
    public void repeatedLightPreservesEveryNibbleWithoutFullArrays() throws Exception {
        ClientMeshSections store = store(1024);
        store.begin(7, 1, BOUNDS, 64);
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        Arrays.fill(block, (byte) 0x73);
        Arrays.fill(sky, (byte) 0xFF);
        Brick brick = Brick.empty(0).withLight(block, sky);
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, brick, SectionBiomes.NONE)));
        assertTrue(store.bytes() < 256);
        ClientMeshSections.Section section = store.view(7).section(0L);
        Arrays.fill(brick.blockLight(), (byte) 0);
        Arrays.fill(brick.skyLight(), (byte) 0);
        for (int cell = 0; cell < ViewStreamLimits.BRICK_CELLS; cell++) {
            assertEquals((cell & 1) == 0 ? 3 : 7, section.light(false, cell));
            assertEquals(15, section.light(true, cell));
        }
        assertTrue(section.hasLight());
        assertTrue(store.drop(7, 1, 0, 0, 0));
        assertEquals(0, store.bytes());
    }

    @Test
    public void mixedLightChannelsRetainTheirIndependentPatterns() throws Exception {
        ClientMeshSections store = store(8192);
        store.begin(7, 1, BOUNDS, 64);
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        Arrays.fill(sky, (byte) 0xFF);
        for (int index = 0; index < block.length; index++) {
            block[index] = (byte) index;
        }
        Brick brick = Brick.empty(0).withLight(block, sky);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, brick, SectionBiomes.NONE));
        assertTrue(store.bytes() < 2500);
        ClientMeshSections.Section section = store.view(7).section(0L);
        Arrays.fill(brick.blockLight(), (byte) 0);
        for (int cell = 0; cell < ViewStreamLimits.BRICK_CELLS; cell++) {
            assertEquals(((cell >>> 1) >>> ((cell & 1) * 4)) & 15, section.light(false, cell));
            assertEquals(15, section.light(true, cell));
        }
    }

    @Test
    public void replacementsAndDropsRespectRevisionAndMemoryAccounting() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        store.begin(7, 1, BOUNDS, 64);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 2, 3, Brick.single(0, 3), SectionBiomes.NONE));
        long bytes = store.bytes();
        assertEquals(ClientMeshSections.Result.DUPLICATE, store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 2, 3, Brick.single(0, 3), SectionBiomes.NONE)));
        assertEquals(ClientMeshSections.Result.STALE, store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.empty(0), SectionBiomes.NONE)));
        assertEquals(bytes, store.bytes());
        assertFalse(store.drop(7, 0, 0, 0, 0));
        assertTrue(store.drop(7, 1, 0, 0, 0));
        assertEquals(0, store.bytes());
        assertNull(store.view(7).section(0));
    }

    @Test
    public void rejectsUnknownPaletteAndOutOfBoundsCoordinates() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        store.begin(7, 1, BOUNDS, 64);
        assertThrows(ClientViewProtocolException.class, () -> store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 99), SectionBiomes.NONE)));
        assertThrows(ClientViewProtocolException.class, () -> store.put(new ClientViewMessage.MeshSection(7, 1, 100, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE)));
        assertThrows(ClientViewProtocolException.class, () -> store.put(new ClientViewMessage.MeshSection(7, 1, 1 << 22, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE)));
        assertEquals(0, store.bytes());
    }

    @Test
    public void removingOnePortalRetainsTheOthersAndClearReleasesAll() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        store.begin(7, 1, BOUNDS, 64);
        store.begin(8, 1, BOUNDS, 64);
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE));
        long first = store.bytes();
        store.put(new ClientViewMessage.MeshSection(8, 1, 0, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE));
        assertEquals(first * 2, store.bytes());
        store.remove(7);
        assertEquals(first, store.bytes());
        assertEquals(1, store.view(8).sectionKeys().size());
        store.clear();
        assertEquals(0, store.bytes());
        assertNull(store.view(8));
    }

    @Test
    public void legacyPlateMemorySharesTheSectionBudget() throws Exception {
        ClientMeshSections store = store(1024);
        store.otherMemory(() -> 1000L);
        store.begin(7, 1, BOUNDS, 64);
        assertEquals(ClientMeshSections.Result.REFUSED, store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE)));
        assertEquals(0, store.bytes());
        store.otherMemory(() -> 0L);
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE)));
    }

    @Test
    public void contentRevisionChangesOnlyForAcceptedSectionMutations() throws Exception {
        ClientMeshSections store = store(1024 * 1024);
        store.begin(7, 1, BOUNDS, 1);
        ClientMeshSections.View view = store.view(7);
        assertEquals(0, view.contentRevision());
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 2, 3, Brick.single(0, 3), SectionBiomes.NONE));
        assertEquals(1, view.contentRevision());
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 2, 3, Brick.single(0, 3), SectionBiomes.NONE));
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.empty(0), SectionBiomes.NONE));
        store.put(new ClientViewMessage.MeshSection(7, 0, 0, 0, 0, 3, 3, Brick.empty(0), SectionBiomes.NONE));
        store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 3, 3, Brick.empty(0), SectionBiomes.NONE));
        assertFalse(store.drop(7, 0, 0, 0, 0));
        assertFalse(store.drop(7, 1, 1, 0, 0));
        view.changed().clear();
        assertEquals(1, view.contentRevision());
        store.put(new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 3, 3, Brick.empty(0), SectionBiomes.NONE));
        assertEquals(2, view.contentRevision());
        assertTrue(store.drop(7, 1, 0, 0, 0));
        assertEquals(3, view.contentRevision());
        store.begin(7, 2, BOUNDS, 1);
        assertEquals(0, store.view(7).contentRevision());
        assertEquals(3, view.contentRevision());
    }

    private static ClientMeshSections store(long bytes) throws ClientViewProtocolException {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:stone"))));
        return new ClientMeshSections(palette, bytes);
    }
}
