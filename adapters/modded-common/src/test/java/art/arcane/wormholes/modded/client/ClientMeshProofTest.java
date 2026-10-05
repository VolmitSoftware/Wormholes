package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.modded.client.render.PortalScene;
import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class ClientMeshProofTest {
    private static final PlateBox BOUNDS = new PlateBox(-32, -32, -32, 64, 64, 64);
    private static final ClientViewEnvironment ENVIRONMENT = PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY);
    private static final Options OPTIONS = new Options(ENVIRONMENT, 71, 11);

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void exactSnapshotProofAcceptsEquivalentInputsButCheapContextCannotAuthorizeReuse() throws Exception {
        Fixture fixture = new Fixture(OPTIONS, true);
        PortalScene.MeshIdentity proof = fixture.proof();
        PortalScene.MeshIdentity equivalent = fixture.proof();
        PortalScene.MeshIdentity context = ClientMeshWorld.meshContext(fixture.snapshot());
        assertNotSame(proof, equivalent);
        assertTrue(proof.same(equivalent));
        assertTrue(directMatch(fixture, proof));
        assertFalse(directMatch(fixture, context));
        assertFalse(ClientMeshWorld.matchesMeshIdentity(fixture.store.view(7), 0L, mock(RegistryAccess.class), context, proof));
        assertFalse(ClientMeshWorld.matchesMeshIdentity(fixture.store.view(7), 0L, fixture.registry, null, proof));
        assertTrue(equivalent.same(proof));
        assertEquals(proof.contextHash(), equivalent.contextHash());
        assertTrue(context.sameContext(proof));
        assertTrue(proof.sameContext(context));
        assertFalse(context.same(proof));
        assertFalse(proof.same(context));
        assertFalse(context.same(context));
    }

    @Test
    public void everyCapturedSectionIncludingMissingNeighborsParticipatesInExactProof() throws Exception {
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    Fixture fixture = new Fixture(OPTIONS, true);
                    PortalScene.MeshIdentity original = fixture.proof();
                    assertEquals(ClientMeshSections.Result.APPLIED, fixture.store.put(section(x, y, z, 2, 4)));
                    PortalScene.MeshIdentity changed = fixture.proof();
                    assertTrue(original.sameContext(changed));
                    assertFalse("Changed section " + x + "," + y + "," + z, original.same(changed));
                    assertFalse(directMatch(fixture, original));
                    assertTrue(directMatch(fixture, changed));
                    assertTrue(fixture.store.drop(7, 1, x, y, z));
                    PortalScene.MeshIdentity missing = fixture.proof();
                    assertTrue(original.sameContext(missing));
                    assertFalse("Missing section " + x + "," + y + "," + z, original.same(missing));
                    assertFalse(directMatch(fixture, changed));
                    assertTrue(directMatch(fixture, missing));
                    assertEquals(ClientMeshSections.Result.APPLIED, fixture.store.put(section(x, y, z, 3, 3)));
                    PortalScene.MeshIdentity restored = fixture.proof();
                    assertFalse("Present section " + x + "," + y + "," + z, missing.same(restored));
                    assertFalse(directMatch(fixture, missing));
                    assertTrue(directMatch(fixture, restored));
                    assertFalse("New immutable section " + x + "," + y + "," + z, original.same(restored));
                }
            }
        }
    }

    @Test
    public void capturedWorldAndProofKeepTheirOriginalInputsAfterViewMutation() throws Exception {
        Fixture fixture = new Fixture(OPTIONS, true);
        ClientMeshWorld world = new ClientMeshWorld(fixture.snapshot());
        PortalScene.MeshIdentity captured = world.meshIdentity();
        assertTrue(captured.same(fixture.proof()));
        assertEquals(ClientMeshSections.Result.APPLIED, fixture.store.put(section(1, 1, 1, 2, 4)));
        assertSame(captured, world.meshIdentity());
        assertFalse(captured.same(fixture.proof()));
        assertFalse(directMatch(fixture, captured));
        assertSame(Blocks.STONE.defaultBlockState(), world.getBlockState(new BlockPos(16, 16, 16)));
        assertSame(Blocks.DIRT.defaultBlockState(), fixture.store.view(7).section(SectionPos.asLong(1, 1, 1)).state(0));
        assertEquals(ClientMeshSections.Result.APPLIED, fixture.store.put(section(0, 0, 0, 2, 4)));
        assertSame(Blocks.STONE.defaultBlockState(), world.getBlockState(BlockPos.ZERO));
        assertSame(captured, world.meshIdentity());
    }

    @Test
    public void registryDimensionBoundsAndBiomeBlendChangesRejectTheSameSectionInputs() throws Exception {
        Fixture fixture = new Fixture(OPTIONS, true);
        PortalScene.MeshIdentity original = fixture.proof();
        assertMismatch(original, ClientMeshWorld.meshIdentity(new ClientMeshWorld.Snapshot(fixture.store.view(7), 0L,
            mock(RegistryAccess.class), ENVIRONMENT, 0)));
        assertMismatch(original, ClientMeshWorld.meshIdentity(new ClientMeshWorld.Snapshot(fixture.store.view(7), 0L,
            fixture.registry, ENVIRONMENT, 1)));
        ClientViewEnvironment.Dimension dimension = ENVIRONMENT.dimension();
        ClientViewEnvironment changed = new ClientViewEnvironment(ENVIRONMENT.gameTime(), ENVIRONMENT.sky(), ENVIRONMENT.fog(),
            ENVIRONMENT.lighting(), ENVIRONMENT.clouds(), ENVIRONMENT.transform(),
            new ClientViewEnvironment.Dimension(dimension.minY(), dimension.height(), dimension.hasSkyLight(),
                ClientViewEnvironment.CardinalLighting.NETHER, dimension.horizonHeight(), dimension.endFlashes()), ENVIRONMENT.world());
        assertMismatch(original, ClientMeshWorld.meshIdentity(new ClientMeshWorld.Snapshot(fixture.store.view(7), 0L,
            fixture.registry, changed, 0)));
        assertTrue(fixture.store.retainLocal(7, 2, new PlateBox(-32, -48, -32, 64, 80, 64), 64));
        assertMismatch(original, fixture.proof());
    }

    @Test
    public void worldTransformEpochAndAuthoritativeTargetSeparateProofsEvenWithSharedSections() throws Exception {
        Fixture fixture = new Fixture(OPTIONS, true);
        PortalScene.MeshIdentity original = fixture.proof();
        ClientViewEnvironment.World world = ENVIRONMENT.world();
        ClientViewEnvironment otherWorld = new ClientViewEnvironment(ENVIRONMENT.gameTime(), ENVIRONMENT.sky(), ENVIRONMENT.fog(),
            ENVIRONMENT.lighting(), ENVIRONMENT.clouds(), ENVIRONMENT.transform(), ENVIRONMENT.dimension(),
            new ClientViewEnvironment.World("minecraft:the_nether", world.clockTime(), world.biomeKey(), world.seaLevel(),
                world.blockLight(), world.skyLight(), world.logicalHeight(), world.hasCeiling(), world.ambientLight(),
                world.eyeMedium(), world.hasFixedTime()));
        ClientViewEnvironment translated = ENVIRONMENT.withTransform(new ClientViewEnvironment.Transform(Direction.E,
            Direction.U, Direction.S, new GeometryVector(16, 0, 0)));
        for (Options options : List.of(new Options(otherWorld, 71, 11), new Options(translated, 71, 11),
            new Options(ENVIRONMENT, 72, 11), new Options(ENVIRONMENT, 71, 12),
            new Options(ENVIRONMENT, 71L << 32, 11), new Options(ENVIRONMENT, 71, 11L << 32))) {
            Fixture changed = new Fixture(options, false);
            for (long key : fixture.store.view(7).sectionKeys()) {
                assertTrue(changed.store.local(7, key, fixture.store.view(7).section(key)));
                assertSame(fixture.store.view(7).section(key), changed.store.view(7).section(key));
            }
            PortalScene.MeshIdentity proof = ClientMeshWorld.meshIdentity(new ClientMeshWorld.Snapshot(changed.store.view(7), 0L,
                fixture.registry, options.environment(), 0));
            if (options.epoch() == 71L << 32 || options.target() == 11L << 32) {
                assertEquals(original.contextHash(), proof.contextHash());
            }
            assertMismatch(original, proof);
            assertFalse(ClientMeshWorld.matchesMeshIdentity(changed.store.view(7), 0L, fixture.registry,
                ClientMeshWorld.meshContext(new ClientMeshWorld.Snapshot(changed.store.view(7), 0L,
                    fixture.registry, options.environment(), 0)), original));
        }
    }

    @Test
    public void historyRestoredUnderANewPortalKeyKeepsTheExactProof() throws Exception {
        Fixture fixture = new Fixture(OPTIONS, true);
        ClientMeshSections.View previous = fixture.store.view(7);
        PortalScene.MeshIdentity original = fixture.proof();
        fixture.store.remove(7);
        assertTrue(fixture.store.begin(19, 2, BOUNDS, 64));
        assertEquals(27, fixture.store.bind(19, new ClientMeshSections.Identity(ENVIRONMENT, 71, 11)).size());
        ClientMeshSections.View restored = fixture.store.view(19);
        assertNotSame(previous, restored);
        for (long key : restored.sectionKeys()) {
            assertSame(previous.section(key), restored.section(key));
        }
        PortalScene.MeshIdentity proof = ClientMeshWorld.meshIdentity(new ClientMeshWorld.Snapshot(restored, 0L,
            fixture.registry, ENVIRONMENT, 0));
        assertTrue(original.sameContext(proof));
        assertTrue(original.same(proof));
        assertTrue(ClientMeshWorld.matchesMeshIdentity(restored, 0L, fixture.registry,
            ClientMeshWorld.meshContext(new ClientMeshWorld.Snapshot(restored, 0L, fixture.registry, ENVIRONMENT, 0)), original));
        assertEquals(original.contextHash(), proof.contextHash());
    }

    private static boolean directMatch(Fixture fixture, PortalScene.MeshIdentity retained) {
        return ClientMeshWorld.matchesMeshIdentity(fixture.store.view(7), 0L, fixture.registry, ClientMeshWorld.meshContext(fixture.snapshot()), retained);
    }

    private static void assertMismatch(PortalScene.MeshIdentity original, PortalScene.MeshIdentity changed) {
        assertFalse(original.sameContext(changed));
        assertFalse(changed.sameContext(original));
        assertFalse(original.same(changed));
        assertFalse(changed.same(original));
    }

    private static ClientViewMessage.MeshSection section(int x, int y, int z, int revision, int state) {
        return new ClientViewMessage.MeshSection(7, 1, x, y, z, revision, state, Brick.single(0, state), SectionBiomes.NONE);
    }

    private static final class Fixture {
        private final ClientMeshSections store;
        private final RegistryAccess registry = mock(RegistryAccess.class);
        private final Options options;

        private Fixture(Options options, boolean populate) throws Exception {
            this.options = options;
            ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
            palette.apply(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(3, "minecraft:stone"),
                new ClientViewMessage.PaletteEntry(4, "minecraft:dirt"))));
            store = new ClientMeshSections(palette, 1024 * 1024);
            store.begin(7, 1, BOUNDS, 64);
            store.bind(7, new ClientMeshSections.Identity(options.environment(), options.epoch(), options.target()));
            if (!populate) {
                return;
            }
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    for (int x = -1; x <= 1; x++) {
                        assertEquals(ClientMeshSections.Result.APPLIED, store.put(section(x, y, z, 1, 3)));
                    }
                }
            }
        }

        private ClientMeshWorld.Snapshot snapshot() {
            return new ClientMeshWorld.Snapshot(store.view(7), 0L, registry, options.environment(), 0);
        }

        private PortalScene.MeshIdentity proof() {
            return ClientMeshWorld.meshIdentity(snapshot());
        }
    }

    private record Options(ClientViewEnvironment environment, long epoch, long target) {
    }
}
