package art.arcane.wormholes.modded.client;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoveSimulationType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientEntityCrossingsTest extends MinecraftTestBase {
    private static final OpticTransform UP = OpticTransform.translation(0.0D, 20.0D, 0.0D);
    private static final TravelMessage.EntityCrossed CROSSED = new TravelMessage.EntityCrossed(0, 42, UP, new Vec3d(1.5D, 80.5D, 1.5D), Face.U,
        new Vec3d(0.0D, -3.5D, 0.0D));

    @Test
    public void aTranslatedCrossingCarriesPositionHistoryInterpolationAndVelocityThroughThePortal() {
        Faller faller = new Faller(80.5D);
        when(faller.interpolation.target()).thenReturn(PositionAndRotation.of(new Vec3(1.5D, 79.0D, 1.5D), 0.0F, 0.0F),
            PositionAndRotation.of(new Vec3(1.5D, 99.0D, 1.5D), 0.0F, 0.0F));
        ClientEntityCrossings.cross(faller.entity, UP, new Vec3d(0.0D, -3.5D, 0.0D));
        verify(faller.interpolation).applyPredictedMovement(new Vec3(0.0D, 20.0D, 0.0D));
        verify(faller.interpolation, never()).cancel();
        verify(faller.entity).setPos(new Vec3(1.5D, 100.5D, 1.5D));
        verify(faller.entity).setOldPosAndRot(new Vec3(1.5D, 104.0D, 1.5D), 90.0F, 10.0F);
        verify(faller.entity).setDeltaMovement(new Vec3(0.0D, -3.5D, 0.0D));
    }

    @Test
    public void anInterpolationTargetThatCannotFollowIsDroppedInsteadOfPullingTheEntityBack() {
        Faller faller = new Faller(80.5D);
        PositionAndRotation stuck = PositionAndRotation.of(new Vec3(1.5D, 79.0D, 1.5D), 0.0F, 0.0F);
        when(faller.interpolation.target()).thenReturn(stuck, stuck);
        ClientEntityCrossings.cross(faller.entity, UP, new Vec3d(0.0D, -3.5D, 0.0D));
        verify(faller.interpolation).cancel();
        verify(faller.entity).setPos(new Vec3(1.5D, 100.5D, 1.5D));
    }

    @Test
    public void aTurningCrossingRotatesTheEntityAndRestartsItsInterpolation() {
        Faller faller = new Faller(80.5D);
        OpticTransform turn = OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 10.0D, 0.0D, 0.0D);
        ClientEntityCrossings.cross(faller.entity, turn, new Vec3d(1.0D, 0.0D, 0.0D));
        verify(faller.interpolation, never()).applyPredictedMovement(any());
        verify(faller.interpolation).cancel();
        Angles.Look look = turn.look(new Angles.Look(90.0F, 10.0F));
        verify(faller.entity).setYRot(look.yaw());
        verify(faller.entity).setXRot(look.pitch());
        verify(faller.entity).setYHeadRot(turn.yaw(80.0F));
        Vec3d mapped = turn.point(new Vec3d(1.5D, 80.5D, 1.5D));
        verify(faller.entity).setPos(new Vec3(mapped.x(), mapped.y(), mapped.z()));
    }

    @Test
    public void anEntityStillInterpolatingTowardThePortalCrossesWhenItsShownPositionReachesThePlane() {
        Faller faller = new Faller(88.0D);
        ClientEntityCrossings crossings = new ClientEntityCrossings();
        assertTrue(crossings.receive(faller.level, null, CROSSED));
        verify(faller.entity, never()).setPos(any(Vec3.class));
        assertNotNull(faller.crossing.get());
        crossings.tick();
        verify(faller.entity, never()).setPos(any(Vec3.class));
        when(faller.entity.position()).thenReturn(new Vec3(1.5D, 80.25D, 1.5D));
        crossings.tick();
        verify(faller.entity).setPos(new Vec3(1.5D, 100.25D, 1.5D));
        assertNull(faller.crossing.get());
    }

    @Test
    public void positionsSentAfterTheCrossingAreFollowedOnTheSourceSideUntilThePlaneIsReached() {
        Faller faller = new Faller(88.0D);
        ClientEntityCrossing crossing = new ClientEntityCrossing(CROSSED, false);
        crossing.route(faller.entity, PositionPath.stepped(List.of(new PositionStep(new Vec3(1.5D, 97.0D, 1.5D), 1),
            new PositionStep(new Vec3(1.5D, 93.0D, 1.5D), 2))), 90.0F, 10.0F, true);
        verify(faller.entity).moveOrInterpolateTo(PositionPath.stepped(List.of(new PositionStep(new Vec3(1.5D, 77.0D, 1.5D), 1),
            new PositionStep(new Vec3(1.5D, 73.0D, 1.5D), 2))), 90.0F, 10.0F, true);
        crossing.route(faller.entity, PositionPath.of(new Vec3(1.5D, 96.0D, 1.5D)), 0.0F, 0.0F, false);
        verify(faller.entity).moveOrInterpolateTo(PositionPath.of(new Vec3(1.5D, 76.0D, 1.5D)), 0.0F, 0.0F, false);
    }

    @Test
    public void aCrossingThatNeverReachesThePlaneResolvesAfterHalfASecond() {
        Faller faller = new Faller(88.0D);
        ClientEntityCrossings crossings = new ClientEntityCrossings();
        crossings.receive(faller.level, null, CROSSED);
        for (int tick = 0; tick < 10; tick++) {
            crossings.tick();
        }
        verify(faller.entity, never()).setPos(any(Vec3.class));
        crossings.tick();
        verify(faller.entity).setPos(new Vec3(1.5D, 108.0D, 1.5D));
    }

    @Test
    public void entitiesAlreadyPastThePlaneCrossImmediately() {
        Faller faller = new Faller(80.25D);
        assertTrue(new ClientEntityCrossings().receive(faller.level, null, CROSSED));
        verify(faller.entity).setPos(new Vec3(1.5D, 100.25D, 1.5D));
        assertNull(faller.crossing.get());
    }

    @Test
    public void theServerTeleportEchoOfAClientSimulatedEntityIsAbsorbedAfterTheCrossing() {
        Faller faller = new Faller(80.25D);
        doReturn(EntityTypes.ITEM).when(faller.entity).getType();
        when(faller.entity.getMoveSimulationType()).thenReturn(MoveSimulationType.SERVER_AND_CLIENT);
        ClientEntityCrossings crossings = new ClientEntityCrossings();
        assertTrue(crossings.receive(faller.level, null, CROSSED));
        verify(faller.entity).setPos(new Vec3(1.5D, 100.25D, 1.5D));
        ClientEntityCrossing crossing = faller.crossing.get();
        assertNotNull(crossing);
        assertTrue(crossing.crossed());
        assertTrue(crossing.absorb());
        assertFalse(crossing.absorb());
        crossings.tick();
        assertNull(faller.crossing.get());
    }

    @Test
    public void crossingsForTheLocalPlayerOrUnknownEntitiesAreIgnored() {
        Faller faller = new Faller(80.5D);
        ClientEntityCrossings crossings = new ClientEntityCrossings();
        assertFalse(crossings.receive(faller.level, faller.entity, CROSSED));
        assertFalse(crossings.receive(faller.level, null, new TravelMessage.EntityCrossed(0, 7, UP, new Vec3d(1.5D, 80.5D, 1.5D), Face.U,
            new Vec3d(0.0D, -3.5D, 0.0D))));
        assertFalse(crossings.receive(null, null, CROSSED));
        verify(faller.entity, never()).setPos(any(Vec3.class));
    }

    private static final class Faller {
        final Entity entity = mock(Entity.class, withSettings().extraInterfaces(CrossingEntity.class));
        final InterpolationHandler interpolation = mock(InterpolationHandler.class);
        final ClientLevel level = mock(ClientLevel.class);
        final AtomicReference<ClientEntityCrossing> crossing = new AtomicReference<>();

        Faller(double y) {
            when(entity.getInterpolation()).thenReturn(interpolation);
            doReturn(EntityTypes.PIG).when(entity).getType();
            when(entity.position()).thenReturn(new Vec3(1.5D, y, 1.5D));
            when(entity.oldPosition()).thenReturn(new Vec3(1.5D, y + 3.5D, 1.5D));
            when(entity.getYRot()).thenReturn(90.0F);
            when(entity.getXRot()).thenReturn(10.0F);
            when(entity.getYHeadRot()).thenReturn(80.0F);
            entity.yRotO = 90.0F;
            entity.xRotO = 10.0F;
            when(level.getEntity(42)).thenReturn(entity);
            CrossingEntity holder = (CrossingEntity) entity;
            when(holder.wormholes$crossing()).thenAnswer(invocation -> crossing.get());
            doAnswer(invocation -> {
                crossing.set(invocation.getArgument(0));
                return null;
            }).when(holder).wormholes$crossing(any());
        }
    }
}
