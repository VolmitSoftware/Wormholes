package art.arcane.wormholes.modded.client;

import net.minecraft.SharedConstants;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.Direction;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class ClientMeshInteractionTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void unchangedWorldEntityQueriesReuseTheOriginalPredicate() {
        WormholesClient client = mock(WormholesClient.class);
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
