package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.Pose;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.ClientAvatarStateAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientTravelMotionTest extends MinecraftTestBase {
    private static final OpticTransform DESTINATION_TO_SOURCE = OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 100, 20, 200);

    @Test
    public void continuousPositionsHistoryAndVelocityUseTheSourceToDestinationTransform() {
        Pose destination = ClientTravelMotion.toward(begin(DESTINATION_TO_SOURCE), motion(30, 28));
        assertEquals(new Vec3d(6, 3, -2), destination.position());
        assertEquals(new Vec3d(5.5, 3, -1.5), destination.previousPosition());
        assertEquals(new Vec3d(5, 3, -1), destination.oldPosition());
        assertEquals(new Vec3d(-0.25, 0.1, -0.5), destination.velocity());
        assertEquals(new Vec3(6, 3, -2), ClientTravelMotion.point(Similarity.of(DESTINATION_TO_SOURCE.inverse(), 1.0D), new Vec3(102, 23, 206)));
    }

    @Test
    public void identityAndRotatedCrossingsPreserveYawInterpolationAcrossTheWrap() {
        Pose identity = ClientTravelMotion.toward(begin(OpticTransform.IDENTITY), motion(181, 179));
        assertEquals(181, identity.yaw(), 0.00001);
        assertEquals(179, identity.previousYaw(), 0.00001);
        Pose rotated = ClientTravelMotion.toward(begin(OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 0, 0, 0)), motion(181, 179));
        assertEquals(2, rotated.yaw() - rotated.previousYaw(), 0.0001);
        assertEquals(2, rotated.bodyYaw() - rotated.previousBodyYaw(), 0.0001);
        assertEquals(2, rotated.headYaw() - rotated.previousHeadYaw(), 0.0001);
    }

    @Test
    public void serverVelocityCorrectionRebasesHistoryAndPreservesMotionSincePrediction() {
        Pose current = motion(30, 28);
        Vec3d predicted = new Vec3d(0.4, 0.1, -0.2);
        Vec3d authoritative = new Vec3d(0.6, 0.1, -0.1);
        Pose reconciled = ClientTravelMotion.reconcile(current, new Vec3d(0.25, 0, 0), predicted, authoritative);
        assertEquals(current.velocity().add(authoritative.subtract(predicted)), reconciled.velocity());
        assertEquals(current.position().add(new Vec3d(0.25, 0, 0)), reconciled.position());
        assertEquals(current.previousPosition().add(new Vec3d(0.25, 0, 0)), reconciled.previousPosition());
        assertEquals(current.oldPosition().add(new Vec3d(0.25, 0, 0)), reconciled.oldPosition());
        assertEquals(current.yaw(), reconciled.yaw(), 0.0F);
        assertEquals(current.previousYaw(), reconciled.previousYaw(), 0.0F);
    }

    @Test
    public void handSwayFollowsTheLookChangeAndTheCapeFollowsTheMappedPosition() {
        LocalPlayer player = mock(LocalPlayer.class);
        ClientAvatarState avatar = mock(ClientAvatarState.class, withSettings().extraInterfaces(ClientAvatarStateAccess.class));
        ClientAvatarStateAccess cloak = (ClientAvatarStateAccess) avatar;
        when(player.avatarState()).thenReturn(avatar);
        player.yBob = 25;
        player.yBobO = 24;
        player.xBob = 10;
        player.xBobO = 9;
        when(cloak.wormholes$xCloak()).thenReturn(102.0);
        when(cloak.wormholes$yCloak()).thenReturn(23.0);
        when(cloak.wormholes$zCloak()).thenReturn(206.0);
        when(cloak.wormholes$xCloakO()).thenReturn(101.0);
        when(cloak.wormholes$yCloakO()).thenReturn(23.0);
        when(cloak.wormholes$zCloakO()).thenReturn(205.0);
        Pose source = motion(30, 28);
        Pose destination = new Pose(source.position(), source.previousPosition(), source.oldPosition(), source.velocity(),
            120, 5, 118, 4, 120, 118, 120, 118);
        ClientTravelMotion.Carry carried = ClientTravelMotion.carry(player).moved(source, destination, Similarity.of(DESTINATION_TO_SOURCE.inverse(), 1.0D));
        carried.restore(player);
        assertEquals(115, player.yBob, 0.0001);
        assertEquals(114, player.yBobO, 0.0001);
        assertEquals(-5, player.xBob, 0.0001);
        assertEquals(-6, player.xBobO, 0.0001);
        verify(cloak).wormholes$xCloak(6.0);
        verify(cloak).wormholes$yCloak(3.0);
        verify(cloak).wormholes$zCloak(-2.0);
        verify(cloak).wormholes$xCloakO(5.0);
        verify(cloak).wormholes$yCloakO(3.0);
        verify(cloak).wormholes$zCloakO(-1.0);
    }

    static TravelMessage.TravelBegin begin(OpticTransform destinationToSource) {
        TravelMessage.TravelBegin base = SeamlessTravelFixtures.begin(false);
        return new TravelMessage.TravelBegin(base.token(), base.generation(), base.sourcePortal(), base.sourceWorld(), base.sourceGeometry(),
            destinationToSource, 1.0F, base.world(), base.arrival(), base.environment(), base.rules(), base.resident(), base.levelHandle());
    }

    static Pose motion(float yaw, float previousYaw) {
        return new Pose(new Vec3d(102, 23, 206), new Vec3d(101.5, 23, 205.5), new Vec3d(101, 23, 205),
            new Vec3d(0.5, 0.1, -0.25), yaw, 20, previousYaw, 19, yaw, previousYaw, yaw, previousYaw);
    }
}
