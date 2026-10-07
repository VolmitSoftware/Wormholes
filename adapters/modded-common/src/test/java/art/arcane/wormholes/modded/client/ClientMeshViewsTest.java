package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalScene;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.OptionInstance;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Field;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;

public class ClientMeshViewsTest extends MinecraftTestBase {
    private MockedStatic<Minecraft> minecraftAccess;
    private OptionInstance<Integer> blendRadius;

    @Before
    @SuppressWarnings("unchecked")
    public void gameOptions() throws ReflectiveOperationException {
        Minecraft minecraft = mock(Minecraft.class);
        Options options = mock(Options.class);
        blendRadius = mock(OptionInstance.class);
        when(blendRadius.get()).thenReturn(1);
        when(options.biomeBlendRadius()).thenReturn(blendRadius);
        Field optionsField = Minecraft.class.getField("options");
        optionsField.setAccessible(true);
        optionsField.set(minecraft, options);
        minecraftAccess = mockStatic(Minecraft.class);
        minecraftAccess.when(Minecraft::getInstance).thenReturn(minecraft);
    }

    @After
    public void closeGameOptions() {
        if (minecraftAccess != null) {
            minecraftAccess.close();
        }
    }

    @Test
    public void failedFeatureExtractionRetainsTheSceneAndWaitsForRendererRecovery() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientMeshSections meshes = mock(ClientMeshSections.class);
        ClientMeshSections.View view = mock(ClientMeshSections.View.class);
        ClientPortal portal = mock(ClientPortal.class);
        ApertureDescriptor geometry = mock(ApertureDescriptor.class);
        ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        ClientLevel level = mock(ClientLevel.class);
        ProjectionEnvironment environment = mock(ProjectionEnvironment.class);
        Camera camera = mock(Camera.class);
        when(session.active()).thenReturn(true);
        when(session.meshes()).thenReturn(meshes);
        when(meshes.view(7)).thenReturn(view);
        when(view.changed()).thenReturn(new LongOpenHashSet());
        when(portal.portalKey()).thenReturn(7);
        when(portal.geometry()).thenReturn(geometry);
        when(geometry.sameSurface(geometry)).thenReturn(true);
        when(session.portal(7)).thenReturn(portal);
        Int2ObjectOpenHashMap<ClientPortal> portals = new Int2ObjectOpenHashMap<>();
        portals.put(7, portal);
        when(session.portals()).thenReturn(portals);
        when(session.environment(7)).thenReturn(environment);
        when(environment.transform()).thenReturn(OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.S), 0, 0, 0));
        when(renderer.available(7)).thenReturn(true);
        IllegalStateException failure = new IllegalStateException("Feature renderer unavailable");
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class,
                 (entities, context) -> doThrow(failure).when(entities).extract(eq(7), any(Camera.class), anyFloat(), any()))) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
            ClientMeshViews views = new ClientMeshViews();
            views.update(session, level);
            views.extract(camera, 0.5F);
            verify(renderer).featureFailed(7, failure);
            when(renderer.available(7)).thenReturn(false);
            views.update(session, level);
            views.extract(camera, 0.5F);
            verify(features.constructed().getFirst(), times(1)).extract(eq(7), any(Camera.class), anyFloat(), any());
            verify(renderer, times(1)).replaceScene(eq(7), any(PortalScene.class));
            verify(renderer, never()).remove(7);
        }
    }

    @Test
    public void descendantChangesKeepTheGpuSceneAndPublishCurrentTopology() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientMeshSections meshes = mock(ClientMeshSections.class);
        ClientMeshSections.View view = mock(ClientMeshSections.View.class);
        ClientPortal portal = mock(ClientPortal.class);
        ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        ClientLevel level = mock(ClientLevel.class);
        ApertureDescriptor base = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, true,
            1, 2, new long[]{3}, 0, 0, 1, 64, 3, 0, 0, 0, 0, 0, ApertureDescriptor.KIND_FRAME, 0.0D, 0, 1, List.of());
        when(session.active()).thenReturn(true);
        when(session.meshes()).thenReturn(meshes);
        when(meshes.view(7)).thenReturn(view);
        when(view.changed()).thenReturn(new LongOpenHashSet());
        when(portal.portalKey()).thenReturn(7);
        when(portal.geometry()).thenReturn(base);
        when(session.portal(7)).thenReturn(portal);
        Int2ObjectOpenHashMap<ClientPortal> portals = new Int2ObjectOpenHashMap<>();
        portals.put(7, portal);
        when(session.portals()).thenReturn(portals);
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
            ClientMeshViews views = new ClientMeshViews();
            views.update(session, level);
            ArgumentCaptor<PortalScene> scene = ArgumentCaptor.forClass(PortalScene.class);
            verify(renderer).replaceScene(eq(7), scene.capture());
            ApertureDescriptor first = base.withParent(7);
            ApertureDescriptor second = base.withParent(8);
            List<ApertureDescriptor> changes = List.of(base.withNested(List.of(first)),
                base.withNested(List.of(first.withNested(List.of(second)), second)),
                base.withNested(List.of(second, first)), base);
            for (ApertureDescriptor geometry : changes) {
                when(portal.geometry()).thenReturn(geometry);
                views.update(session, level);
                assertSame(geometry, scene.getValue().geometry());
            }
            verify(renderer, times(1)).replaceScene(eq(7), any(PortalScene.class));
            assertEquals(1, features.constructed().size());
            when(portal.geometry()).thenReturn(surface(base, 1, base.targetIdentity()));
            views.update(session, level);
            verify(renderer, times(2)).replaceScene(eq(7), any(PortalScene.class));
            when(portal.geometry()).thenReturn(surface(base, 1, 99L));
            views.update(session, level);
            verify(renderer, times(3)).replaceScene(eq(7), any(PortalScene.class));
            views.update(session, mock(ClientLevel.class));
            verify(renderer, times(4)).replaceScene(eq(7), any(PortalScene.class));
        }
    }

    @Test
    public void changedDestinationTransformReplacesTheSceneEvenWithIdenticalGeometryAndSectionGeneration() {
        ClientViewSession session = mock(ClientViewSession.class);
        ClientMeshSections meshes = mock(ClientMeshSections.class);
        ClientMeshSections.View view = mock(ClientMeshSections.View.class);
        ClientPortal portal = mock(ClientPortal.class);
        ApertureDescriptor geometry = mock(ApertureDescriptor.class);
        ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        ClientLevel level = mock(ClientLevel.class);
        ProjectionEnvironment environment = mock(ProjectionEnvironment.class);
        when(session.active()).thenReturn(true);
        when(session.meshes()).thenReturn(meshes);
        when(meshes.view(7)).thenReturn(view);
        when(view.changed()).thenReturn(new LongOpenHashSet());
        when(portal.portalKey()).thenReturn(7);
        when(portal.geometry()).thenReturn(geometry);
        when(geometry.sameSurface(geometry)).thenReturn(true);
        when(session.portal(7)).thenReturn(portal);
        Int2ObjectOpenHashMap<ClientPortal> portals = new Int2ObjectOpenHashMap<>();
        portals.put(7, portal);
        when(session.portals()).thenReturn(portals);
        when(session.environment(7)).thenReturn(environment);
        when(environment.transform()).thenReturn(OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.S), 0, 0, 0));
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
            ClientMeshViews views = new ClientMeshViews();
            views.update(session, level);
            views.update(session, level);
            verify(renderer, times(1)).replaceScene(eq(7), any(PortalScene.class));
            when(environment.transform()).thenReturn(OpticTransform.of(AxisPermutation.of(Face.U, Face.W, Face.S), 0, 0, 0));
            views.update(session, level);
            views.update(session, level);
            verify(renderer, times(2)).replaceScene(eq(7), any(PortalScene.class));
            when(session.environment(7)).thenReturn(null);
            views.update(session, level);
            verify(renderer, times(3)).replaceScene(eq(7), any(PortalScene.class));
        }
    }

    @Test
    public void identicalTerrainRebindsFreshFeaturesToTheNewPhysicalLevelWithoutDeletingGpuGeometry() {
        Fixture fixture = new Fixture();
        ClientLevel destination = mock(ClientLevel.class);
        when(destination.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        List<ClientLevel> featureLevels = new ArrayList<>();
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class,
                 (entities, context) -> featureLevels.add((ClientLevel) context.arguments().get(1)))) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            fixture.views.update(fixture.session, fixture.level);
            ClientMeshSections.Identity sameIdentity = new ClientMeshSections.Identity(fixture.environment, 1, 1);
            when(fixture.view.identity()).thenReturn(sameIdentity);
            fixture.views.update(fixture.session, destination);
            fixture.views.update(fixture.session, destination);
            ArgumentCaptor<PortalScene> scene = ArgumentCaptor.forClass(PortalScene.class);
            verify(fixture.renderer).replaceScene(eq(7), any(PortalScene.class));
            verify(fixture.renderer).refreshScene(eq(7), scene.capture(), eq(true));
            assertEquals(List.of(fixture.level, destination), featureLevels);
            Camera camera = mock(Camera.class);
            fixture.views.extract(camera, 0.5F);
            verify(features.constructed().get(1)).extract(7, camera, 0.5F, OpticTransform.IDENTITY);
            fixture.views.detach();
            verify(fixture.renderer).remove(7);
        }
    }

    @Test
    public void changedRegistryCanonicalIdentityViewOrDimensionUsesOrdinarySceneReplacement() {
        Fixture fixture = new Fixture();
        ClientLevel foreignRegistry = mock(ClientLevel.class);
        RegistryAccess foreignAccess = mock(RegistryAccess.class);
        when(foreignRegistry.registryAccess()).thenReturn(foreignAccess);
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            fixture.views.update(fixture.session, fixture.level);
            fixture.views.update(fixture.session, foreignRegistry);
            ClientMeshSections.Identity replacementIdentity = new ClientMeshSections.Identity(fixture.environment, 1, 2);
            when(fixture.view.identity()).thenReturn(replacementIdentity);
            fixture.views.update(fixture.session, foreignRegistry);
            when(fixture.world.dimensionKey()).thenReturn("minecraft:the_nether");
            ClientMeshSections.Identity worldIdentity = new ClientMeshSections.Identity(fixture.environment, 1, 2);
            when(fixture.view.identity()).thenReturn(worldIdentity);
            fixture.views.update(fixture.session, foreignRegistry);
            when(fixture.environment.dimension()).thenReturn(new ProjectionEnvironment.Dimension(-64, 384, true,
                ProjectionEnvironment.CardinalLighting.NETHER, 63, false));
            fixture.views.update(fixture.session, foreignRegistry);
            ClientMeshSections.View replacement = mock(ClientMeshSections.View.class);
            when(replacement.changed()).thenReturn(new LongOpenHashSet());
            when(fixture.meshes.view(7)).thenReturn(replacement);
            fixture.views.update(fixture.session, foreignRegistry);
            verify(fixture.renderer, times(6)).replaceScene(eq(7), any(PortalScene.class));
            verify(fixture.renderer, never()).refreshScene(eq(7), any(PortalScene.class), anyBoolean());
            assertEquals(6, features.constructed().size());
        }
    }

    @Test
    public void unknownCanonicalIdentityDoesNotAuthorizeTerrainAcrossPhysicalLevels() {
        Fixture fixture = new Fixture();
        when(fixture.view.identity()).thenReturn(null);
        ClientLevel destination = mock(ClientLevel.class);
        when(destination.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            fixture.views.update(fixture.session, fixture.level);
            fixture.views.update(fixture.session, destination);
            verify(fixture.renderer, times(2)).replaceScene(eq(7), any(PortalScene.class));
            verify(fixture.renderer, never()).refreshScene(eq(7), any(PortalScene.class), anyBoolean());
            assertEquals(2, features.constructed().size());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void biomeBlendOptionChangeReplacesTerrainAndEachSceneBuildUsesItsCapturedRadius() {
        Fixture fixture = new Fixture();
        RegistryAccess registry = mock(RegistryAccess.class);
        Registry<Biome> biomes = mock(Registry.class);
        when(registry.lookupOrThrow(Registries.BIOME)).thenReturn(biomes);
        when(fixture.level.registryAccess()).thenReturn(registry);
        List<Integer> snapshotRadii = new ArrayList<>();
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class);
             MockedConstruction<ClientMeshWorld> worlds = mockConstruction(ClientMeshWorld.class,
                 (world, context) -> snapshotRadii.add(((ClientMeshWorld.Snapshot) context.arguments().getFirst()).blendRadius()))) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            fixture.views.update(fixture.session, fixture.level);
            when(blendRadius.get()).thenReturn(2);
            fixture.views.update(fixture.session, fixture.level);
            ArgumentCaptor<PortalScene> scenes = ArgumentCaptor.forClass(PortalScene.class);
            verify(fixture.renderer, times(2)).replaceScene(eq(7), scenes.capture());
            scenes.getAllValues().get(0).world(0L);
            scenes.getAllValues().get(1).world(0L);
            assertEquals(List.of(1, 2), snapshotRadii);
            assertEquals(2, features.constructed().size());
            assertEquals(2, worlds.constructed().size());
        }
    }

    @Test
    public void unchangedSceneReusesOneImmutableContextWithoutRepeatedFactories() {
        Fixture fixture = new Fixture();
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class);
             MockedStatic<ClientMeshWorld> worlds = mockStatic(ClientMeshWorld.class, CALLS_REAL_METHODS)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            fixture.views.update(fixture.session, fixture.level);
            ArgumentCaptor<PortalScene> captured = ArgumentCaptor.forClass(PortalScene.class);
            verify(fixture.renderer).replaceScene(eq(7), captured.capture());
            PortalScene scene = captured.getValue();
            PortalScene.MeshIdentity context = scene.meshContext();
            PortalScene.MeshIdentity proof = scene.meshIdentity(0L);
            assertNotNull(context);
            for (int lookup = 0; lookup < 100; lookup++) {
                assertSame(context, scene.meshContext());
                assertTrue(scene.matchesMeshIdentity(0L, proof));
            }
            worlds.verify(() -> ClientMeshWorld.meshIdentity(any(ClientMeshWorld.Snapshot.class)), times(1));
            when(fixture.environment.gameTime()).thenReturn(200L);
            fixture.views.update(fixture.session, fixture.level);
            assertSame(context, scene.meshContext());
            worlds.verify(() -> ClientMeshWorld.meshContext(any(ClientMeshWorld.Snapshot.class)), times(1));
            verify(fixture.renderer, times(1)).replaceScene(eq(7), any(PortalScene.class));
        }
    }

    @Test
    public void contextChangesRejectStaleLookupBeforeSceneUpdateAndInstallFreshContextAfterward() {
        Fixture fixture = new Fixture();
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            ArgumentCaptor<PortalScene> captured = ArgumentCaptor.forClass(PortalScene.class);
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer).replaceScene(eq(7), captured.capture());
            PortalScene first = captured.getValue();
            PortalScene.MeshIdentity firstContext = first.meshContext();
            PortalScene.MeshIdentity firstProof = first.meshIdentity(0L);
            assertTrue(first.matchesMeshIdentity(0L, firstProof));
            when(fixture.view.bounds()).thenReturn(new BlockBox(-16, -32, -16, 48, 256, 48));
            assertNull(first.meshContext());
            assertFalse(first.matchesMeshIdentity(0L, firstProof));
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer, times(2)).replaceScene(eq(7), captured.capture());
            PortalScene second = captured.getValue();
            assertNotNull(second.meshContext());
            assertNotSame(firstContext, second.meshContext());
            assertNull(first.meshContext());
            ClientMeshSections.Identity rebound = new ClientMeshSections.Identity(fixture.environment, 1, 2);
            when(fixture.view.identity()).thenReturn(rebound);
            assertNull(second.meshContext());
            assertFalse(second.matchesMeshIdentity(0L, firstProof));
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer, times(3)).replaceScene(eq(7), captured.capture());
            PortalScene third = captured.getValue();
            assertNotNull(third.meshContext());
            when(fixture.environment.dimension()).thenReturn(new ProjectionEnvironment.Dimension(-32, 256, false,
                ProjectionEnvironment.CardinalLighting.NETHER, 63, false));
            assertNull(third.meshContext());
            assertFalse(third.matchesMeshIdentity(0L, firstProof));
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer, times(4)).replaceScene(eq(7), captured.capture());
            PortalScene fourth = captured.getValue();
            assertNotNull(fourth.meshContext());
            when(fixture.environment.transform()).thenReturn(OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.S), 16, 0, 0));
            assertNull(fourth.meshContext());
            assertFalse(fourth.matchesMeshIdentity(0L, firstProof));
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer, times(5)).replaceScene(eq(7), captured.capture());
            PortalScene awaitingBinding = captured.getValue();
            assertNull(awaitingBinding.meshContext());
            rebound = new ClientMeshSections.Identity(fixture.environment, 1, 2);
            when(fixture.view.identity()).thenReturn(rebound);
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer, times(6)).replaceScene(eq(7), captured.capture());
            assertNotNull(captured.getValue().meshContext());
            when(fixture.world.dimensionKey()).thenReturn("minecraft:the_nether");
            assertNull(captured.getValue().meshContext());
            when(fixture.session.environment(7)).thenReturn(null);
            assertNull(captured.getValue().meshContext());
        }
    }

    @Test
    public void freshSceneConsumesPendingSectionChangesWithoutInvalidatingUnownedGeometry() {
        Fixture fixture = new Fixture();
        LongLinkedOpenHashSet changed = new LongLinkedOpenHashSet(new long[]{0L, SectionPos.asLong(1, 0, 0)});
        when(fixture.view.changed()).thenReturn(changed);
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer).replaceScene(eq(7), any(PortalScene.class));
            verify(fixture.renderer, never()).invalidate(eq(7), anyLong(), anyBoolean());
            assertTrue(changed.isEmpty());
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer, never()).invalidate(eq(7), anyLong(), anyBoolean());
        }
    }

    @Test
    public void retainedSceneAndSameRegistryRebindKeepNeighborInvalidationButNewRegistryReplacesIt() {
        Fixture fixture = new Fixture();
        LongLinkedOpenHashSet changed = new LongLinkedOpenHashSet();
        when(fixture.view.changed()).thenReturn(changed);
        ClientLevel retainedLevel = mock(ClientLevel.class);
        when(retainedLevel.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ClientLevel changedRegistry = mock(ClientLevel.class);
        RegistryAccess foreignAccess = mock(RegistryAccess.class);
        when(changedRegistry.registryAccess()).thenReturn(foreignAccess);
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            fixture.views.update(fixture.session, fixture.level);
            changed.add(0L);
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer, times(27)).invalidate(eq(7), anyLong(), anyBoolean());
            verify(fixture.renderer).invalidate(7, 0L, true);
            assertTrue(changed.isEmpty());
            long next = SectionPos.asLong(1, 0, 0);
            changed.add(next);
            fixture.views.update(fixture.session, retainedLevel);
            verify(fixture.renderer).refreshScene(eq(7), any(PortalScene.class), eq(true));
            verify(fixture.renderer, times(54)).invalidate(eq(7), anyLong(), anyBoolean());
            verify(fixture.renderer).invalidate(7, next, true);
            assertTrue(changed.isEmpty());
            changed.add(SectionPos.asLong(2, 0, 0));
            fixture.views.update(fixture.session, changedRegistry);
            verify(fixture.renderer, times(2)).replaceScene(eq(7), any(PortalScene.class));
            verify(fixture.renderer, times(54)).invalidate(eq(7), anyLong(), anyBoolean());
            assertTrue(changed.isEmpty());
        }
    }

    @Test
    public void consumedFreshHistoryChangesStillRejectACompiledMeshWithAChangedNeighbor() throws Exception {
        Fixture fixture = new Fixture();
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:stone"),
            new ViewStreamMessage.PaletteEntry(4, "minecraft:dirt"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1024 * 1024);
        BlockBox bounds = fixture.view.bounds();
        ClientMeshSections.Identity identity = new ClientMeshSections.Identity(fixture.environment, 1, 1);
        store.begin(7, 1, bounds, 64);
        store.bind(7, identity);
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE));
        store.put(new ViewStreamMessage.MeshSection(7, 1, 1, 0, 0, 1, 3, Brick.single(0, 3), SectionBiomes.NONE));
        ClientMeshSections.View previous = store.view(7);
        PortalScene.MeshIdentity compiled = ClientMeshWorld.meshIdentity(new ClientMeshWorld.Snapshot(previous, 0L,
            RegistryAccess.EMPTY, fixture.environment, 1));
        store.remove(7);
        store.begin(7, 2, bounds, 64);
        store.bind(7, identity);
        ClientMeshSections.View current = store.view(7);
        assertSame(previous.section(0L), current.section(0L));
        store.put(new ViewStreamMessage.MeshSection(7, 2, 1, 0, 0, 2, 4, Brick.single(0, 4), SectionBiomes.NONE));
        assertFalse(current.changed().isEmpty());
        when(fixture.meshes.view(7)).thenReturn(current);
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            fixture.views.update(fixture.session, fixture.level);
            ArgumentCaptor<PortalScene> scenes = ArgumentCaptor.forClass(PortalScene.class);
            verify(fixture.renderer).replaceScene(eq(7), scenes.capture());
            verify(fixture.renderer, never()).invalidate(eq(7), anyLong(), anyBoolean());
            assertTrue(current.changed().isEmpty());
            PortalScene.MeshIdentity refreshed = scenes.getValue().meshIdentity(0L);
            assertTrue(compiled.sameContext(refreshed));
            assertFalse(compiled.same(refreshed));
        }
    }

    @Test
    public void overlappingChangesInvalidateNeighborsOnceInFirstSeenOrderBeforePrioritizingCenters() {
        Fixture fixture = new Fixture();
        long first = SectionPos.asLong(-1, -2, -3);
        long second = SectionPos.asLong(0, -2, -3);
        LongLinkedOpenHashSet changed = new LongLinkedOpenHashSet();
        when(fixture.view.changed()).thenReturn(changed);
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            fixture.views.update(fixture.session, fixture.level);
            changed.add(first);
            changed.add(second);
            fixture.views.update(fixture.session, fixture.level);
            ArgumentCaptor<Long> coordinates = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Boolean> priorities = ArgumentCaptor.forClass(Boolean.class);
            verify(fixture.renderer, times(38)).invalidate(eq(7), coordinates.capture(), priorities.capture());
            List<Long> expected = neighbors(first);
            for (long neighbor : neighbors(second)) {
                if (!expected.contains(neighbor)) {
                    expected.add(neighbor);
                }
            }
            assertEquals(36, expected.size());
            expected.add(first);
            expected.add(second);
            assertEquals(expected, coordinates.getAllValues());
            assertTrue(priorities.getAllValues().subList(0, 36).stream().noneMatch(Boolean::booleanValue));
            assertEquals(List.of(true, true), priorities.getAllValues().subList(36, 38));
            assertEquals(SectionPos.asLong(-2, -3, -4), coordinates.getAllValues().getFirst().longValue());
            assertTrue(changed.isEmpty());
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer, times(38)).invalidate(eq(7), anyLong(), anyBoolean());
            changed.add(first);
            fixture.views.update(fixture.session, fixture.level);
            verify(fixture.renderer, times(65)).invalidate(eq(7), anyLong(), anyBoolean());
        }
    }

    @Test
    public void neighborDeduplicationRemainsIndependentForEachPortalView() {
        Fixture fixture = new Fixture();
        long first = SectionPos.asLong(-1, -2, -3);
        long second = SectionPos.asLong(0, -2, -3);
        when(fixture.view.changed()).thenReturn(new LongLinkedOpenHashSet());
        ClientMeshSections.View otherView = mock(ClientMeshSections.View.class);
        ClientPortal otherPortal = mock(ClientPortal.class);
        BlockBox bounds = fixture.view.bounds();
        ClientMeshSections.Identity identity = fixture.view.identity();
        ApertureDescriptor geometry = fixture.portal.geometry();
        when(otherView.changed()).thenReturn(new LongLinkedOpenHashSet());
        when(otherView.bounds()).thenReturn(bounds);
        when(otherView.identity()).thenReturn(identity);
        when(otherPortal.portalKey()).thenReturn(8);
        when(otherPortal.geometry()).thenReturn(geometry);
        when(fixture.meshes.view(8)).thenReturn(otherView);
        when(fixture.session.portal(8)).thenReturn(otherPortal);
        when(fixture.session.environment(8)).thenReturn(fixture.environment);
        fixture.session.portals().put(8, otherPortal);
        try (MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
             MockedConstruction<ClientMeshEntities> features = mockConstruction(ClientMeshEntities.class)) {
            renderers.when(ClientPortalRenderer::instance).thenReturn(fixture.renderer);
            fixture.views.update(fixture.session, fixture.level);
            fixture.view.changed().add(first);
            fixture.view.changed().add(second);
            otherView.changed().add(first);
            otherView.changed().add(second);
            fixture.views.update(fixture.session, fixture.level);
            ArgumentCaptor<Long> firstCoordinates = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> secondCoordinates = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Boolean> firstPriorities = ArgumentCaptor.forClass(Boolean.class);
            ArgumentCaptor<Boolean> secondPriorities = ArgumentCaptor.forClass(Boolean.class);
            verify(fixture.renderer, times(38)).invalidate(eq(7), firstCoordinates.capture(), firstPriorities.capture());
            verify(fixture.renderer, times(38)).invalidate(eq(8), secondCoordinates.capture(), secondPriorities.capture());
            assertEquals(firstCoordinates.getAllValues(), secondCoordinates.getAllValues());
            assertEquals(firstPriorities.getAllValues(), secondPriorities.getAllValues());
            assertTrue(fixture.view.changed().isEmpty());
            assertTrue(otherView.changed().isEmpty());
        }
    }

    private static List<Long> neighbors(long center) {
        List<Long> neighbors = new ArrayList<>(26);
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    if (x != 0 || y != 0 || z != 0) {
                        neighbors.add(SectionPos.asLong(SectionPos.x(center) + x, SectionPos.y(center) + y,
                            SectionPos.z(center) + z));
                    }
                }
            }
        }
        return neighbors;
    }

    private static final class Fixture {
        private final ClientViewSession session = mock(ClientViewSession.class);
        private final ClientMeshSections meshes = mock(ClientMeshSections.class);
        private final ClientMeshSections.View view = mock(ClientMeshSections.View.class);
        private final ClientPortal portal = mock(ClientPortal.class);
        private final ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        private final ClientLevel level = mock(ClientLevel.class);
        private final ProjectionEnvironment environment = mock(ProjectionEnvironment.class);
        private final ProjectionEnvironment.World world = mock(ProjectionEnvironment.World.class);
        private final ClientMeshViews views = new ClientMeshViews();

        private Fixture() {
            when(session.active()).thenReturn(true);
            when(session.meshes()).thenReturn(meshes);
            when(meshes.view(7)).thenReturn(view);
            when(view.changed()).thenReturn(new LongOpenHashSet());
            when(view.bounds()).thenReturn(new BlockBox(-16, -64, -16, 48, 384, 48));
            when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            ApertureDescriptor geometry = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, true,
                1, 2, new long[]{3}, 0, 0, 1, 64, 3, 0, 0, 0, 0, 0, ApertureDescriptor.KIND_FRAME, 0.0D, 0, 1, List.of());
            when(portal.portalKey()).thenReturn(7);
            when(portal.geometry()).thenReturn(geometry);
            when(session.portal(7)).thenReturn(portal);
            Int2ObjectOpenHashMap<ClientPortal> portals = new Int2ObjectOpenHashMap<>();
            portals.put(7, portal);
            when(session.portals()).thenReturn(portals);
            when(session.environment(7)).thenReturn(environment);
            when(environment.transform()).thenReturn(OpticTransform.IDENTITY);
            when(world.dimensionKey()).thenReturn("minecraft:overworld");
            when(environment.world()).thenReturn(world);
            ClientMeshSections.Identity identity = new ClientMeshSections.Identity(environment, 1, 1);
            when(view.identity()).thenReturn(identity);
            when(environment.dimension()).thenReturn(new ProjectionEnvironment.Dimension(-64, 384, true,
                ProjectionEnvironment.CardinalLighting.DEFAULT, 63, false));
            when(renderer.available(7)).thenReturn(true);
        }
    }

    private static ApertureDescriptor surface(ApertureDescriptor base, int quarterTurns, long targetIdentity) {
        return new ApertureDescriptor(base.originX(), base.originY(), base.originZ(), base.facing(), base.frontSide(), quarterTurns,
            base.mirror(), base.apertureWidth(), base.apertureHeight(), base.apertureMask(), base.nearPlanePadding(),
            base.aperturePadding(), base.frustumCullingRatio(), base.depthBlocks(), base.recursionDepth(), base.blackoutPolicy(),
            base.blackoutState(), base.maskAirPolicy(), base.lightingPolicy(), base.fidelityFlags(), base.kind(), base.planeOffset(),
            base.parentPortalKey(), targetIdentity, base.nested());
    }
}
