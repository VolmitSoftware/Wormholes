package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ClientMeshSectionsReuseTest {
    private static final PlateBox BOUNDS = new PlateBox(-32, -32, -32, 64, 64, 64);
    private static final ClientViewEnvironment ENVIRONMENT = PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY);

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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
        List<ClientViewMessage.MeshClaim> claims = store.bind(9, ENVIRONMENT, 71, 11);
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
        long hash = store.bind(7, ENVIRONMENT, 71, 11).getFirst().hash();
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
        assertTrue(store.bind(7, ENVIRONMENT, 71, 12).isEmpty());
        assertTrue(store.view(7).sectionKeys().isEmpty());
        ClientViewEnvironment translated = ENVIRONMENT.withTransform(new ClientViewEnvironment.Transform(Direction.E,
            Direction.U, Direction.S, new GeometryVector(16, 0, 0)));
        assertTrue(store.bind(7, translated, 71, 11).isEmpty());
        ClientViewEnvironment.World previous = ENVIRONMENT.world();
        ClientViewEnvironment.World nether = new ClientViewEnvironment.World("minecraft:the_nether", previous.clockTime(),
            previous.biomeKey(), previous.seaLevel(), previous.blockLight(), previous.skyLight(), previous.logicalHeight(),
            previous.hasCeiling(), previous.ambientLight(), previous.eyeMedium(), previous.hasFixedTime());
        ClientViewEnvironment otherWorld = new ClientViewEnvironment(ENVIRONMENT.gameTime(), ENVIRONMENT.sky(), ENVIRONMENT.fog(),
            ENVIRONMENT.lighting(), ENVIRONMENT.clouds(), ENVIRONMENT.transform(), ENVIRONMENT.dimension(), nether);
        assertTrue(store.bind(7, otherWorld, 71, 11).isEmpty());
        assertNull(store.view(7).section(0L));
        assertEquals(1, store.bind(7, ENVIRONMENT, 71, 11).size());
    }

    @Test
    public void populatedViewIdentityChangeRetiresOldContentAndClaimsBeforeRebinding() throws Exception {
        ClientMeshSections store = populated();
        ClientMeshSections.Section original = store.view(7).section(0L);
        store.clear();
        store.begin(7, 2, BOUNDS, 8);
        long hash = store.bind(7, ENVIRONMENT, 71, 11).getFirst().hash();
        assertEquals(ClientMeshSections.Result.DUPLICATE, store.reuse(reuse(7, 2, 50, hash)));
        assertTrue(store.bind(7, ENVIRONMENT, 71, 12).isEmpty());
        assertNull(store.view(7).section(0L));
        assertTrue(store.view(7).sectionKeys().isEmpty());
        assertEquals(ClientMeshSections.Result.STALE, store.reuse(reuse(7, 2, 51, hash)));
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(section(7, 2, 1, 4)));
        assertSame(Blocks.DIRT.defaultBlockState(), store.view(7).section(0L).state(0));
        List<ClientViewMessage.MeshClaim> restored = store.bind(7, ENVIRONMENT, 71, 11);
        assertEquals(1, restored.size());
        assertSame(original, store.view(7).section(0L));
        assertEquals(hash, restored.getFirst().hash());
        assertEquals(ClientMeshSections.Result.DUPLICATE, store.reuse(reuse(7, 2, 1, hash)));
        store.bind(7, ENVIRONMENT, 71, 12);
        assertSame(Blocks.DIRT.defaultBlockState(), store.view(7).section(0L).state(0));
    }

    @Test
    public void registryEpochAndNewConnectionDiscardHistoricalContent() throws Exception {
        ClientMeshSections store = populated();
        store.epoch(72);
        assertNull(store.view(7));
        assertEquals(0, store.bytes());
        store.begin(7, 2, BOUNDS, 8);
        assertTrue(store.bind(7, ENVIRONMENT, 72, 11).isEmpty());
        ClientMeshSections disconnected = store();
        disconnected.epoch(71);
        disconnected.begin(7, 1, BOUNDS, 8);
        assertTrue(disconnected.bind(7, ENVIRONMENT, 71, 11).isEmpty());
    }

    @Test
    public void residentBoundsAndExplicitDropCannotBeBypassedByHistoricalReuse() throws Exception {
        ClientMeshSections store = populated();
        store.put(new ClientViewMessage.MeshSection(7, 1, 1, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE));
        store.clear();
        store.begin(7, 2, new PlateBox(0, 0, 0, 16, 16, 16), 1);
        List<ClientViewMessage.MeshClaim> claims = store.bind(7, ENVIRONMENT, 71, 11);
        assertEquals(1, claims.size());
        assertEquals(0, claims.getFirst().x());
        assertNull(store.view(7).section(SectionPos.asLong(1, 0, 0)));
        assertTrue(store.drop(7, 2, 0, 0, 0));
        assertEquals(ClientMeshSections.Result.STALE, store.reuse(reuse(7, 2, 1, claims.getFirst().hash())));
    }

    private static ClientMeshSections populated() throws ClientViewProtocolException {
        ClientMeshSections store = store();
        store.epoch(71);
        store.begin(7, 1, BOUNDS, 8);
        store.bind(7, ENVIRONMENT, 71, 11);
        assertEquals(ClientMeshSections.Result.APPLIED, store.put(section(7, 1, 1, 3)));
        return store;
    }

    private static ClientMeshSections store() throws ClientViewProtocolException {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:stone"),
            new ClientViewMessage.PaletteEntry(4, "minecraft:dirt"))));
        return new ClientMeshSections(palette, 1024 * 1024);
    }

    private static ClientViewMessage.MeshSection section(int key, int generation, int revision, int state) {
        return new ClientViewMessage.MeshSection(key, generation, 0, 0, 0, revision, state, Brick.single(0, state), SectionBiomes.NONE);
    }

    private static ClientViewMessage.MeshReuse reuse(int key, int generation, int revision, long hash) {
        return new ClientViewMessage.MeshReuse(key, generation, 0, 0, 0, revision, hash);
    }
}
