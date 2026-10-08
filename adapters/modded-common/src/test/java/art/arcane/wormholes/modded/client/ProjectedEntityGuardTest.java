package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.render.ProjectedEntityIdentity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProjectedEntityGuardTest extends MinecraftTestBase {
    private static final int[] COPY_IDS = {ClientEntityIds.REFLECTION_MIN, ClientEntityIds.REFLECTION_MAX, ClientEntityIds.PROJECTED_MIN,
        ClientEntityIds.PROJECTED_MAX, ProjectedEntityIdentity.MIN_ENTITY_ID, ProjectedEntityIdentity.MAX_ENTITY_ID};
    private static final int[] REAL_IDS = {1, 417, -1, ProjectedEntityIdentity.MAX_ENTITY_ID + 1};

    @Test
    public void spawnReusingTheLocalPlayerIdIsRefused() {
        assertTrue(ProjectedEntityGuard.refusesSpawn(417, 417));
        assertTrue(ProjectedEntityGuard.refusesSpawn(ProjectedEntityIdentity.MAX_ENTITY_ID, ProjectedEntityIdentity.MAX_ENTITY_ID));
    }

    @Test
    public void spawnWithAnotherIdIsKept() {
        assertFalse(ProjectedEntityGuard.refusesSpawn(418, 417));
        assertFalse(ProjectedEntityGuard.refusesSpawn(ProjectedEntityIdentity.MIN_ENTITY_ID, 417));
    }

    @Test
    public void pickAndCollisionSelectorsSkipCopiesFromEveryRange() {
        Predicate<Entity> selector = ProjectedEntityGuard.excluding(entity -> true);
        for (int id : COPY_IDS) {
            assertFalse("copy " + id, selector.test(entity(id, new AABB(0, 0, 0, 1, 1, 1))));
        }
        for (int id : REAL_IDS) {
            assertTrue("real " + id, selector.test(entity(id, new AABB(0, 0, 0, 1, 1, 1))));
        }
    }

    @Test
    public void localPlayerAndVehicleQueriesSkipCopiesWhileOtherQueriesKeepTheirSelector() {
        Entity local = entity(5, new AABB(0, 0, 0, 1, 2, 1));
        when(local.isLocalInstanceAuthoritative()).thenReturn(true);
        Predicate<? super Entity> query = ProjectedEntityGuard.localTargets(local, entity -> true);
        for (int id : COPY_IDS) {
            assertFalse("copy " + id, query.test(entity(id, new AABB(0, 0, 0, 1, 1, 1))));
        }
        for (int id : REAL_IDS) {
            assertTrue("real " + id, query.test(entity(id, new AABB(0, 0, 0, 1, 1, 1))));
        }
        Predicate<Entity> selector = entity -> true;
        assertSame(selector, ProjectedEntityGuard.localTargets(entity(6, new AABB(0, 0, 0, 1, 2, 1)), selector));
        assertSame(selector, ProjectedEntityGuard.localTargets(null, selector));
    }

    @Test
    public void solidCopiesGiveTheLocalPlayerAndItsVehicleNoCollisionShapes() {
        Entity player = entity(5, new AABB(-0.3, 0, -0.3, 0.3, 1.8, 0.3));
        Entity vehicle = entity(6, new AABB(-0.7, 0, -0.7, 0.7, 0.6, 0.7));
        Entity real = entity(20, new AABB(-0.7, 0, 2.5, 0.7, 0.6, 3.9));
        List<Entity> candidates = List.of(entity(ClientEntityIds.PROJECTED_MAX, new AABB(-0.7, 0, 0.8, 0.7, 0.6, 2.2)),
            entity(ClientEntityIds.REFLECTION_MIN, new AABB(-0.5, 0, 0.8, 0.5, 1, 1.8)),
            entity(ProjectedEntityIdentity.MAX_ENTITY_ID, new AABB(-0.5, 0, 0.8, 0.5, 1, 1.8)), real);
        for (Entity source : List.of(player, vehicle)) {
            when(source.isLocalInstanceAuthoritative()).thenReturn(true);
            when(source.canCollideWith(any())).thenReturn(true);
            ClientLevel level = mock(ClientLevel.class);
            when(level.getEntities(eq(source), any(AABB.class), any())).thenAnswer(call -> {
                Predicate<? super Entity> filter = ProjectedEntityGuard.localTargets(source, call.getArgument(2));
                ArrayList<Entity> matches = new ArrayList<>();
                for (Entity candidate : candidates) {
                    if (filter.test(candidate)) {
                        matches.add(candidate);
                    }
                }
                return matches;
            });
            doCallRealMethod().when(level).getEntityCollisions(eq(source), any(AABB.class));
            List<VoxelShape> shapes = level.getEntityCollisions(source, source.getBoundingBox().expandTowards(0, 0, 4));
            assertEquals(1, shapes.size());
            assertEquals(real.getBoundingBox(), shapes.getFirst().bounds());
        }
    }

    private static Entity entity(int id, AABB bounds) {
        Entity entity = mock(Entity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.getBoundingBox()).thenReturn(bounds);
        when(entity.isPickable()).thenReturn(true);
        return entity;
    }
}
