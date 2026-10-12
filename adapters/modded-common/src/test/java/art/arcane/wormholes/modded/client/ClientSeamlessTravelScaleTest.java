package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftScaleAccess;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static net.minecraft.world.entity.Pose.STANDING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class ClientSeamlessTravelScaleTest extends MinecraftTestBase {
    private static final UUID SCALED_TOKEN = new UUID(9, 9);
    private static final double EYE = 1.62D;

    @Test
    public void aRatioCrossingPredictsTheGrownFactorAndACancelRestoresIt() throws ReflectiveOperationException {
        try (ClientSeamlessTravelTest.Crossing crossing = new ClientSeamlessTravelTest.Crossing(false)) {
            AttributeInstance scale = new AttributeInstance(Attributes.SCALE, ignored -> { });
            when(crossing.player.getAttribute(Attributes.SCALE)).thenReturn(scale);
            TravelMessage.TravelBegin scaled = scaledBegin(crossing.begin, ScaleRule.ratio(0.25D, 4.0D), 3.0D);
            crossing.travel.receive(new TravelMessage.TravelCancel(crossing.begin.token(), crossing.begin.generation()));
            assertTrue(crossing.travel.receive(scaled));

            walkThrough(crossing, scaled.sourceGeometry());

            assertTrue(crossing.travel.pending());
            AttributeModifier grown = scale.getModifier(MinecraftScaleAccess.ID);
            assertEquals(2.0D, grown.amount(), 1.0E-6D);
            assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, grown.operation());
            assertTrue(crossing.scope.sent.stream().anyMatch(TravelMessage.TravelCross.class::isInstance));

            crossing.travel.receive(new TravelMessage.TravelCancel(scaled.token(), scaled.generation()));

            assertNull(scale.getModifier(MinecraftScaleAccess.ID));
        }
    }

    @Test
    public void aMotionCrossingLeavesTheSizeAlone() throws ReflectiveOperationException {
        try (ClientSeamlessTravelTest.Crossing crossing = new ClientSeamlessTravelTest.Crossing(false)) {
            AttributeInstance scale = new AttributeInstance(Attributes.SCALE, ignored -> { });
            when(crossing.player.getAttribute(Attributes.SCALE)).thenReturn(scale);
            TravelMessage.TravelBegin scaled = scaledBegin(crossing.begin, ScaleRule.motion(), 3.0D);
            crossing.travel.receive(new TravelMessage.TravelCancel(crossing.begin.token(), crossing.begin.generation()));
            assertTrue(crossing.travel.receive(scaled));

            walkThrough(crossing, scaled.sourceGeometry());

            assertTrue(crossing.travel.pending());
            assertNull(scale.getModifier(MinecraftScaleAccess.ID));
        }
    }

    @Test
    public void shrinkingPredictsIntoAnExitTooLowForTheDepartureBody() throws ReflectiveOperationException {
        try (ClientSeamlessTravelTest.Crossing crossing = new ClientSeamlessTravelTest.Crossing(false)) {
            AttributeInstance scale = new AttributeInstance(Attributes.SCALE, ignored -> { });
            when(crossing.player.getAttribute(Attributes.SCALE)).thenReturn(scale);
            AtomicReference<AABB> checked = new AtomicReference<>();
            when(crossing.nether.noCollision(same(crossing.player), any(AABB.class))).thenAnswer(call -> {
                AABB bounds = call.getArgument(1, AABB.class);
                checked.set(bounds);
                return bounds.getYsize() <= 1.2D;
            });
            TravelMessage.TravelBegin shrinking = scaledBegin(crossing.begin, ScaleRule.ratio(0.25D, 4.0D), 0.5D);
            crossing.travel.receive(new TravelMessage.TravelCancel(crossing.begin.token(), crossing.begin.generation()));
            crossing.travel.receive(shrinking);
            walkThrough(crossing, shrinking.sourceGeometry());
            assertTrue(crossing.travel.pending());
            assertEquals(0.9D, checked.get().getYsize(), 1.0E-6D);
            assertEquals(0.5D, scale.getValue(), 0.0D);
            assertTrue(crossing.scope.sent.stream().anyMatch(TravelMessage.TravelCross.class::isInstance));
        }
    }

    @Test
    public void theClampedBodyRefusesPredictionBeforeSendingOrMutatingTheSource() throws ReflectiveOperationException {
        try (ClientSeamlessTravelTest.Crossing crossing = new ClientSeamlessTravelTest.Crossing(false)) {
            AttributeInstance scale = new AttributeInstance(Attributes.SCALE, ignored -> { });
            scale.addPermanentModifier(new AttributeModifier(MinecraftScaleAccess.ID, 1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            when(crossing.player.getAttribute(Attributes.SCALE)).thenReturn(scale);
            when(crossing.player.getDimensions(STANDING)).thenReturn(EntityDimensions.scalable(1.2F, 3.6F));
            AtomicReference<AABB> checked = new AtomicReference<>();
            when(crossing.nether.noCollision(same(crossing.player), any(AABB.class))).thenAnswer(call -> {
                AABB bounds = call.getArgument(1, AABB.class);
                checked.set(bounds);
                return bounds.getYsize() <= 1.2D;
            });
            TravelMessage.TravelBegin clamped = scaledBegin(crossing.begin, ScaleRule.ratio(1.0D, 4.0D), 0.25D);
            crossing.travel.receive(new TravelMessage.TravelCancel(crossing.begin.token(), crossing.begin.generation()));
            crossing.travel.receive(clamped);
            walkThrough(crossing, clamped.sourceGeometry());
            assertFalse(crossing.travel.pending());
            assertEquals(1.8D, checked.get().getYsize(), 1.0E-6D);
            assertEquals(2.0D, scale.getValue(), 0.0D);
            assertEquals(1.0D, scale.getModifier(MinecraftScaleAccess.ID).amount(), 0.0D);
            assertSame(crossing.source, crossing.scope.minecraft.level);
            assertTrue(crossing.scope.sent.stream().noneMatch(TravelMessage.TravelCross.class::isInstance));
            verify(crossing.player, never()).setPos(any(Vec3.class));
            verify(crossing.player, never()).setDeltaMovement(any(Vec3.class));
        }
    }

    private static void walkThrough(ClientSeamlessTravelTest.Crossing crossing, ApertureDescriptor geometry) {
        double side = geometry.frontSide() ? 1.0D : -1.0D;
        double nearZ = geometry.signedDistance(0.5D, EYE, 0.2D) * side > 0.0D ? 0.2D : 0.8D;
        double farZ = 1.0D - nearZ;
        when(crossing.scope.minecraft.getCameraEntity()).thenReturn(crossing.player);
        when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(0.5D, EYE, nearZ));
        when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(0.5D, 0.0D, nearZ));
        when(crossing.player.position()).thenReturn(new Vec3(0.5D, 0.0D, farZ));
        crossing.travel.afterTick();
        when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(0.5D, EYE, farZ));
        when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(0.5D, 0.0D, farZ));
        when(crossing.player.getEyePosition()).thenReturn(new Vec3(0.5D, EYE, farZ));
        crossing.travel.afterTick();
    }

    private static TravelMessage.TravelBegin scaledBegin(TravelMessage.TravelBegin base, ScaleRule rule, double ratio) {
        Frame north = Frame.canonical(Face.N);
        Similarity toward = Similarity.between(north, new Vec3d(1.0D, 1.5D, 0.5D), north, new Vec3d(101.0D, 65.5D, 100.5D), ratio);
        TravelMessage.ArrivalRules rules = new TravelMessage.ArrivalRules(OrientationRule.FRAME, false,
            new MomentumRule(MomentumRule.Mode.PRESERVE, 1.0D, 0.0D, null), rule, false);
        return new TravelMessage.TravelBegin(SCALED_TOKEN, base.generation(), new UUID(2, 4), base.sourceWorld(), base.sourceGeometry(),
            TravelMessage.TravelBegin.destinationToSource(toward), (float) ratio, base.world(), base.arrival(), base.environment(), rules, base.resident(),
            base.levelHandle(), base.doorCollision());
    }
}
