package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.gravity.GravityHolder;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.junit.Assert.assertEquals;

public class ClientEntityGravityTest extends MinecraftTestBase {
    private static final UUID ID = new UUID(12, 34);
    private static final GravityFrame SIDEWAYS = new GravityFrame(AxisPermutation.of(Face.D, Face.E, Face.S));

    @Test
    public void authorityWaitsForSpawnAndRoutesToTheResidentLevel() {
        Level level = mock(Level.class);
        Entity entity = entity(level, ID);
        ClientEntityGravity gravity = new ClientEntityGravity();
        gravity.receive(level, null, false, new TravelMessage.EntityGravity(3, 42, ID, SIDEWAYS, false), 10L);
        verify((GravityHolder) entity, never()).wormholesGravity(any(GravityFrame.class));
        when(level.getEntity(42)).thenReturn(entity);
        gravity.tick(handle -> handle == 3 ? level : null, null, false, 11L);
        verify((GravityHolder) entity).wormholesGravity(SIDEWAYS);
    }

    @Test
    public void aReusedEntityIdCannotReceiveAnotherEntitysGravity() {
        Level level = mock(Level.class);
        Entity entity = entity(level, new UUID(99, 88));
        when(level.getEntity(42)).thenReturn(entity);
        ClientEntityGravity gravity = new ClientEntityGravity();
        gravity.receive(level, null, false, new TravelMessage.EntityGravity(0, 42, ID, SIDEWAYS, false), 10L);
        when(entity.getUUID()).thenReturn(ID);
        gravity.tick(handle -> level, null, false, 11L);
        verify((GravityHolder) entity, never()).wormholesGravity(any(GravityFrame.class));
    }

    @Test
    public void selfAuthorityWaitsUntilPredictedCrossingsSettle() {
        Level level = mock(Level.class);
        Entity player = entity(level, ID);
        when(level.getEntity(42)).thenReturn(player);
        ClientEntityGravity gravity = new ClientEntityGravity();
        gravity.receive(level, player, true, new TravelMessage.EntityGravity(0, 42, ID, SIDEWAYS, false), 10L);
        gravity.tick(handle -> level, player, true, 11L);
        verify((GravityHolder) player, never()).wormholesGravity(any(GravityFrame.class));
        gravity.tick(handle -> level, player, false, 12L);
        verify((GravityHolder) player).wormholesGravity(SIDEWAYS);
    }

    @Test
    public void expiredAndClearedSpawnUpdatesAreDiscarded() {
        Level level = mock(Level.class);
        Entity entity = entity(level, ID);
        ClientEntityGravity gravity = new ClientEntityGravity();
        gravity.receive(level, null, false, new TravelMessage.EntityGravity(0, 42, ID, SIDEWAYS, false), 10L);
        when(level.getEntity(42)).thenReturn(entity);
        gravity.tick(handle -> level, null, false, 10_010L);
        verify((GravityHolder) entity, never()).wormholesGravity(any(GravityFrame.class));
        when(level.getEntity(42)).thenReturn(null);
        gravity.receive(level, null, false, new TravelMessage.EntityGravity(0, 42, ID, SIDEWAYS, false), 20L);
        gravity.clear();
        when(level.getEntity(42)).thenReturn(entity);
        gravity.tick(handle -> level, null, false, 21L);
        verify((GravityHolder) entity, never()).wormholesGravity(any(GravityFrame.class));
    }

    @Test
    public void acceptedSelfAuthorityDiscardsOlderUpdatesAcrossResidentLevels() {
        Level level = mock(Level.class);
        Entity player = entity(level, ID);
        UUID observerId = new UUID(56, 78);
        Entity observer = entity(level, observerId);
        when(level.getEntity(42)).thenReturn(player);
        when(level.getEntity(43)).thenReturn(observer);
        ClientEntityGravity gravity = new ClientEntityGravity();
        gravity.receive(level, player, true, new TravelMessage.EntityGravity(0, 42, ID, SIDEWAYS, false), 10L);
        gravity.receive(level, player, true, new TravelMessage.EntityGravity(3, 42, ID, SIDEWAYS, false), 11L);
        gravity.receive(null, player, false, new TravelMessage.EntityGravity(3, 43, observerId, SIDEWAYS, false), 12L);
        gravity.discard(ID);
        gravity.tick(handle -> level, player, false, 13L);
        verify((GravityHolder) player, never()).wormholesGravity(any(GravityFrame.class));
        verify((GravityHolder) observer).wormholesGravity(SIDEWAYS);
    }

    @Test
    public void observerAuthorityWaitsUntilItsEntityCrossingReachesThePortalPlane() {
        Level level = mock(Level.class);
        Entity entity = entity(level, ID);
        when(level.getEntity(42)).thenReturn(entity);
        ClientEntityCrossing crossing = mock(ClientEntityCrossing.class);
        when(((CrossingEntity) entity).wormholes$crossing()).thenReturn(crossing);
        ClientEntityGravity gravity = new ClientEntityGravity();
        gravity.receive(level, null, false, new TravelMessage.EntityGravity(0, 42, ID, SIDEWAYS, false), 10L);
        gravity.tick(handle -> level, null, false, 11L);
        verify((GravityHolder) entity, never()).wormholesGravity(any(GravityFrame.class));
        when(crossing.crossed()).thenReturn(true);
        gravity.tick(handle -> level, null, false, 12L);
        verify((GravityHolder) entity).wormholesGravity(SIDEWAYS);
    }

    @Test
    public void worldOrientationResetMapsTheCurrentViewOnceBeforeRestoringItsBasis() {
        Level level = mock(Level.class);
        Entity player = entity(level, ID);
        ((GravityHolder) player).wormholesGravity(SIDEWAYS);
        when(player.getYRot()).thenReturn(30.0F);
        when(player.getXRot()).thenReturn(-15.0F);
        when(level.getEntity(42)).thenReturn(player);
        Vec3 expected = MinecraftGravity.world(SIDEWAYS, lookVector(30.0F, -15.0F));
        ClientEntityGravity gravity = new ClientEntityGravity();
        TravelMessage.EntityGravity reset = new TravelMessage.EntityGravity(0, 42, ID, GravityFrame.WORLD, true);
        gravity.receive(level, player, false, reset, 10L);
        gravity.receive(level, player, false, reset, 11L);
        ArgumentCaptor<Float> yaw = ArgumentCaptor.forClass(Float.class);
        ArgumentCaptor<Float> pitch = ArgumentCaptor.forClass(Float.class);
        verify(player).setYRot(yaw.capture());
        verify(player).setXRot(pitch.capture());
        Vec3 actual = lookVector(yaw.getValue(), pitch.getValue());
        assertEquals(expected.x, actual.x, 1.0E-4D);
        assertEquals(expected.y, actual.y, 1.0E-4D);
        assertEquals(expected.z, actual.z, 1.0E-4D);
        verify((GravityHolder) player).wormholesGravity(GravityFrame.WORLD);
    }

    private static Entity entity(Level level, UUID uuid) {
        Entity entity = mock(Entity.class, withSettings().extraInterfaces(GravityHolder.class, CrossingEntity.class));
        AtomicReference<GravityFrame> frame = new AtomicReference<>(GravityFrame.WORLD);
        when(((GravityHolder) entity).wormholesGravity()).thenAnswer(call -> frame.get());
        doAnswer(call -> {
            frame.set(call.getArgument(0, GravityFrame.class));
            return null;
        }).when((GravityHolder) entity).wormholesGravity(any(GravityFrame.class));
        when(entity.level()).thenReturn(level);
        when(entity.getUUID()).thenReturn(uuid);
        when(entity.position()).thenReturn(Vec3.ZERO);
        when(entity.getPose()).thenReturn(Pose.STANDING);
        when(entity.getDimensions(Pose.STANDING)).thenReturn(EntityDimensions.scalable(0.6F, 1.8F));
        return entity;
    }
}
