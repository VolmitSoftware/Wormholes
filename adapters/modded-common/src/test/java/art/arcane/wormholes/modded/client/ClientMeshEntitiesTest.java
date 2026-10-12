package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.SeamlessEntityAccess;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.transform.Affine;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityAttachments;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientMeshEntitiesTest extends MinecraftTestBase {
    @Test
    public void meshOwnershipWaitsForIdAssignmentAndUsesTheAssignedId() {
        ClientLevel level = mock(ClientLevel.class);
        Entity entity = mock(Entity.class, withSettings().extraInterfaces(SeamlessEntityAccess.class));
        when(entity.level()).thenReturn(level);
        SeamlessEntityAccess access = (SeamlessEntityAccess) entity;
        doThrow(new IllegalStateException("Tried to access entity ID before ID assignment")).when(entity).getId();
        WormholesClient client = mock(WormholesClient.class);
        when(client.viewsAttachedTo(level)).thenReturn(true);
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientProjectedEntities projected = mock(ClientProjectedEntities.class);
        ClientReflectionEntity reflections = mock(ClientReflectionEntity.class);
        when(client.tickState()).thenReturn(tick);
        when(tick.entities()).thenReturn(projected);
        when(client.reflections()).thenReturn(reflections);
        int assignedId = ClientEntityIds.PROJECTED_MAX;
        when(projected.meshEntity(assignedId)).thenReturn(true);
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            assertFalse(ClientMeshEntities.hiddenFromWorld(entity));
            verifyNoInteractions(tick, projected, reflections);
            when(access.wormholesEntityId()).thenReturn(assignedId);
            assertTrue(ClientMeshEntities.hiddenFromWorld(entity));
            when(projected.meshEntity(assignedId)).thenReturn(false);
            assertFalse(ClientMeshEntities.hiddenFromWorld(entity));
            when(reflections.meshEntity(assignedId)).thenReturn(true);
            assertTrue(ClientMeshEntities.hiddenFromWorld(entity));
        }
        verify(entity, never()).getId();
    }

    @Test
    public void localEntitiesUseIndependentRenderStatesWithoutTickingOrMutatingSourceEntities() {
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities first = new ClientMeshEntities(mock(ClientMeshSections.View.class), level);
        ClientMeshEntities second = new ClientMeshEntities(mock(ClientMeshSections.View.class), level);
        Entity entity = mock(Entity.class);
        UUID id = UUID.randomUUID();
        when(entity.getUUID()).thenReturn(id);
        when(level.entitiesForRendering()).thenReturn(List.of(entity));
        EntityRenderDispatcher renderer = mock(EntityRenderDispatcher.class);
        EntityRenderState firstState = new EntityRenderState();
        EntityRenderState secondState = new EntityRenderState();
        when(renderer.extractEntity(entity, 0.5F)).thenAnswer(call -> {
            assertNull(ClientMeshEntities.active());
            return firstState;
        }).thenAnswer(call -> {
            assertNull(ClientMeshEntities.active());
            return secondState;
        });
        List<EntityRenderState> firstStates = new ArrayList<>();
        List<EntityRenderState> secondStates = new ArrayList<>();
        try (MockedStatic<ClientMeshEntities> ownership = mockStatic(ClientMeshEntities.class, CALLS_REAL_METHODS)) {
            ownership.when(() -> ClientMeshEntities.hiddenFromWorld(entity)).thenReturn(false);
            clearInvocations(entity);
            first.inDestinationWorld(() -> {
                first.extractLocalEntities(Set.of(id), renderer, 0.5F, firstStates);
                assertSame(first, ClientMeshEntities.active(level));
            });
            second.inDestinationWorld(() -> {
                second.extractLocalEntities(Set.of(id), renderer, 0.5F, secondStates);
                assertSame(second, ClientMeshEntities.active(level));
            });
        }
        assertEquals(List.of(firstState), firstStates);
        assertEquals(List.of(secondState), secondStates);
        assertNotSame(firstStates.getFirst(), secondStates.getFirst());
        assertNull(ClientMeshEntities.active());
        verify(entity, times(2)).getUUID();
        verify(entity, times(2)).isRemoved();
        verifyNoMoreInteractions(entity);
    }

    @Test
    public void localEntityExtractionSkipsUncoveredRemovedAndOwnedCopies() {
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(mock(ClientMeshSections.View.class), level);
        Entity uncovered = mock(Entity.class);
        Entity removed = mock(Entity.class);
        Entity owned = mock(Entity.class);
        UUID removedId = UUID.randomUUID();
        UUID ownedId = UUID.randomUUID();
        when(uncovered.getUUID()).thenReturn(UUID.randomUUID());
        when(removed.getUUID()).thenReturn(removedId);
        when(owned.getUUID()).thenReturn(ownedId);
        when(removed.isRemoved()).thenReturn(true);
        when(level.entitiesForRendering()).thenReturn(List.of(uncovered, removed, owned));
        EntityRenderDispatcher renderer = mock(EntityRenderDispatcher.class);
        List<EntityRenderState> states = new ArrayList<>();
        try (MockedStatic<ClientMeshEntities> ownership = mockStatic(ClientMeshEntities.class, CALLS_REAL_METHODS)) {
            ownership.when(() -> ClientMeshEntities.hiddenFromWorld(removed)).thenReturn(false);
            ownership.when(() -> ClientMeshEntities.hiddenFromWorld(owned)).thenReturn(true);
            scene.extractLocalEntities(Set.of(removedId, ownedId), renderer, 0.25F, states);
        }
        assertTrue(states.isEmpty());
        assertNull(ClientMeshEntities.active());
        verifyNoInteractions(renderer);
        verify(uncovered).getUUID();
        verifyNoMoreInteractions(uncovered);
    }

    @Test
    public void localEntityExtractionRestoresDestinationScopeAfterRenderFailure() {
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(mock(ClientMeshSections.View.class), level);
        Entity entity = mock(Entity.class);
        UUID id = UUID.randomUUID();
        when(entity.getUUID()).thenReturn(id);
        when(level.entitiesForRendering()).thenReturn(List.of(entity));
        EntityRenderDispatcher renderer = mock(EntityRenderDispatcher.class);
        when(renderer.extractEntity(entity, 0.5F)).thenAnswer(call -> {
            assertNull(ClientMeshEntities.active());
            throw new IllegalStateException("local render failed");
        });
        try (MockedStatic<ClientMeshEntities> ownership = mockStatic(ClientMeshEntities.class, CALLS_REAL_METHODS)) {
            ownership.when(() -> ClientMeshEntities.hiddenFromWorld(entity)).thenReturn(false);
            scene.inDestinationWorld(() -> {
                assertThrows(IllegalStateException.class,
                    () -> scene.extractLocalEntities(Set.of(id), renderer, 0.5F, new ArrayList<>()));
                assertSame(scene, ClientMeshEntities.active(level));
            });
            assertNull(ClientMeshEntities.active());
            assertThrows(IllegalStateException.class,
                () -> scene.extractLocalEntities(Set.of(id), renderer, 0.5F, new ArrayList<>()));
            assertNull(ClientMeshEntities.active());
        }
    }

    @Test
    public void nestedFeatureCameraFollowsRotatedAndReflectedAncestorSpaces() {
        OpticTransform root = OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 100, 20, -50);
        OpticTransform child = OpticTransform.of(AxisPermutation.of(Face.W, Face.U, Face.S), 6, 0, 0);
        ClientViewSession session = mock(ClientViewSession.class);
        bindCameraPortal(session, 1, 0, PortalEnvironmentTest.environment(root), null);
        bindCameraPortal(session, 2, 1, PortalEnvironmentTest.environment(child), null);
        Vec3d eye = new Vec3d(96, 23, -46);
        assertEquals(new Vec3d(2, 3, 4), ClientMeshEntities.cameraSpace(session, 2, child).point(eye));
        assertEquals(new Vec3d(4, 3, 4), ClientMeshEntities.cameraSpace(session, 1, root).point(eye));
        assertEquals(new Vec3d(4, 3, 4), ClientMeshEntities.cameraSpace(null, 1, root).point(eye));
        assertEquals(eye, ClientMeshEntities.cameraSpace(null, 1, PortalEnvironmentTest.identity()).point(eye));
    }

    @Test
    public void affineEyeAndBasisUseNativeDestinationCoordinatesWithFiniteOrthogonalBillboards() {
        Affine mapping = Affine.of(2, 0, 1, 100, 0, 3, 1, -2, 0, 0, 0.5D, 3);
        OpticTransform grid = OpticTransform.of(AxisPermutation.of(Face.W, Face.U, Face.S), 40, 20, -10);
        ClientViewSession session = mock(ClientViewSession.class);
        bindCameraPortal(session, 1, 0, PortalEnvironmentTest.environment(grid).withScale(0.25F), mapping);
        Affine rootToNative = ClientMeshEntities.cameraSpace(session, 1, grid);
        assertEquals(mapping, rootToNative);
        Camera root = rootCamera(new Vec3(1, 2, 3));
        ClientMeshEntities.SceneCamera camera = new ClientMeshEntities.SceneCamera();
        camera.update(root, rootToNative);
        assertEquals(new Vec3(105, 7, 4.5D), camera.position());
        assertEquals(1.0F, camera.forwardVector().length(), 1.0E-6F);
        assertEquals(1.0F, camera.upVector().length(), 1.0E-6F);
        assertEquals(1.0F, camera.leftVector().length(), 1.0E-6F);
        assertEquals(0.0F, camera.forwardVector().dot(camera.upVector()), 1.0E-6F);
        assertEquals(0.0F, camera.forwardVector().dot(camera.leftVector()), 1.0E-6F);
        assertEquals(0.0F, camera.upVector().dot(camera.leftVector()), 1.0E-6F);
        assertEquals(1.0F, camera.rotation().lengthSquared(), 1.0E-6F);
        Vector3f expectedForward = new Vector3f(1, 1, 0.5F).normalize();
        assertVector(expectedForward, camera.forwardVector());
        assertTrue(camera.isDetached());
        assertTrue(camera.isInitialized());
        Affine reflected = Affine.scale(-1, 1, 1).compose(mapping);
        camera.update(root, reflected);
        assertEquals(1.0F, camera.rotation().lengthSquared(), 1.0E-6F);
        assertEquals(0.0F, camera.forwardVector().dot(camera.upVector()), 1.0E-6F);
        assertEquals(new Vec3(-105, 7, 4.5D), camera.position());
        camera.update(root, Affine.of(1, 1.0E8D, 1.0E8D, 0, 0, 1.0E8D, 1.0E8D, 0, 0, 1.0E8D, 1.0E8D + 1, 0));
        assertEquals(1.0F, camera.forwardVector().length(), 1.0E-6F);
        assertEquals(1.0F, camera.upVector().length(), 1.0E-6F);
        assertEquals(1.0F, camera.leftVector().length(), 1.0E-6F);
        assertEquals(0.0F, camera.forwardVector().dot(camera.upVector()), 1.0E-6F);
        assertEquals(1.0F, camera.rotation().lengthSquared(), 1.0E-6F);
    }

    @Test
    public void nestedAffineAndRigidScaledEndpointsComposeInDrawingOrder() {
        Affine root = Affine.translation(100, -2, 3).compose(Affine.shear(0.5D, 0, 0, 0, 0, 0));
        OpticTransform grid = OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 20, 10, -30);
        EnvironmentState childEnvironment = PortalEnvironmentTest.environment(grid).withScale(0.5F);
        ClientViewSession session = mock(ClientViewSession.class);
        bindCameraPortal(session, 1, 0, PortalEnvironmentTest.environment(PortalEnvironmentTest.identity()), root);
        bindCameraPortal(session, 2, 1, childEnvironment, null);
        Vec3d eye = new Vec3d(1, 2, 3);
        Vec3d parentEye = root.point(eye);
        Vec3d center = new Vec3d(11, 65, -3.5D);
        Vec3d unscaled = parentEye.subtract(center).multiply(2.0D).add(center);
        Vec3d expected = grid.inverse().point(unscaled);
        assertEquals(expected, ClientMeshEntities.cameraSpace(session, 2, grid).point(eye));
        Affine child = Affine.translation(-4, 8, 2).compose(Affine.scale(0.5D, 2, 3));
        bindCameraPortal(session, 2, 1, childEnvironment, child);
        assertEquals(child.point(root.point(eye)), ClientMeshEntities.cameraSpace(session, 2, grid).point(eye));
        assertEquals(child.vector(root.vector(new Vec3d(0, 1, 0))),
            ClientMeshEntities.cameraSpace(session, 2, grid).vector(new Vec3d(0, 1, 0)));
        when(session.affine(1)).thenReturn(null);
        assertEquals(child.point(eye), ClientMeshEntities.cameraSpace(session, 2, grid).point(eye));
    }

    @Test
    public void extractionPreparesAffineNameDistancesAndBlockCameraThenRestoresAfterFailure() {
        ClientMeshSections.View view = mock(ClientMeshSections.View.class);
        when(view.sectionKeys()).thenReturn(new LongOpenHashSet());
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(view, level);
        ClientViewSession session = mock(ClientViewSession.class);
        OpticTransform identity = PortalEnvironmentTest.identity();
        bindCameraPortal(session, 7, 0, PortalEnvironmentTest.environment(identity), Affine.translation(100, 0, 0));
        WormholesClient client = mock(WormholesClient.class);
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientLocalMeshSources local = mock(ClientLocalMeshSources.class);
        ClientReflectionEntity reflections = mock(ClientReflectionEntity.class);
        when(client.session()).thenReturn(session);
        when(client.tickState()).thenReturn(tick);
        when(client.localMeshes()).thenReturn(local);
        when(client.reflections()).thenReturn(reflections);
        Entity entity = mock(Entity.class);
        UUID id = UUID.randomUUID();
        when(entity.getUUID()).thenReturn(id);
        when(entity.position()).thenReturn(new Vec3(101, 0, 10));
        when(entity.shouldShowName()).thenReturn(true);
        when(entity.getDisplayName()).thenReturn(Component.literal("Visible name"));
        when(entity.belowNameDisplay()).thenReturn(Component.literal("Visible score"));
        when(entity.getAttachments()).thenReturn(mock(EntityAttachments.class));
        when(level.entitiesForRendering()).thenReturn(List.of(entity));
        when(local.entities(7)).thenReturn(Set.of(id));
        EntityRenderDispatcher entities = mock(EntityRenderDispatcher.class, CALLS_REAL_METHODS);
        EntityRendererProvider.Context context = mock(EntityRendererProvider.Context.class);
        when(context.getEntityRenderDispatcher()).thenReturn(entities);
        CameraNameRenderer names = new CameraNameRenderer(context);
        BlockEntityRenderDispatcher blocks = mock(BlockEntityRenderDispatcher.class);
        Camera source = rootCamera(new Vec3(0, 0, 10));
        entities.prepare(source, null);
        EntityRenderState distant = names.names(entity);
        assertEquals(10201.0D, distant.distanceToCameraSq, 0.0D);
        assertNull(distant.nameTag);
        assertNull(distant.scoreText);
        doAnswer(call -> names.names(entity)).when(entities).extractEntity(entity, 0.5F);
        Minecraft minecraft = mock(Minecraft.class);
        when(minecraft.getEntityRenderDispatcher()).thenReturn(entities);
        when(minecraft.getBlockEntityRenderDispatcher()).thenReturn(blocks);
        try (MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
             MockedStatic<ProjectedEntityGuard> ownership = mockStatic(ProjectedEntityGuard.class)) {
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            clients.when(WormholesClient::instance).thenReturn(client);
            scene.extract(7, source, 0.5F, identity);
            EntityRenderState visible = scene.entities().getFirst();
            assertEquals(1.0D, visible.distanceToCameraSq, 0.0D);
            assertSame(entity.getDisplayName(), visible.nameTag);
            assertSame(entity.belowNameDisplay(), visible.scoreText);
            verify(blocks).prepare(new Vec3(100, 0, 10));
            assertSame(source, entities.camera);
            assertNull(ClientMeshEntities.active());
            doThrow(new IllegalStateException("extraction failed")).when(entities).extractEntity(entity, 0.5F);
            assertThrows(IllegalStateException.class, () -> scene.extract(7, source, 0.5F, identity));
            assertSame(source, entities.camera);
            verify(blocks, times(2)).prepare(source.position());
            assertNull(ClientMeshEntities.active());
        }
        BlockEntity block = mock(BlockEntity.class);
        when(block.getBlockPos()).thenReturn(new BlockPos(100, 0, 10));
        BlockEntityRenderer<BlockEntity, BlockEntityRenderState> blockRenderer = mock(BlockEntityRenderer.class, CALLS_REAL_METHODS);
        assertFalse(blockRenderer.shouldRender(block, source.position()));
        assertTrue(blockRenderer.shouldRender(block, new Vec3(100, 0, 10)));
    }

    @Test
    public void halfBlockTranslationQueriesKeepTheCapturedCellAtExactCenterTies() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:chest[facing=north]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new BlockBox(0, 0, 0, 16, 16, 16), 1);
        byte[] light = new byte[2048];
        light[0] = 7;
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, oneBlock(3).withLight(light, new byte[2048]), SectionBiomes.NONE));
        ClientMeshEntities scene = new ClientMeshEntities(store.view(7), mock(ClientLevel.class));
        for (Face[] axes : new Face[][] {{Face.E, Face.U, Face.S}, {Face.W, Face.D, Face.N},
            {Face.U, Face.W, Face.S}}) {
            scene.synchronize(OpticTransform.of(AxisPermutation.of(axes[0], axes[1], axes[2]), 0.5D, 0.5D, 0.5D));
            BlockEntity chest = scene.blockEntity(BlockPos.ZERO);
            assertNotNull(chest);
            assertEquals(BlockPos.ZERO, chest.getBlockPos());
            scene.inDestinationWorld(() -> {
                assertSame(chest, scene.blockEntity(BlockPos.ZERO));
                assertSame(chest.getBlockState(), scene.blockState(BlockPos.ZERO));
                assertEquals(7, scene.brightness(LightLayer.BLOCK, BlockPos.ZERO));
            });
        }
    }

    @Test
    public void visualTicksReadCapturedDestinationFluidsAndRestoreScopeAfterFailure() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:water[level=0]"))));
        BlockState water = palette.state(3);
        assertSame(Blocks.WATER, water.getBlock());
        water.initCache();
        assertFalse(water.getFluidState().isEmpty());
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new BlockBox(0, 0, 0, 16, 16, 16), 1);
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, oneBlock(3), SectionBiomes.NONE));
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(store.view(7), level);
        OpticTransform transform = OpticTransform.of(AxisPermutation.of(Face.W, Face.U, Face.S), 101, 0, 0);
        Entity entity = mock(Entity.class);
        doAnswer(ignored -> {
            assertSame(scene, ClientMeshEntities.active(level));
            assertFalse(scene.blockState(new BlockPos(100, 0, 0)).getFluidState().isEmpty());
            assertTrue(scene.blockState(new BlockPos(200, 0, 0)).getFluidState().isEmpty());
            return null;
        }).when(entity).tick();
        scene.tickEntity(entity, transform);
        NativeClientClock.verifyTick(entity, times(1));
        verify(entity).tick();
        assertNull(ClientMeshEntities.active());
        verifyNoInteractions(level);
        doThrow(new IllegalStateException("failed species tick")).when(entity).tick();
        assertThrows(IllegalStateException.class, () -> scene.tickEntity(entity, transform));
        assertNull(ClientMeshEntities.active());
        assertFalse(scene.blockState(BlockPos.ZERO).getFluidState().isEmpty());
    }

    @Test
    public void sidewaysDoubleChestsUseNativePositionsNeighborsAndLightInsideScopedExtraction() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(
            new ViewStreamMessage.PaletteEntry(3, "minecraft:chest[facing=north,type=left,waterlogged=false]"),
            new ViewStreamMessage.PaletteEntry(4, "minecraft:chest[facing=north,type=right,waterlogged=false]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new BlockBox(0, 0, 0, 16, 16, 16), 1);
        BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos connected = ChestBlock.getConnectedBlockPos(BlockPos.ZERO, left);
        BlockPos display = new BlockPos(8, 8, 8);
        BlockPos displayPartner = display.offset(-connected.getY(), connected.getX(), connected.getZ());
        int firstCell = display.getY() << 8 | display.getZ() << 4 | display.getX();
        int secondCell = displayPartner.getY() << 8 | displayPartner.getZ() << 4 | displayPartner.getX();
        int[] cells = new int[4096];
        cells[firstCell] = 3;
        cells[secondCell] = 4;
        byte[] light = new byte[2048];
        light[firstCell >> 1] |= (byte) (3 << ((firstCell & 1) * 4));
        light[secondCell >> 1] |= (byte) (12 << ((secondCell & 1) * 4));
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, BrickCodec.pack(0, cells).withLight(light, new byte[2048]), SectionBiomes.NONE));
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(store.view(7), level);
        OpticTransform transform = OpticTransform.of(AxisPermutation.of(Face.U, Face.W, Face.S), 100, 50, 200);
        scene.synchronize(transform);
        BlockEntity chest = scene.blockEntity(display);
        BlockPos nativePosition = new BlockPos(-42, 91, -192);
        assertEquals(nativePosition, chest.getBlockPos());
        when(level.getBlockState(any())).thenAnswer(call -> scene.blockState(call.getArgument(0)));
        when(level.getBlockEntity(any())).thenAnswer(call -> scene.blockEntity(call.getArgument(0)));
        scene.inDestinationWorld(() -> {
            assertSame(scene, ClientMeshEntities.active(level));
            assertSame(chest, scene.blockEntity(nativePosition));
            assertEquals(left, scene.blockState(nativePosition));
            BlockPos neighbor = nativePosition.offset(connected);
            assertEquals(neighbor, scene.blockEntity(neighbor).getBlockPos());
            assertEquals(3, scene.brightness(LightLayer.BLOCK, nativePosition));
            assertEquals(12, scene.brightness(LightLayer.BLOCK, neighbor));
            assertEquals(0, scene.brightness(LightLayer.BLOCK, nativePosition.offset(1000, 0, 0)));
            DoubleBlockCombiner.Combiner<ChestBlockEntity, Boolean> combiner = mock(DoubleBlockCombiner.Combiner.class);
            ((ChestBlock) Blocks.CHEST).combine(left, level, nativePosition, true).apply(combiner);
            verify(combiner).acceptDouble(any(), any());
        });
        assertNull(ClientMeshEntities.active());
        assertSame(chest, scene.blockEntity(display));
        assertThrows(IllegalStateException.class, () -> scene.inDestinationWorld(() -> { throw new IllegalStateException("test"); }));
        assertNull(ClientMeshEntities.active());
        assertSame(chest, scene.blockEntity(display));
    }

    @Test
    public void detachedBlockEntitiesFollowSectionReplacementAndRemovalWithoutChunkWrites() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:chest[facing=east]"),
            new ViewStreamMessage.PaletteEntry(4, "minecraft:oak_sign[rotation=8]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new BlockBox(-16, -16, -16, 16, 16, 16), 1);
        store.put(new ViewStreamMessage.MeshSection(7, 1, -1, -1, -1, 1, 0, oneBlock(3), SectionBiomes.NONE));
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(store.view(7), level);
        BlockPos position = new BlockPos(-16, -16, -16);
        scene.synchronize(PortalEnvironmentTest.identity());
        BlockEntity chest = scene.blockEntity(position);
        assertNotNull(chest);
        assertSame(Blocks.CHEST, chest.getBlockState().getBlock());
        assertSame(level, chest.getLevel());
        scene.synchronize(PortalEnvironmentTest.identity());
        assertSame(chest, scene.blockEntity(position));
        store.put(new ViewStreamMessage.MeshSection(7, 1, -1, -1, -1, 2, 0, oneBlock(4), SectionBiomes.NONE));
        scene.synchronize(PortalEnvironmentTest.identity());
        assertNotSame(chest, scene.blockEntity(position));
        assertSame(Blocks.OAK_SIGN, scene.blockEntity(position).getBlockState().getBlock());
        assertSame(Blocks.AIR.defaultBlockState(), scene.blockState(position.offset(16, 0, 0)));
        assertNull(scene.blockEntity(position.offset(1, 0, 0)));
        store.drop(7, 1, -1, -1, -1);
        scene.synchronize(PortalEnvironmentTest.identity());
        assertNull(scene.blockEntity(position));
        verifyNoInteractions(level);
    }

    @Test
    public void palettesWithoutEntityBlocksSkipCellDiscoveryAndMissingNbtStillCreatesEntities() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:chest"),
            new ViewStreamMessage.PaletteEntry(4, "minecraft:stone"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new BlockBox(0, 0, 0, 32, 16, 16), 2);
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, oneBlock(4), SectionBiomes.NONE));
        store.put(new ViewStreamMessage.MeshSection(7, 1, 1, 0, 0, 1, 0, oneBlock(3), SectionBiomes.NONE));
        long chestKey = SectionPos.asLong(1, 0, 0);
        ClientMeshSections.Section stone = mock(ClientMeshSections.Section.class, delegatesTo(store.view(7).section(0L)));
        ClientMeshSections.Section chest = store.view(7).section(chestKey);
        assertFalse(stone.hasEntityBlocks());
        assertTrue(chest.hasEntityBlocks());
        assertNull(chest.blockEntity(0));
        ClientMeshSections.View view = mock(ClientMeshSections.View.class, delegatesTo(store.view(7)));
        when(view.section(0L)).thenReturn(stone);
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(view, level);
        scene.synchronize(PortalEnvironmentTest.identity());
        BlockPos position = new BlockPos(16, 0, 0);
        assertTrue(scene.blockEntity(position) instanceof ChestBlockEntity);
        verify(stone, never()).state(anyInt());
        store.put(new ViewStreamMessage.MeshSection(7, 1, 1, 0, 0, 2, 0, oneBlock(4), SectionBiomes.NONE));
        ClientMeshSections.Section replaced = mock(ClientMeshSections.Section.class, delegatesTo(store.view(7).section(chestKey)));
        when(view.section(chestKey)).thenReturn(replaced);
        scene.synchronize(PortalEnvironmentTest.identity());
        assertNull(scene.blockEntity(position));
        verify(replaced, never()).state(anyInt());
        verify(stone, never()).state(anyInt());
        verifyNoInteractions(level);
    }

    @Test
    public void poweredSkullAnimationAdvancesOncePerTickWithoutAWorldTicker() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:dragon_head[powered=true]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new BlockBox(0, 0, 0, 16, 16, 16), 1);
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, oneBlock(3), SectionBiomes.NONE));
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(store.view(7), level);
        scene.synchronize(PortalEnvironmentTest.identity());
        SkullBlockEntity skull = (SkullBlockEntity) scene.blockEntity(BlockPos.ZERO);
        scene.animate(1);
        assertEquals(1.0F, skull.getAnimation(0.0F), 0.0F);
        scene.animate(1);
        assertEquals(1.0F, skull.getAnimation(0.0F), 0.0F);
        scene.animate(2);
        assertEquals(2.0F, skull.getAnimation(0.0F), 0.0F);
        verifyNoInteractions(level);
    }

    @Test
    public void unchangedFramesDoNotEnumerateSectionsAndUpdatesKeepTheActiveSetCurrent() throws Exception {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:dragon_head[powered=true]"))));
        ClientMeshSections store = new ClientMeshSections(palette, 1_048_576);
        store.begin(7, 1, new BlockBox(0, 0, 0, 32, 16, 16), 2);
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, Brick.empty(0), SectionBiomes.NONE));
        store.put(new ViewStreamMessage.MeshSection(7, 1, 1, 0, 0, 1, 0, oneBlock(3), SectionBiomes.NONE));
        ClientMeshSections.View view = mock(ClientMeshSections.View.class, delegatesTo(store.view(7)));
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities scene = new ClientMeshEntities(view, level);
        BlockPos position = new BlockPos(16, 0, 0);
        scene.synchronize(PortalEnvironmentTest.identity());
        SkullBlockEntity original = (SkullBlockEntity) scene.blockEntity(position);
        scene.animate(1);
        for (int frame = 0; frame < 100; frame++) {
            scene.synchronize(PortalEnvironmentTest.identity());
        }
        verify(view, times(1)).sectionKeys();
        assertSame(original, scene.blockEntity(position));
        store.put(new ViewStreamMessage.MeshSection(7, 1, 1, 0, 0, 2, 0, oneBlock(3), SectionBiomes.NONE));
        scene.synchronize(PortalEnvironmentTest.identity());
        SkullBlockEntity replacement = (SkullBlockEntity) scene.blockEntity(position);
        assertNotSame(original, replacement);
        scene.animate(2);
        assertEquals(1.0F, original.getAnimation(0), 0);
        assertEquals(1.0F, replacement.getAnimation(0), 0);
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 2, 0, oneBlock(3), SectionBiomes.NONE));
        scene.synchronize(PortalEnvironmentTest.identity());
        SkullBlockEntity added = (SkullBlockEntity) scene.blockEntity(BlockPos.ZERO);
        assertSame(replacement, scene.blockEntity(position));
        scene.animate(3);
        assertEquals(1.0F, added.getAnimation(0), 0);
        assertEquals(2.0F, replacement.getAnimation(0), 0);
        store.drop(7, 1, 1, 0, 0);
        store.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 3, 0, Brick.empty(0), SectionBiomes.NONE));
        scene.synchronize(PortalEnvironmentTest.identity());
        assertNull(scene.blockEntity(position));
        assertNull(scene.blockEntity(BlockPos.ZERO));
        scene.animate(4);
        assertEquals(1.0F, added.getAnimation(0), 0);
        assertEquals(2.0F, replacement.getAnimation(0), 0);
        scene.synchronize(PortalEnvironmentTest.identity());
        verify(view, times(4)).sectionKeys();
        verifyNoInteractions(level);
    }

    @Test
    public void meshEntitiesSpawnWithoutVoxelSweepsAndLoseTheirHiddenOwnershipOnDrop() {
        ClientSceneWorld world = mock(ClientSceneWorld.class);
        when(world.spawn(anyInt(), any(), any())).thenReturn(true);
        ClientProjectedEntities entities = new ClientProjectedEntities(world);
        UUID id = UUID.randomUUID();
        EntitySnapshot stand = EntitySnapshot.full(id, "minecraft:armor_stand", -20.5D, 64.0D, -40.5D, 1.975D,
            0.0D, 0.0D, -1.0D, 180.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 1);
        entities.apply(new ViewStreamMessage.EntityFrame(7, 1, List.of(stand), List.of(id), true));
        ClientPortal portal = mock(ClientPortal.class);
        entities.tick(key -> portal, key -> true);
        int entityId = entities.entityId(7, id);
        assertTrue(ClientEntityIds.isProjected(entityId));
        assertTrue(entities.meshEntity(entityId));
        int[] visited = {0};
        entities.forEachEntity(7, value -> visited[0] = value);
        assertEquals(entityId, visited[0]);
        entities.drop(7);
        assertFalse(entities.meshEntity(entityId));
        assertEquals(0, entities.spawned());
    }

    private static void bindCameraPortal(ClientViewSession session, int key, int parent, EnvironmentState environment, Affine sample) {
        ApertureDescriptor geometry = new ApertureDescriptor(10, 64, -4, Face.N.ordinal(), true, 0, false, 2, 2,
            new long[] {15L}, ShapeDescriptor.FULL, 0, 0, 1, 16, 0, 0, 0, 0, 0, 0, 0, 0.0D, parent, key, List.of());
        when(session.portal(key)).thenReturn(new ClientPortal(key, geometry, 1, 0));
        when(session.environment(key)).thenReturn(environment);
        when(session.affine(key)).thenReturn(sample == null ? null : new ViewStreamMessage.PortalAffine(key, 1, Affine.IDENTITY,
            sample, ShapeDescriptor.FULL));
    }

    private static Camera rootCamera(Vec3 position) {
        Camera camera = mock(Camera.class);
        when(camera.position()).thenReturn(position);
        when(camera.forwardVector()).thenReturn(new Vector3f(0, 0, 1));
        when(camera.upVector()).thenReturn(new Vector3f(0, 1, 0));
        when(camera.isDetached()).thenReturn(true);
        when(camera.isInitialized()).thenReturn(true);
        return camera;
    }

    private static void assertVector(Vector3fc expected, Vector3fc actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-6F);
        assertEquals(expected.y(), actual.y(), 1.0E-6F);
        assertEquals(expected.z(), actual.z(), 1.0E-6F);
    }

    private static final class CameraNameRenderer extends EntityRenderer<Entity, EntityRenderState> {
        private CameraNameRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public EntityRenderState createRenderState() {
            return new EntityRenderState();
        }

        private EntityRenderState names(Entity entity) {
            EntityRenderState state = createRenderState();
            extractNameTags(entity, state, 0.5F);
            return state;
        }
    }

    private static Brick oneBlock(int paletteId) {
        long[] indices = new long[64];
        indices[0] = 1L;
        return new Brick(0, Brick.Encoding.PALETTED, 1, 0, 0, new int[] {0, paletteId}, indices, null, null, null);
    }
}
