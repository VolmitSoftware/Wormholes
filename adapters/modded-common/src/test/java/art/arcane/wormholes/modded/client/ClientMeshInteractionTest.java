package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.entity.EntitySnapshot;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.Direction;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class ClientMeshInteractionTest extends MinecraftTestBase {
    @Test
    public void loadedNativeCopiesNeverReachWorldTickHandlersWhileRealEntitiesTickNormally() {
        Entity nativeCopy = entity(-100, new AABB(0, 0, 0, 1, 2, 1));
        Entity real = entity(100, new AABB(2, 0, 0, 3, 2, 1));
        ClientLevel level = mock(ClientLevel.class);
        when(level.isClientSide()).thenReturn(true);
        when(level.getEntity(-100)).thenReturn(nativeCopy);
        when(nativeCopy.level()).thenReturn(level);
        when(nativeCopy.getInterpolation()).thenReturn(InterpolationHandler.NO_OP);
        doCallRealMethod().when(nativeCopy).commonTick();
        ClientLevelScene scene = new ClientLevelScene(level, () -> null);
        EntityTickList list = new EntityTickList();
        list.add(nativeCopy);
        list.add(real);
        WormholesClient client = mock(WormholesClient.class);
        when(client.localMeshes()).thenReturn(new ClientLocalMeshSources(ignored -> {}));
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientProjectedEntities projected = mock(ClientProjectedEntities.class);
        ClientReflectionEntity reflections = mock(ClientReflectionEntity.class);
        when(client.tickState()).thenReturn(tick);
        when(tick.entities()).thenReturn(projected);
        when(client.reflections()).thenReturn(reflections);
        when(projected.hasMeshEntities()).thenReturn(true);
        when(projected.meshEntity(-100)).thenReturn(true);
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            for (int index = 0; index < 20; index++) {
                list.forEach(ClientMeshEntities.worldEntityTick(entity -> {
                    entity.tickCount++;
                    entity.tick();
                }));
                scene.tick(-100, 7, true);
            }
            assertEquals(20, real.tickCount);
            assertEquals(20, nativeCopy.tickCount);
            verify(nativeCopy, never()).tick();
            verify(real, times(20)).tick();
        }
    }

    @Test
    public void unchangedWorldEntityQueriesReuseTheOriginalPredicate() {
        WormholesClient client = mock(WormholesClient.class);
        when(client.localMeshes()).thenReturn(new ClientLocalMeshSources(ignored -> {}));
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientProjectedEntities projected = mock(ClientProjectedEntities.class);
        when(client.tickState()).thenReturn(tick);
        when(tick.entities()).thenReturn(projected);
        when(client.reflections()).thenReturn(new ClientReflectionEntity());
        Predicate<Entity> selector = entity -> true;
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            assertSame(selector, ClientMeshEntities.worldEntityPredicate(null, selector));
        }
    }

    @Test
    public void mirrorAndProjectedHitboxesDoNotReplaceWorldClickTargets() throws ReflectiveOperationException {
        WormholesClient client = mock(WormholesClient.class);
        when(client.localMeshes()).thenReturn(new ClientLocalMeshSources(ignored -> {}));
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientProjectedEntities projected = mock(ClientProjectedEntities.class);
        ClientReflectionEntity reflections = new ClientReflectionEntity();
        Field meshField = ClientReflectionEntity.class.getDeclaredField("meshIds");
        meshField.setAccessible(true);
        IntOpenHashSet reflectedMeshIds = (IntOpenHashSet) meshField.get(reflections);
        when(client.tickState()).thenReturn(tick);
        when(tick.entities()).thenReturn(projected);
        when(client.reflections()).thenReturn(reflections);
        Entity reflection = entity(-100, new AABB(-0.3, 0, -0.3, 0.3, 2, 0.3));
        Entity projection = entity(-101, new AABB(-0.3, 0, 0.5, 0.3, 2, 1));
        Entity real = entity(20, new AABB(-0.3, 0, 2, 0.3, 2, 2.5));
        reflectedMeshIds.add(-100);
        when(projected.meshEntity(-101)).thenReturn(true);
        when(projected.hasMeshEntities()).thenReturn(true);
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            assertSame(reflection, pick(List.of(reflection), EntitySelector.CAN_BE_PICKED).getEntity());
            assertSame(projection, pick(List.of(projection, real), EntitySelector.CAN_BE_PICKED).getEntity());
            assertSame(real, pick(List.of(reflection, projection, real), ClientMeshEntities::interactionTarget).getEntity());
            assertNull(pick(List.of(reflection, projection), ClientMeshEntities::interactionTarget));
            reflectedMeshIds.remove(-100);
            assertSame(reflection, pick(List.of(reflection), ClientMeshEntities::interactionTarget).getEntity());
            assertTrue(ClientMeshEntities.interactionTarget(real));
            when(real.isPickable()).thenReturn(false);
            assertFalse(ClientMeshEntities.interactionTarget(real));
        }
    }

    @Test
    public void nativeCopiesCannotContributeCollisionShapesOrPushTheWorld() throws ReflectiveOperationException {
        WormholesClient client = mock(WormholesClient.class);
        when(client.localMeshes()).thenReturn(new ClientLocalMeshSources(ignored -> {}));
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientProjectedEntities projected = mock(ClientProjectedEntities.class);
        ClientReflectionEntity reflections = new ClientReflectionEntity();
        Field meshField = ClientReflectionEntity.class.getDeclaredField("meshIds");
        meshField.setAccessible(true);
        ((IntOpenHashSet) meshField.get(reflections)).add(-100);
        when(client.tickState()).thenReturn(tick);
        when(tick.entities()).thenReturn(projected);
        when(client.reflections()).thenReturn(reflections);
        Entity reflection = entity(-100, new AABB(-0.3, 0, -0.3, 0.3, 2, 0.3));
        Entity projection = entity(-101, new AABB(-0.3, 0, 0.5, 0.3, 2, 1));
        Entity real = entity(20, new AABB(-0.3, 0, 2, 0.3, 2, 2.5));
        Entity source = entity(21, new AABB(-0.3, 0, -0.3, 0.3, 2, 0.3));
        when(projected.meshEntity(-101)).thenReturn(true);
        when(projected.hasMeshEntities()).thenReturn(true);
        ClientLevel level = mock(ClientLevel.class);
        List<Entity> candidates = List.of(reflection, projection, real);
        when(level.getEntities(eq(source), any(AABB.class), any())).thenAnswer(call -> {
            Predicate<? super Entity> filter = ClientMeshEntities.worldEntityPredicate(source, call.getArgument(2));
            ArrayList<Entity> matches = new ArrayList<>();
            for (Entity candidate : candidates) {
                if (filter.test(candidate)) {
                    matches.add(candidate);
                }
            }
            return matches;
        });
        when(source.canCollideWith(any())).thenReturn(true);
        doCallRealMethod().when(level).getEntityCollisions(eq(source), any(AABB.class));
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            List<VoxelShape> shapes = level.getEntityCollisions(source, source.getBoundingBox().expandTowards(0, 0, 4));
            assertEquals(1, shapes.size());
            assertEquals(real.getBoundingBox(), shapes.getFirst().bounds());
            assertEquals(1.7, Shapes.collide(Direction.Axis.Z, source.getBoundingBox(), shapes, 4), 1.0E-9);
            Predicate<Entity> pushable = Entity::isPushable;
            when(source.isPushable()).thenReturn(true);
            assertFalse(ClientMeshEntities.worldEntityPredicate(projection, pushable).test(source));
            assertFalse(ClientMeshEntities.worldEntityPredicate(reflection, pushable).test(source));
            assertTrue(ClientMeshEntities.worldEntityPredicate(real, pushable).test(source));
            assertFalse(ClientMeshEntities.worldEntityPredicate(real, entity -> false).test(source));
            when(projected.meshEntity(-101)).thenReturn(false);
            assertTrue(ClientMeshEntities.worldEntityPredicate(source, entity -> true).test(projection));
        }
    }

    @Test
    public void absentClientKeepsVanillaPicking() {
        Entity real = entity(20, new AABB(-0.3, 0, 2, 0.3, 2, 2.5));
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(null);
            assertSame(real, pick(List.of(real), ClientMeshEntities::interactionTarget).getEntity());
        }
    }

    @Test
    public void twoNativeScenesStayNonphysicalThroughoutSpawnUpdateAndRemoval() {
        ClientSceneWorld world = mock(ClientSceneWorld.class);
        ClientProjectedEntities projected = new ClientProjectedEntities(world);
        WormholesClient client = mock(WormholesClient.class);
        when(client.localMeshes()).thenReturn(new ClientLocalMeshSources(ignored -> {}));
        ClientViewTick tick = mock(ClientViewTick.class);
        when(client.tickState()).thenReturn(tick);
        when(tick.entities()).thenReturn(projected);
        when(client.reflections()).thenReturn(new ClientReflectionEntity());
        ClientLevel level = mock(ClientLevel.class);
        Map<Integer, Entity> copies = new HashMap<>();
        Entity source = entity(21, new AABB(-0.3, 0, -0.3, 0.3, 2, 0.3));
        Entity real = entity(20, new AABB(-0.3, 0, 2, 0.3, 2, 2.5));
        when(source.canCollideWith(any())).thenReturn(true);
        when(level.getEntities(eq(source), any(AABB.class), any())).thenAnswer(call -> {
            Predicate<? super Entity> selector = ClientMeshEntities.worldEntityPredicate(source, call.getArgument(2));
            ArrayList<Entity> matches = new ArrayList<>();
            for (Entity candidate : copies.values()) {
                if (selector.test(candidate)) {
                    matches.add(candidate);
                }
            }
            if (selector.test(real)) {
                matches.add(real);
            }
            return matches;
        });
        doCallRealMethod().when(level).getEntityCollisions(eq(source), any(AABB.class));
        Runnable check = () -> assertOnlyRealCollision(level, source, real);
        when(world.spawn(anyInt(), any(), any())).thenAnswer(call -> {
            int id = call.getArgument(0);
            copies.put(id, entity(id, new AABB(-0.3, 0, 0.5, 0.3, 2, 1)));
            check.run();
            return true;
        });
        doAnswer(call -> {
            check.run();
            return null;
        }).when(world).move(anyInt(), any(), any());
        doAnswer(call -> {
            check.run();
            return null;
        }).when(world).metadata(anyInt(), any());
        doAnswer(call -> {
            check.run();
            return null;
        }).when(world).tick(anyInt(), anyInt(), eq(true));
        doAnswer(call -> {
            check.run();
            copies.remove(call.<Integer>getArgument(0));
            return null;
        }).when(world).remove(anyInt(), any());
        UUID visualId = UUID.randomUUID();
        ClientPortal portal = new ClientPortal(1, ClientViewHarness.geometry(), 1, 0);
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            for (int revision = 1; revision <= 40; revision++) {
                EntitySnapshot visual = EntitySnapshot.full(visualId, "minecraft:pig", revision, 0, 0.5, 1, 0, 0,
                    1, 0, 0, 0, 0, 0, true, "", "", "", null, null, new byte[] {(byte) revision}, EntitySnapshot.EMPTY, revision);
                for (int key = 1; key <= 2; key++) {
                    projected.apply(new ClientViewMessage.EntityFrame(key, revision, List.of(visual), List.of(visualId), true));
                }
                projected.tick(key -> portal, key -> true);
                assertEquals(2, copies.size());
                assertTrue(projected.hasMeshEntities());
                projected.drop(1);
                assertEquals(1, copies.size());
            }
            projected.clear();
            assertTrue(copies.isEmpty());
            assertFalse(projected.hasMeshEntities());
            check.run();
        }
    }

    private static void assertOnlyRealCollision(ClientLevel level, Entity source, Entity real) {
        List<VoxelShape> shapes = level.getEntityCollisions(source, source.getBoundingBox().expandTowards(0, 0, 4));
        assertEquals(1, shapes.size());
        assertEquals(real.getBoundingBox(), shapes.getFirst().bounds());
        assertEquals(1.7, Shapes.collide(Direction.Axis.Z, source.getBoundingBox(), shapes, 4), 1.0E-9);
    }

    private static Entity entity(int id, AABB bounds) {
        Entity entity = mock(Entity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.isPickable()).thenReturn(true);
        when(entity.canBePickedFromInside()).thenReturn(true);
        when(entity.getBoundingBox()).thenReturn(bounds);
        when(entity.getRootVehicle()).thenReturn(entity);
        return entity;
    }

    private static EntityHitResult pick(List<Entity> candidates, Predicate<Entity> predicate) {
        ClientLevel level = mock(ClientLevel.class);
        Entity camera = mock(Entity.class);
        when(camera.level()).thenReturn(level);
        when(camera.getRootVehicle()).thenReturn(camera);
        when(level.getEntities(eq(camera), any(AABB.class), any())).thenAnswer(call -> {
            Predicate<Entity> filter = call.getArgument(2);
            ArrayList<Entity> matches = new ArrayList<>();
            for (Entity candidate : candidates) {
                if (filter.test(candidate)) {
                    matches.add(candidate);
                }
            }
            return matches;
        });
        return ProjectileUtil.getEntityHitResult(camera, new Vec3(0, 1.6, 0), new Vec3(0, 1.6, 4),
            new AABB(-1, 0, -1, 1, 2, 4), predicate, 16);
    }
}
