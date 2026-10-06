package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.math.Face;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClientMeshSectionsReuseTest extends MinecraftTestBase {
    private static final PlateBox BOUNDS = new PlateBox(-32, -32, -32, 64, 64, 64);
    private static final ProjectionEnvironment ENVIRONMENT = PortalEnvironmentTest.environment(ProjectionEnvironment.Transform.IDENTITY);

    @Test
    public void immutableIdentityUsesValueEqualityWithoutTreatingCachedHashCollisionsAsProof() {
        ClientMeshSections.Identity original = new ClientMeshSections.Identity(ENVIRONMENT, 71, 1);
        ProjectionEnvironment same = ENVIRONMENT.withTransform(new ProjectionEnvironment.Transform(Face.E,
            Face.U, Face.S, new Vec3(0, 0, 0)));
        ClientMeshSections.Identity equivalent = new ClientMeshSections.Identity(same, 71, 1);
        assertEquals(original, equivalent);
        assertEquals(original.hashCode(), equivalent.hashCode());
        ClientMeshSections.Identity targetCollision = new ClientMeshSections.Identity(ENVIRONMENT, 71, 1L << 32);
        assertEquals(original.hashCode(), targetCollision.hashCode());
        assertNotEquals(original, targetCollision);
        ClientMeshSections.Identity epoch = new ClientMeshSections.Identity(ENVIRONMENT, 1, 1);
        ClientMeshSections.Identity epochCollision = new ClientMeshSections.Identity(ENVIRONMENT, 1L << 32, 1);
        assertEquals(epoch.hashCode(), epochCollision.hashCode());
        assertNotEquals(epoch, epochCollision);
    }

    @Test
    public void previewEligibilityUsesTheSameIdentityBoundsContentAndResidentLimitsAsAdmission() throws Exception {
        ClientMeshSections store = store();
        long nearby = SectionPos.asLong(1, 0, 0);
        assertFalse(store.canPreview(7, 0L));
        store.begin(7, 1, BOUNDS, 1);
        assertFalse(store.canPreview(7, 0L));
        store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11));
        assertTrue(store.canPreview(7, 0L));
        assertTrue(store.canPreview(7, nearby));
        assertFalse(store.canPreview(7, SectionPos.asLong(10, 0, 0)));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(section(7, 1, 1, 3)));
        assertFalse(store.canPreview(7, 0L));
        assertFalse(store.canPreview(7, nearby));
        ClientMeshSections.Section section = store.localSection(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0,
            2, 4, Brick.single(0, 4), SectionBiomes.NONE));
        assertNull(store.preview(7, nearby, section));
        assertTrue(store.drop(7, 1, 0, 0, 0));
        assertTrue(store.canPreview(7, nearby));
        assertNotNull(store.preview(7, nearby, section));
        assertFalse(store.canPreview(7, nearby));
    }

    @Test
    public void incomingWireSectionsEvictUnconfirmedPreviewsBeforeLocalResidents() throws Exception {
        ClientMeshSections store = store();
        store.begin(7, 1, BOUNDS, 2);
        store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11));
        ClientMeshSections.Section local = store.localSection(section(7, 1, 1, 3));
        assertTrue(store.local(7, 0L, local));
        long previewKey = SectionPos.asLong(1, 0, 0);
        ClientMeshSections.Section preview = store.localSection(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0,
            1, 3, Brick.single(0, 3), SectionBiomes.NONE));
        assertNotNull(store.preview(7, previewKey, preview));
        assertFalse(store.canPreview(7, SectionPos.asLong(-1, 0, 0)));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(new ClientViewMessage.MeshSection(7, 1, -1, 0, 0,
            1, 3, Brick.single(0, 3), SectionBiomes.NONE)));
        assertSame(local, store.view(7).section(0L));
        assertNull(store.view(7).section(previewKey));
        assertEquals(2, store.view(7).sectionKeys().size());
    }

    @Test
    public void restoredContentKeepsItsObjectAndRendererRevisionAcrossWireGenerations() throws Exception {
        ClientMeshSections store = populated();
        ClientMeshSections.Section original = store.view(7).section(0L);
        int revision = original.revision();
        store.clear();
        assertNull(store.view(7));
        assertTrue(store.bytes() > 0);
        store.begin(9, 2, BOUNDS, 8);
        List<ClientViewMessage.MeshClaim> claims = store.bind(9, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11));
        assertEquals(1, claims.size());
        assertSame(original, store.view(9).section(0L));
        store.view(9).changed().clear();
        long contentRevision = store.view(9).contentRevision();
        assertEquals(ClientMeshSections.Result.DUPLICATE, store.reuse(reuse(9, 2, 5, claims.getFirst().hash())));
        assertEquals(ClientMeshSections.Result.DUPLICATE, store.put(section(9, 2, 6, 3)));
        assertSame(original, store.view(9).section(0L));
        assertEquals(revision, original.revision());
        assertEquals(contentRevision, store.view(9).contentRevision());
        assertTrue(store.view(9).changed().isEmpty());
        assertEquals(ClientMeshSections.Result.STALE, store.reuse(reuse(9, 2, 4, claims.getFirst().hash())));
    }

    @Test
    public void staleOrMismatchedClaimsCannotReplaceCurrentContentOrAdvanceItsWireRevision() throws Exception {
        ClientMeshSections store = populated();
        store.clear();
        store.begin(7, 2, BOUNDS, 8);
        long hash = store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11)).getFirst().hash();
        ClientMeshSections.Section restored = store.view(7).section(0L);
        assertEquals(ClientMeshSections.Result.STALE, store.reuse(reuse(7, 1, 90, hash)));
        assertEquals(ClientMeshSections.Result.STALE, store.reuse(reuse(8, 2, 90, hash)));
        assertEquals(ClientMeshSections.Result.STALE, store.reuse(reuse(7, 2, 90, hash + 1)));
        assertEquals(ClientMeshSections.Result.STALE, store.reuse(new ClientViewMessage.MeshReuse(7, 2, 1, 0, 0, 90, hash)));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(section(7, 2, 1, 4)));
        ClientMeshSections.Section changed = store.view(7).section(0L);
        assertNotSame(restored, changed);
        assertTrue(changed.revision() > restored.revision());
        assertSame(Blocks.DIRT.defaultBlockState(), changed.state(0));
        assertEquals(ClientMeshSections.Result.STALE, store.reuse(reuse(7, 2, 100, hash)));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(section(7, 2, 2, 3)));
        assertSame(Blocks.STONE.defaultBlockState(), store.view(7).section(0L).state(0));
    }

    @Test
    public void worldTransformAndAuthoritativeTargetIdentityPartitionHistory() throws Exception {
        ClientMeshSections store = populated();
        store.clear();
        store.begin(7, 2, BOUNDS, 8);
        assertTrue(store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 12)).isEmpty());
        assertTrue(store.view(7).sectionKeys().isEmpty());
        ProjectionEnvironment translated = ENVIRONMENT.withTransform(new ProjectionEnvironment.Transform(Face.E,
            Face.U, Face.S, new Vec3(16, 0, 0)));
        assertTrue(store.bind(7, new ClientMeshSections.Identity(translated, 71, 11)).isEmpty());
        ProjectionEnvironment.World previous = ENVIRONMENT.world();
        ProjectionEnvironment.World nether = new ProjectionEnvironment.World("minecraft:the_nether", previous.clockTime(),
            previous.biomeKey(), previous.seaLevel(), previous.blockLight(), previous.skyLight(), previous.logicalHeight(),
            previous.hasCeiling(), previous.ambientLight(), previous.eyeMedium(), previous.hasFixedTime());
        ProjectionEnvironment otherWorld = new ProjectionEnvironment(ENVIRONMENT.gameTime(), ENVIRONMENT.sky(), ENVIRONMENT.fog(),
            ENVIRONMENT.lighting(), ENVIRONMENT.clouds(), ENVIRONMENT.transform(), ENVIRONMENT.dimension(), nether);
        assertTrue(store.bind(7, new ClientMeshSections.Identity(otherWorld, 71, 11)).isEmpty());
        assertNull(store.view(7).section(0L));
        assertEquals(1, store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11)).size());
    }

    @Test
    public void populatedViewIdentityChangeRetiresOldContentAndClaimsBeforeRebinding() throws Exception {
        ClientMeshSections store = populated();
        ClientMeshSections.Section original = store.view(7).section(0L);
        store.clear();
        store.begin(7, 2, BOUNDS, 8);
        long hash = store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11)).getFirst().hash();
        assertEquals(ClientMeshSections.Result.DUPLICATE, store.reuse(reuse(7, 2, 50, hash)));
        assertTrue(store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 12)).isEmpty());
        assertNull(store.view(7).section(0L));
        assertTrue(store.view(7).sectionKeys().isEmpty());
        assertEquals(ClientMeshSections.Result.STALE, store.reuse(reuse(7, 2, 51, hash)));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(section(7, 2, 1, 4)));
        assertSame(Blocks.DIRT.defaultBlockState(), store.view(7).section(0L).state(0));
        List<ClientViewMessage.MeshClaim> restored = store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11));
        assertEquals(1, restored.size());
        assertSame(original, store.view(7).section(0L));
        assertEquals(hash, restored.getFirst().hash());
        assertEquals(ClientMeshSections.Result.DUPLICATE, store.reuse(reuse(7, 2, 1, hash)));
        store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 12));
        assertSame(Blocks.DIRT.defaultBlockState(), store.view(7).section(0L).state(0));
    }

    @Test
    public void registryEpochAndNewConnectionDiscardHistoricalContent() throws Exception {
        ClientMeshSections store = populated();
        store.epoch(72);
        assertNull(store.view(7));
        assertEquals(0, store.bytes());
        store.begin(7, 2, BOUNDS, 8);
        assertTrue(store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 72, 11)).isEmpty());
        ClientMeshSections disconnected = store();
        disconnected.epoch(71);
        disconnected.begin(7, 1, BOUNDS, 8);
        assertTrue(disconnected.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11)).isEmpty());
    }

    @Test
    public void residentBoundsAndExplicitDropCannotBeBypassedByHistoricalReuse() throws Exception {
        ClientMeshSections store = populated();
        store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE));
        store.clear();
        store.begin(7, 2, new PlateBox(0, 0, 0, 16, 16, 16), 1);
        List<ClientViewMessage.MeshClaim> claims = store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11));
        assertEquals(1, claims.size());
        assertEquals(0, claims.getFirst().x());
        assertNull(store.view(7).section(SectionPos.asLong(1, 0, 0)));
        assertTrue(store.drop(7, 2, 0, 0, 0));
        assertEquals(ClientMeshSections.Result.STALE, store.reuse(reuse(7, 2, 1, claims.getFirst().hash())));
    }

    @Test
    public void collidingIdentityHashesKeepHistoricalPayloadsInSeparateContexts() throws Exception {
        ClientMeshSections store = populated();
        ClientMeshSections.Identity first = store.view(7).identity();
        ClientMeshSections.Identity collision = new ClientMeshSections.Identity(ENVIRONMENT, 71, 11L << 32);
        assertEquals(first.hashCode(), collision.hashCode());
        store.clear();
        store.begin(8, 1, BOUNDS, 8);
        assertTrue(store.bind(8, collision).isEmpty());
        store.put(section(8, 1, 1, 4));
        store.clear();
        store.begin(9, 1, BOUNDS, 8);
        assertEquals(1, store.bind(9, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11)).size());
        assertSame(Blocks.STONE.defaultBlockState(), store.view(9).section(0L).state(0));
        store.clear();
        store.begin(10, 1, BOUNDS, 8);
        assertEquals(1, store.bind(10, collision).size());
        assertSame(Blocks.DIRT.defaultBlockState(), store.view(10).section(0L).state(0));
    }

    @Test
    public void interleavedHistoryRestoresMatchingContextInGlobalOrderAndRefreshesExistingEntries() throws Exception {
        ClientMeshSections store = store();
        PlateBox bounds = new PlateBox(0, 0, 0, 64, 16, 16);
        ClientMeshSections.Identity first = new ClientMeshSections.Identity(ENVIRONMENT, 71, 11);
        ClientMeshSections.Identity second = new ClientMeshSections.Identity(ENVIRONMENT, 71, 12);
        store.begin(7, 1, bounds, 4);
        store.bind(7, first);
        store.begin(8, 1, bounds, 4);
        store.bind(8, second);
        store.put(at(7, 0, 1, 3));
        store.put(at(8, 0, 1, 3));
        store.put(at(7, 1, 1, 3));
        store.put(at(8, 1, 1, 3));
        store.put(at(7, 2, 1, 3));
        List<Object> entries = historyEntries(store);
        assertEquals(ClientMeshSections.Result.DUPLICATE, store.put(at(7, 0, 2, 3)));
        List<Object> refreshed = historyEntries(store);
        assertEquals(entries.size(), refreshed.size());
        assertSame(entries.getFirst(), refreshed.getLast());
        for (int index = 1; index < entries.size(); index++) {
            assertSame(entries.get(index), refreshed.get(index - 1));
        }
        store.clear();
        store.begin(9, 2, bounds, 2);
        List<ClientViewMessage.MeshClaim> claims = store.bind(9, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11));
        assertEquals(List.of(1, 2), claims.stream().map(ClientViewMessage.MeshClaim::x).toList());
        assertSame(first, store.view(9).identity());
        assertNull(store.view(9).section(0L));
        assertEquals(2, store.view(9).sectionKeys().size());
        assertTrue(store.bind(9, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11)).isEmpty());
    }

    @Test
    public void globalEvictionAndReplacementKeepTheExactPayloadBudgetAcrossContexts() throws Exception {
        ClientMeshSections sample = store();
        long sectionBytes = sample.localSection(at(7, 0, 1, 3)).bytes();
        ClientMeshSections store = store(sectionBytes * 9);
        PlateBox bounds = new PlateBox(0, 0, 0, 64, 16, 16);
        for (int key : new int[]{7, 8, 9}) {
            store.begin(key, 1, bounds, 4);
            store.bind(key, new ClientMeshSections.Identity(ENVIRONMENT, 71, key));
        }
        store.put(at(7, 0, 1, 3));
        store.put(at(8, 0, 1, 3));
        store.put(at(7, 1, 1, 3));
        store.put(at(7, 0, 2, 3));
        store.put(at(8, 1, 1, 3));
        store.put(at(9, 0, 1, 3));
        assertEquals(sectionBytes * 8, store.bytes());
        store.clear();
        assertEquals(sectionBytes * 3, store.bytes());
        store.begin(10, 2, bounds, 4);
        assertEquals(List.of(0), store.bind(10, new ClientMeshSections.Identity(ENVIRONMENT, 71, 7))
            .stream().map(ClientViewMessage.MeshClaim::x).toList());
        store.begin(11, 2, bounds, 4);
        assertEquals(List.of(1), store.bind(11, new ClientMeshSections.Identity(ENVIRONMENT, 71, 8))
            .stream().map(ClientViewMessage.MeshClaim::x).toList());
        store.begin(12, 2, bounds, 4);
        assertEquals(List.of(0), store.bind(12, new ClientMeshSections.Identity(ENVIRONMENT, 71, 9))
            .stream().map(ClientViewMessage.MeshClaim::x).toList());
        store.epoch(72);
        assertEquals(0, store.bytes());
        assertTrue(historyEntries(store).isEmpty());
        assertTrue(historyContexts(store).isEmpty());

        ClientMeshSections replacing = populated();
        Object entry = historyEntries(replacing).getFirst();
        assertEquals(ClientMeshSections.Result.APPLIED, replacing.put(new ClientViewMessage.MeshSection(7, 1,
            0, 0, 0, 2, 0, Brick.empty(0), SectionBiomes.NONE)));
        assertSame(entry, historyEntries(replacing).getFirst());
        assertEquals(replacing.view(7).section(0L).bytes() * 2, replacing.bytes());
    }

    @Test
    public void recreatedContextAdoptsItsCanonicalIdentityWhileOlderActiveViewsRemainUsable() throws Exception {
        long sectionBytes = store().localSection(at(7, 0, 1, 3)).bytes();
        ClientMeshSections store = store(sectionBytes * 3);
        PlateBox bounds = new PlateBox(0, 0, 0, 64, 16, 16);
        ClientMeshSections.Identity original = new ClientMeshSections.Identity(ENVIRONMENT, 71, 11);
        store.begin(7, 1, bounds, 4);
        store.bind(7, original);
        store.put(at(7, 0, 1, 3));
        store.begin(8, 1, bounds, 4);
        store.bind(8, new ClientMeshSections.Identity(ENVIRONMENT, 71, 12));
        store.put(at(8, 0, 1, 3));
        assertEquals(1, historyContexts(store).size());
        store.remove(8);
        ClientMeshSections.Identity recreated = new ClientMeshSections.Identity(ENVIRONMENT, 71, 11);
        store.begin(9, 1, bounds, 4);
        store.bind(9, recreated);
        store.put(at(9, 0, 1, 3));
        assertNotSame(original, store.view(9).identity());
        store.remove(9);
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(at(7, 1, 1, 3)));
        assertSame(recreated, store.view(7).identity());
        assertEquals(1, historyContexts(store).size());
        store.clear();
        store.begin(10, 2, bounds, 4);
        assertEquals(List.of(1), store.bind(10, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11))
            .stream().map(ClientViewMessage.MeshClaim::x).toList());
        assertSame(recreated, store.view(10).identity());
    }

    private static List<Object> historyEntries(ClientMeshSections store) throws Exception {
        Field field = ClientMeshSections.class.getDeclaredField("history");
        field.setAccessible(true);
        return new ArrayList<>((Collection<?>) field.get(store));
    }

    private static Map<?, ?> historyContexts(ClientMeshSections store) throws Exception {
        Field field = ClientMeshSections.class.getDeclaredField("historyByIdentity");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(store);
    }

    private static ClientViewMessage.MeshSection at(int key, int x, int revision, int state) {
        return new ClientViewMessage.MeshSection(key, 1, x, 0, 0, revision, state, Brick.single(0, state), SectionBiomes.NONE);
    }

    private static ClientMeshSections populated() throws ClientViewProtocolException {
        ClientMeshSections store = store();
        store.epoch(71);
        store.begin(7, 1, BOUNDS, 8);
        store.bind(7, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(section(7, 1, 1, 3)));
        return store;
    }

    private static ClientMeshSections store() throws ClientViewProtocolException {
        return store(1024 * 1024);
    }

    private static ClientMeshSections store(long budget) throws ClientViewProtocolException {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:stone"),
            new ClientViewMessage.PaletteEntry(4, "minecraft:dirt"))));
        return new ClientMeshSections(palette, budget);
    }

    private static ClientViewMessage.MeshSection section(int key, int generation, int revision, int state) {
        return new ClientViewMessage.MeshSection(key, generation, 0, 0, 0, revision, state, Brick.single(0, state), SectionBiomes.NONE);
    }

    private static ClientViewMessage.MeshReuse reuse(int key, int generation, int revision, long hash) {
        return new ClientViewMessage.MeshReuse(key, generation, 0, 0, 0, revision, hash);
    }
}
