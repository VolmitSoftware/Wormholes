package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftScaleAccess;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class ClientSeamlessTravelGravityTest extends MinecraftTestBase {
    private static final UUID PLAYER = new UUID(71, 82);
    private static final AxisPermutation QUARTER_TURN = AxisPermutation.of(Face.D, Face.E, Face.S);

    @Test
    public void gravityAuthorityBeforeServerCrossingCannotApplyThePortalTurnTwice() throws ReflectiveOperationException {
        try (ClientSeamlessTravelTest.Crossing crossing = new ClientSeamlessTravelTest.Crossing(false);
             MockedStatic<MinecraftGravity> gravity = mockStatic(MinecraftGravity.class)) {
            GravityFrame destination = GravityFrame.WORLD.transformed(QUARTER_TURN);
            AtomicReference<GravityFrame> current = new AtomicReference<>(GravityFrame.WORLD);
            gravity.when(() -> MinecraftGravity.frame(any(Entity.class))).thenAnswer(call -> current.get());
            gravity.when(() -> MinecraftGravity.set(any(Entity.class), any(GravityFrame.class))).thenAnswer(call -> {
                current.set(call.getArgument(1, GravityFrame.class));
                return null;
            });
            when(crossing.player.getUUID()).thenReturn(PLAYER);
            when(crossing.source.getEntity(42)).thenReturn(crossing.player);
            AttributeInstance scale = new AttributeInstance(Attributes.SCALE, ignored -> { });
            when(crossing.player.getAttribute(Attributes.SCALE)).thenReturn(scale);
            TravelMessage.TravelBegin arm = rotated(crossing.begin);
            crossing.travel.receive(arm);
            crossing.travel.receive(new TravelMessage.EntityGravity(0, 42, PLAYER, destination, false));
            assertEquals(destination, current.get());
            TravelMessage.TravelAccept base = SeamlessTravelFixtures.accept(0.0D, 0.0F);
            crossing.travel.receive(new TravelMessage.TravelAccept(base.token(), base.generation(), base.contentRevision(), base.pose(),
                base.velocity(), base.levelHandle(), base.dimensionChanged(), base.serverTick(), destination, 0.5D));
            assertEquals(destination, current.get());
            assertEquals(-0.5D, scale.getModifier(MinecraftScaleAccess.ID).amount(), 0.0D);
            assertTrue(!crossing.travel.pending());
        }
    }

    @Test
    public void finalPredictionConfirmationUsesTheAcceptedBasisAndAbsoluteScale() throws ReflectiveOperationException {
        try (ClientSeamlessTravelTest.Crossing crossing = new ClientSeamlessTravelTest.Crossing(true);
             MockedStatic<MinecraftGravity> gravity = mockStatic(MinecraftGravity.class)) {
            GravityFrame accepted = GravityFrame.WORLD.transformed(QUARTER_TURN);
            AtomicReference<GravityFrame> current = new AtomicReference<>(GravityFrame.WORLD);
            gravity.when(() -> MinecraftGravity.frame(any(Entity.class))).thenAnswer(call -> current.get());
            gravity.when(() -> MinecraftGravity.set(any(Entity.class), any(GravityFrame.class))).thenAnswer(call -> {
                current.set(call.getArgument(1, GravityFrame.class));
                return null;
            });
            when(crossing.player.getUUID()).thenReturn(PLAYER);
            AttributeInstance scale = new AttributeInstance(Attributes.SCALE, ignored -> { });
            when(crossing.player.getAttribute(Attributes.SCALE)).thenReturn(scale);
            scale.addOrReplacePermanentModifier(new AttributeModifier(MinecraftScaleAccess.ID, 2.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            TravelMessage.TravelAccept base = SeamlessTravelFixtures.accept(0.0004D, 0.2F);
            crossing.travel.receive(new TravelMessage.TravelAccept(base.token(), base.generation(), base.contentRevision(), base.pose(),
                base.velocity(), base.levelHandle(), base.dimensionChanged(), base.serverTick(), accepted, 0.5D));
            assertEquals(accepted, current.get());
            assertEquals(-0.5D, scale.getModifier(MinecraftScaleAccess.ID).amount(), 0.0D);
            assertTrue(!crossing.travel.pending());
        }
    }

    @Test
    public void serverArrivalReconstructsEverySourceBasisFromAuthority() {
        TravelMessage.TravelBegin base = SeamlessTravelFixtures.begin(false);
        TravelMessage.TravelBegin arm = rotated(base);
        for (int index = 0; index < 48; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            if (permutation.reflects()) {
                continue;
            }
            GravityFrame departure = new GravityFrame(permutation);
            GravityFrame arrival = departure.transformed(QUARTER_TURN);
            assertEquals(departure, ClientSeamlessTravel.sourceGravity(arm, arrival));
            assertEquals(arrival, ClientSeamlessTravel.sourceGravity(base, arrival));
        }
    }

    private static TravelMessage.TravelBegin rotated(TravelMessage.TravelBegin base) {
        Similarity toward = Similarity.between(Frame.canonical(Face.N), new Vec3d(100.5D, 64.0D, 99.0D),
            Frame.fromNormalUp(Face.N, Face.E), new Vec3d(100.5D, 64.0D, 99.2D), 0.5D);
        TravelMessage.ArrivalRules rules = new TravelMessage.ArrivalRules(base.rules().orientation(), base.rules().gravityFlip(),
            base.rules().momentum(), ScaleRule.ratio(0.25D, 4.0D), true);
        return new TravelMessage.TravelBegin(base.token(), base.generation(), base.sourcePortal(), base.sourceWorld(), base.sourceGeometry(),
            TravelMessage.TravelBegin.destinationToSource(toward), 0.5F, base.world(), base.arrival(), base.environment(), rules,
            base.resident(), base.levelHandle(), base.doorCollision());
    }
}
