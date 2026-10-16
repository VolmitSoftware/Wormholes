package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.modded.seamless.StraddleHolder;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.portal.vanilla.EndPortalContact;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

import static net.minecraft.world.entity.Pose.STANDING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientSeamlessTravelTest extends MinecraftTestBase {
    private static final TravelMessage.TravelWorld END = new TravelMessage.TravelWorld(EndPortalContact.END, EndPortalContact.END,
        7, false, false, 0, 0, 256);

    @Test
    public void predictedCrossingNotifiesReturnOwnershipWithTheActualArmAndLevels() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            double nearZ = crossing.begin.sourceGeometry().signedDistance(0.5D, 1.62D, 0.2D) > 0.0D ? 0.2D : 0.8D;
            double farZ = 1.0D - nearZ;
            when(crossing.scope.minecraft.getCameraEntity()).thenReturn(crossing.player);
            when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(0.5D, 1.62D, nearZ));
            when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(0.5D, 0.0D, nearZ));
            when(crossing.player.position()).thenReturn(new Vec3(0.5D, 0.0D, farZ));
            crossing.travel.afterTick();
            verify(crossing.portalViews, never()).crossed(any(), any(), any(), anyBoolean());
            when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(0.5D, 1.62D, farZ));
            when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(0.5D, 0.0D, farZ));
            when(crossing.player.getEyePosition()).thenReturn(new Vec3(0.5D, 1.62D, farZ));
            crossing.travel.afterTick();
            assertTrue(crossing.travel.pending());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            verify(crossing.portalViews).crossed(crossing.begin, crossing.source, crossing.nether, true);
        }
    }

    @Test
    public void anArmIntoTheEndIsPredictedOnContactAndArrivesJustBelowTheArrivalPlane() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, floor(ApertureKind.VANILLA_REPLACEMENT, END))) {
            stepIntoTheFloorPortal(crossing, 0.7D);
            assertTrue(crossing.travel.pending());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            TravelMessage.TravelCross claim = (TravelMessage.TravelCross) crossing.scope.sent.stream()
                .filter(TravelMessage.TravelCross.class::isInstance).findFirst().orElseThrow();
            assertEquals(0.7D, claim.sourcePose().y(), 1.0E-9D);
            ArgumentCaptor<Vec3> position = ArgumentCaptor.forClass(Vec3.class);
            verify(crossing.player, atLeastOnce()).setPos(position.capture());
            assertEquals(101.5D, position.getValue().x, 1.0E-9D);
            assertEquals(64.499D, position.getValue().y, 1.0E-9D);
            assertEquals(101.5D, position.getValue().z, 1.0E-9D);
        }
    }

    @Test
    public void aCrossingIntoTheEndThatLeadsBackHoldsNoReturnStraddleUnderTheArrivalPortal() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, floor(ApertureKind.VANILLA_REPLACEMENT, END, false, true))) {
            StraddleTracker.Straddle[] held = crossing.holdStraddles();
            stepIntoTheFloorPortal(crossing, 0.7D);
            assertTrue(crossing.travel.pending());
            assertNull(held[0]);
        }
    }

    @Test
    public void anArmIntoTheEndIsNotPredictedWhileTheTravellerStandsOnTheFrameRing() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, floor(ApertureKind.VANILLA_REPLACEMENT, END))) {
            stepIntoTheFloorPortal(crossing, 0.8125D);
            assertFalse(crossing.travel.pending());
            assertSame(crossing.source, crossing.scope.minecraft.level);
            assertTrue(crossing.scope.sent.stream().noneMatch(TravelMessage.TravelCross.class::isInstance));
        }
    }

    @Test
    public void anEndReturnArmIsNeitherCrossedOnContactNorWhileFallingThroughItsPlane() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, floor(ApertureKind.VANILLA_REPLACEMENT, ResidentTestFixtures.OVERWORLD))) {
            stepIntoTheFloorPortal(crossing, 0.0D);
            dropThroughTheFloorPlane(crossing);
            assertFalse(crossing.travel.pending());
            assertSame(crossing.source, crossing.scope.minecraft.level);
            assertTrue(crossing.scope.sent.stream().noneMatch(TravelMessage.TravelCross.class::isInstance));
            verify(crossing.portalViews, never()).crossed(any(), any(), any(), anyBoolean());
        }
    }

    @Test
    public void anEndReturnArmIsCrossedRisingThroughItsPlaneAndLandsOnTheEndPortalFrame() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, floor(ApertureKind.VANILLA_REPLACEMENT, ResidentTestFixtures.OVERWORLD, true))) {
            riseThroughTheFloorPlane(crossing);
            assertTrue(crossing.travel.pending());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            ArgumentCaptor<Vec3> position = ArgumentCaptor.forClass(Vec3.class);
            verify(crossing.player, atLeastOnce()).setPos(position.capture());
            assertEquals(99.5D, position.getValue().x, 1.0E-9D);
            assertEquals(65.0D, position.getValue().y, 1.0E-9D);
            assertEquals(101.5D, position.getValue().z, 1.0E-9D);
        }
    }

    @Test
    public void aPredictedCrossingIntoAnArrivalThatLeadsBackHoldsTheReturnStraddle() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, SeamlessTravelFixtures.begin(true, true))) {
            StraddleTracker.Straddle[] held = crossing.holdStraddles();
            crossing.approach();
            assertNotNull(held[0]);
            crossing.cross();
            assertTrue(crossing.travel.pending());
            assertNotNull(held[0]);
            assertSame(crossing.source, held[0].destination());
            verify(crossing.portalViews).crossed(crossing.begin, crossing.source, crossing.nether, true);
        }
    }

    @Test
    public void aFrameFloorArmIsStillPredictedWhenTheEyeDropsThroughItsPlane() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, floor(ApertureKind.FRAME))) {
            dropThroughTheFloorPlane(crossing);
            assertTrue(crossing.travel.pending());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            assertTrue(crossing.scope.sent.stream().anyMatch(TravelMessage.TravelCross.class::isInstance));
        }
    }

    @Test
    public void aFrameFloorArmIsPredictedWhenTheTravellerRestsOnTheBlockUnderItsOpening() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, floor(ApertureKind.FRAME))) {
            when(crossing.source.getBlockCollisions(any(), any(AABB.class))).thenAnswer(invocation ->
                ((AABB) invocation.getArgument(1)).minY < 0.0D ? List.of(Shapes.block().move(1, -1, 1)) : List.of());
            stepIntoTheFloorPortal(crossing, 0.0D);
            assertTrue(crossing.travel.pending());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            TravelMessage.TravelCross claim = (TravelMessage.TravelCross) crossing.scope.sent.stream()
                .filter(TravelMessage.TravelCross.class::isInstance).findFirst().orElseThrow();
            assertEquals(0.0D, claim.sourcePose().y(), 1.0E-9D);
            ArgumentCaptor<Vec3> position = ArgumentCaptor.forClass(Vec3.class);
            verify(crossing.player, atLeastOnce()).setPos(position.capture());
            assertEquals(101.5D, position.getValue().x, 1.0E-9D);
            assertEquals(64.5D - 1.0E-3D - 1.62D, position.getValue().y, 1.0E-9D);
            assertEquals(101.5D, position.getValue().z, 1.0E-9D);
        }
    }

    @Test
    public void aFrameFloorArmOverAirIsNotPredictedWhileTheTravellerStandsInItsOpening() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, floor(ApertureKind.FRAME))) {
            stepIntoTheFloorPortal(crossing, 0.0D);
            assertFalse(crossing.travel.pending());
            assertSame(crossing.source, crossing.scope.minecraft.level);
            assertTrue(crossing.scope.sent.stream().noneMatch(TravelMessage.TravelCross.class::isInstance));
        }
    }

    @Test
    public void anArrivalWithItsHeadInSolidCeilingCellsIsPredictedBelowThem() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, floor(ApertureKind.FRAME))) {
            when(crossing.nether.noCollision(any(Entity.class), any(AABB.class))).thenAnswer(invocation ->
                ((AABB) invocation.getArgument(1)).maxY <= 64.0D);
            dropThroughTheFloorPlane(crossing);
            assertTrue(crossing.travel.pending());
            TravelMessage.TravelCross claim = (TravelMessage.TravelCross) crossing.scope.sent.stream()
                .filter(TravelMessage.TravelCross.class::isInstance).findFirst().orElseThrow();
            assertEquals(-1.32D, claim.sourcePose().y(), 1.0E-9D);
            ArgumentCaptor<Vec3> position = ArgumentCaptor.forClass(Vec3.class);
            verify(crossing.player, atLeastOnce()).setPos(position.capture());
            assertEquals(64.0D - 1.0E-3D, position.getValue().y + 1.8D, 1.0E-6D);
        }
    }

    @Test
    public void aPredictedCrossingIntoAnArrivalThatDoesNotLeadBackReleasesEveryStraddle() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false, SeamlessTravelFixtures.begin(true, false))) {
            StraddleTracker.Straddle[] held = crossing.holdStraddles();
            crossing.approach();
            assertNotNull(held[0]);
            crossing.cross();
            assertTrue(crossing.travel.pending());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            assertNull(held[0]);
            verify(crossing.portalViews).crossed(crossing.begin, crossing.source, crossing.nether, true);
        }
    }

    @Test
    public void anEndPortalArmNeverOpensTheFloorUnderItsAperture() {
        Box standing = new Box(1.2D, 1.8D, 0.0D, 1.8D, 1.2D, 1.8D);
        Vec3d eye = new Vec3d(1.5D, 1.62D, 1.5D);
        ClientLevel destination = mock(ClientLevel.class);

        assertNull(ClientSeamlessTravel.straddle(floor(ApertureKind.VANILLA_REPLACEMENT), destination, standing, eye));
        assertNotNull(ClientSeamlessTravel.straddle(floor(ApertureKind.FRAME), destination, standing, eye));
    }

    @Test
    public void anOlderConfirmationDoesNotReplaceTheLatestPredictedReturn() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(true)) {
            TravelMessage.TravelBegin next = mock(TravelMessage.TravelBegin.class);
            crossing.pending().add(Crossing.crossing(next, crossing.nether, crossing.source));
            crossing.scope.minecraft.level = crossing.source;
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            assertTrue(crossing.travel.pending());
            assertSame(crossing.source, crossing.scope.minecraft.level);
            verify(crossing.portalViews, never()).crossed(any(), any(), any(), anyBoolean());
            verify(crossing.portalViews, never()).resetReturn(any());
        }
    }

    @Test
    public void serverCrossingWithoutAnArmCannotBorrowTheLastRenderedPortal() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.travel.receive(new TravelMessage.TravelCancel(crossing.begin.token(), crossing.begin.generation()));
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            verify(crossing.portalViews).crossed(null, crossing.source, crossing.nether, true);
        }
    }

    @Test
    public void serverCrossingCarriesTheConfiguredRecursionChoice() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            WormholesClientConfig config = new WormholesClientConfig();
            config.clientRecursion = false;
            when(crossing.client.config()).thenReturn(config);
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            verify(crossing.portalViews).crossed(crossing.begin, crossing.source, crossing.nether, false);
        }
    }

    @Test
    public void armsAreHeldPerSourcePortalUntilTheServerDisarmsThem() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            assertTrue(crossing.travel.armed(crossing.begin.sourcePortal()));
            assertTrue(crossing.travel.receive(new TravelMessage.TravelCancel(SeamlessTravelFixtures.TOKEN, SeamlessTravelFixtures.GENERATION)));
            assertFalse(crossing.travel.armed(crossing.begin.sourcePortal()));
            assertFalse(crossing.travel.receive(new TravelMessage.TravelCancel(new UUID(3, 3), 2L)));
        }
    }

    @Test
    public void acceptWithinToleranceKeepsTheSwappedLevelThePredictedMomentumAndRetiresTheSource() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(true)) {
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0004D, 0.2F));
            assertFalse(crossing.travel.pending());
            assertTrue(crossing.travel.armed());
            assertNull(crossing.residents.crossingSource());
            assertTrue(crossing.residents.resident(crossing.source));
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            assertSame(crossing.nether, crossing.scope.connection.getLevel());
            verify(crossing.scope.minecraft, never()).setLevel(any());
            assertPosition(crossing.player, 100.5004D, 99.0D);
            verify(crossing.player).setYRot(185.0F);
            ArgumentCaptor<Vec3> velocity = ArgumentCaptor.forClass(Vec3.class);
            verify(crossing.player).setDeltaMovement(velocity.capture());
            assertEquals(-0.3D, velocity.getValue().z, 0.000001D);
            verify(crossing.client).dropProjectedEntities(crossing.begin.sourceGeometry());
            assertTrue(crossing.scope.sent.stream().noneMatch(TravelMessage.TravelCancel.class::isInstance));
            assertTrue(crossing.scope.sent.stream().noneMatch(TravelMessage.TravelCross.class::isInstance));
        }
    }

    @Test
    public void acceptOutsideToleranceCorrectsToTheServerPose() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(true)) {
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.5D, 10.0F));
            assertFalse(crossing.travel.pending());
            assertPosition(crossing.player, 101.0D, 99.0D);
            verify(crossing.player).setYRot(195.0F);
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            crossing.scope.worlds.verify(ClientWorldLoader::forceFullSectionDiscovery);
        }
    }

    @Test
    public void cancelDuringThePendingWindowRestoresTheSourceLevelAndKeepsTheArm() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(true)) {
            crossing.travel.receive(new TravelMessage.TravelCancel(SeamlessTravelFixtures.TOKEN, SeamlessTravelFixtures.GENERATION));
            assertFalse(crossing.travel.pending());
            assertTrue(crossing.travel.armed(crossing.begin.sourcePortal()));
            assertNull(crossing.residents.crossingSource());
            assertFalse(crossing.residents.resident(crossing.source));
            assertSame(crossing.source, crossing.scope.connection.getLevel());
            verify(crossing.nether).removeEntity(42, Entity.RemovalReason.CHANGED_DIMENSION);
            verify(crossing.source).addEntity(crossing.player);
            verify((PreparedChunkColumns) crossing.source.getChunkSource()).wormholes$announceColumns();
            verify(crossing.portalViews).resetReturn(crossing.source);
            crossing.scope.worlds.verify(ClientWorldLoader::forceFullSectionDiscovery);
        }
    }

    @Test
    public void anUnclaimedServerCrossingSwapsIntoTheResidentLevelWithoutReloadingTheClient() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            verify(crossing.scope.minecraft, never()).setLevel(any());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            NativeExtractorFixtures.untouched(crossing.scope.minecraft);
            crossing.scope.worlds.verify(() -> ClientWorldLoader.changeLevel(same(crossing.nether), any()));
            verify(crossing.scope.minecraft.particleEngine, never()).setLevel(any());
            verify((ParticleEngineAccess) crossing.scope.minecraft.particleEngine).wormholes$level(crossing.nether);
            verify(crossing.scope.minecraft.gameRenderer).setLevel(crossing.nether);
            verify(crossing.scope.minecraft.getSoundManager(), never()).stop();
            assertSame(crossing.nether, crossing.scope.connection.getLevel());
            assertTrue(crossing.residents.resident(crossing.source));
            verify(crossing.source).removeEntity(42, Entity.RemovalReason.CHANGED_DIMENSION);
            verify(crossing.nether).addEntity(crossing.player);
            assertPosition(crossing.player, SeamlessTravelFixtures.EXPECTED_ARRIVAL.x, SeamlessTravelFixtures.EXPECTED_ARRIVAL.z);
            verify(crossing.client).dropProjectedEntities(crossing.begin.sourceGeometry());
            verify(crossing.portalViews).crossed(crossing.begin, crossing.source, crossing.nether, true);
            crossing.scope.worlds.verify(ClientWorldLoader::forceFullSectionDiscovery);
        }
    }

    @Test
    public void anUnclaimedServerCrossingReportsTheSwapForTheAcceptedCrossing() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            List<TravelMessage.TravelCross> reported = crossing.scope.sent.stream().filter(TravelMessage.TravelCross.class::isInstance)
                .map(TravelMessage.TravelCross.class::cast).toList();
            assertEquals(1, reported.size());
            assertEquals(SeamlessTravelFixtures.TOKEN, reported.getFirst().token());
            assertEquals(SeamlessTravelFixtures.GENERATION, reported.getFirst().generation());
            assertSame(crossing.nether, crossing.scope.minecraft.level);
        }
    }

    @Test
    public void anUnclaimedServerCrossingAlongAResidentRouteInTheSameDimensionSwapsIntoTheResidentLevel() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            TravelMessage.TravelAccept base = SeamlessTravelFixtures.accept(0.0D, 0.0F);
            crossing.travel.receive(new TravelMessage.TravelAccept(base.token(), base.generation(), base.contentRevision(), base.pose(),
                base.velocity(), base.levelHandle(), false, base.serverTick(), base.gravity(), base.entityScale()));
            assertSame(crossing.nether, crossing.scope.minecraft.level);
            assertTrue(crossing.residents.resident(crossing.source));
            assertPosition(crossing.player, SeamlessTravelFixtures.EXPECTED_ARRIVAL.x, SeamlessTravelFixtures.EXPECTED_ARRIVAL.z);
        }
    }

    @Test
    public void aClosedRouteReleasesReturnViewsIntoItsLevelOnce() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            assertTrue(crossing.travel.receive(new TravelMessage.RemoteLevelClose(3)));
            verify(crossing.portalViews).routeClosed(crossing.nether);
            assertTrue(crossing.travel.receive(new TravelMessage.RemoteLevelClose(3)));
            assertTrue(crossing.travel.receive(new TravelMessage.RemoteLevelClose(9)));
            verify(crossing.portalViews).routeClosed(any());
        }
    }

    @Test
    public void anUnclaimedServerCrossingIntoAnUnknownLevelAsksForItToBeReopened() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            crossing.residents.close(new TravelMessage.RemoteLevelClose(3));
            crossing.travel.receive(SeamlessTravelFixtures.accept(0.0D, 0.0F));
            assertSame(crossing.source, crossing.scope.minecraft.level);
            assertTrue(crossing.scope.sent.contains(new TravelMessage.RemoteLevelReopen(3)));
            assertTrue(crossing.scope.sent.stream().noneMatch(TravelMessage.TravelCross.class::isInstance));
            verify(crossing.portalViews, never()).crossed(any(), any(), any(), anyBoolean());
            crossing.scope.worlds.verify(ClientWorldLoader::forceFullSectionDiscovery, never());
        }
    }

    @Test
    public void preparationReportsOnlyAMissingArmedDestinationLevel() throws ReflectiveOperationException {
        try (Crossing crossing = new Crossing(false)) {
            assertNull(crossing.travel.unprepared());
            crossing.travel.receive(new TravelMessage.TravelCancel(SeamlessTravelFixtures.TOKEN, SeamlessTravelFixtures.GENERATION));
            assertEquals("no armed destination", crossing.travel.unprepared());
        }
    }

    private static void stepIntoTheFloorPortal(Crossing crossing, double feet) {
        when(crossing.scope.minecraft.getCameraEntity()).thenReturn(crossing.player);
        when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(1.5D, 0.8125D + 1.62D, 2.5D));
        when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(1.5D, 0.8125D, 2.5D));
        when(crossing.player.position()).thenReturn(new Vec3(1.5D, 0.8125D, 2.5D));
        when(crossing.player.getBoundingBox()).thenReturn(new AABB(1.2D, 0.8125D, 2.2D, 1.8D, 2.6125D, 2.8D));
        crossing.travel.afterTick();
        when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(1.5D, feet + 1.62D, 1.5D));
        when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(1.5D, feet, 1.5D));
        when(crossing.player.position()).thenReturn(new Vec3(1.5D, feet, 1.5D));
        when(crossing.player.getEyePosition()).thenReturn(new Vec3(1.5D, feet + 1.62D, 1.5D));
        when(crossing.player.getBoundingBox()).thenReturn(new AABB(1.2D, feet, 1.2D, 1.8D, feet + 1.8D, 1.8D));
        crossing.travel.afterTick();
    }

    private static void dropThroughTheFloorPlane(Crossing crossing) {
        when(crossing.scope.minecraft.getCameraEntity()).thenReturn(crossing.player);
        when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(1.5D, 1.62D, 1.5D));
        when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(1.5D, 0.0D, 1.5D));
        when(crossing.player.position()).thenReturn(new Vec3(1.5D, -1.32D, 1.5D));
        crossing.travel.afterTick();
        when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(1.5D, 0.3D, 1.5D));
        when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(1.5D, -1.32D, 1.5D));
        when(crossing.player.getEyePosition()).thenReturn(new Vec3(1.5D, 0.3D, 1.5D));
        crossing.travel.afterTick();
    }

    private static void riseThroughTheFloorPlane(Crossing crossing) {
        when(crossing.scope.minecraft.getCameraEntity()).thenReturn(crossing.player);
        when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(1.5D, 0.3D, 1.5D));
        when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(1.5D, -1.32D, 1.5D));
        when(crossing.player.position()).thenReturn(new Vec3(1.5D, -1.32D, 1.5D));
        crossing.travel.afterTick();
        when(crossing.player.getEyePosition(0.0F)).thenReturn(new Vec3(1.5D, 0.7D, 1.5D));
        when(crossing.player.getPosition(0.0F)).thenReturn(new Vec3(1.5D, -0.92D, 1.5D));
        when(crossing.player.position()).thenReturn(new Vec3(1.5D, -0.92D, 1.5D));
        when(crossing.player.getEyePosition()).thenReturn(new Vec3(1.5D, 0.7D, 1.5D));
        crossing.travel.afterTick();
    }

    private static TravelMessage.TravelBegin floor(int kind) {
        return floor(kind, ResidentTestFixtures.NETHER);
    }

    private static TravelMessage.TravelBegin floor(int kind, TravelMessage.TravelWorld destination) {
        return floor(kind, destination, false);
    }

    private static TravelMessage.TravelBegin floor(int kind, TravelMessage.TravelWorld destination, boolean underside) {
        return floor(kind, destination, underside, kind == ApertureKind.FRAME);
    }

    private static TravelMessage.TravelBegin floor(int kind, TravelMessage.TravelWorld destination, boolean underside, boolean returnTraversable) {
        ApertureDescriptor geometry = new ApertureDescriptor(0, 0, 0, Face.D.ordinal(), underside, 0, false, 3, 3, new long[] {511L},
            ShapeDescriptor.FULL, 0.0F, 0.0F, 1.0F, 64, 0, 0, 0, 0, 0, 0, kind, 0.0D, 0, 11L, List.of());
        return new TravelMessage.TravelBegin(SeamlessTravelFixtures.TOKEN, SeamlessTravelFixtures.GENERATION, new UUID(1, 3), "minecraft:overworld",
            geometry, OpticTransform.translation(-100, -64, -100), 1.0F, destination,
            new TravelMessage.TravelPose(101.5, 64, 101.5, 180, 10), ResidentTestFixtures.environment(destination),
            TravelMessage.ArrivalRules.FRAME, true, 3, null, returnTraversable);
    }

    private static void assertPosition(LocalPlayer player, double x, double z) {
        ArgumentCaptor<Vec3> position = ArgumentCaptor.forClass(Vec3.class);
        verify(player, atLeastOnce()).setPos(position.capture());
        assertEquals(x, position.getValue().x, 0.000001D);
        assertEquals(64.0D, position.getValue().y, 0.000001D);
        assertEquals(z, position.getValue().z, 0.000001D);
    }

    static final class Crossing implements AutoCloseable {
        final ClientLevel source = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        final ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(source);
        final ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
        final ClientSeamlessTravel travel = new ClientSeamlessTravel(scope.sent::add, residents);
        final TravelMessage.TravelBegin begin;
        final LocalPlayer player = SeamlessTravelFixtures.player();
        final WormholesClient client = mock(WormholesClient.class);
        final PortalViews portalViews = mock(PortalViews.class);
        final MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
        final MockedStatic<ClientPortalRenderer> renderers = mockStatic(ClientPortalRenderer.class);
        final ClientPortalRenderer renderer = mock(ClientPortalRenderer.class);
        final ClientLevel nether;

        Crossing(boolean predicted) throws ReflectiveOperationException {
            this(predicted, SeamlessTravelFixtures.begin(true));
        }

        Crossing(boolean predicted, TravelMessage.TravelBegin begin) throws ReflectiveOperationException {
            this.begin = begin;
            ClientViewSession session = mock(ClientViewSession.class);
            when(session.has(any(ViewStreamCapability.class))).thenReturn(true);
            when(session.has(anyLong())).thenReturn(true);
            when(client.session()).thenReturn(session);
            when(client.portalViews()).thenReturn(portalViews);
            clients.when(WormholesClient::instance).thenReturn(client);
            renderers.when(ClientPortalRenderer::instance).thenReturn(renderer);
            worldGravity(player);
            when(player.getPose()).thenReturn(STANDING);
            when(player.getDimensions(STANDING)).thenReturn(EntityDimensions.scalable(0.6F, 1.8F));
            when(player.getId()).thenReturn(42);
            when(player.getEyeHeight()).thenReturn(1.62F);
            when(player.getEyePosition()).thenReturn(new Vec3(100.5D, 65.62D, 99.0D));
            scope.minecraft.player = player;
            nether = ResidentTestFixtures.level(ResidentTestFixtures.NETHER);
            when(nether.noCollision(any(Entity.class), any(AABB.class))).thenReturn(true);
            ResidentTestFixtures.loaded(nether, 6, 6);
            residents.retire(nether);
            assertSame(nether, residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 6, 6)));
            assertTrue(travel.receive(begin));
            if (!predicted) {
                return;
            }
            pending().add(crossing(begin, source, nether));
            residents.crossing(source);
            scope.minecraft.level = nether;
            ((PreparedPacketAccess) scope.connection).wormholes$level(nether);
            assertTrue(travel.pending());
        }

        StraddleTracker.Straddle[] holdStraddles() {
            StraddleTracker.Straddle[] held = new StraddleTracker.Straddle[1];
            StraddleHolder holder = (StraddleHolder) player;
            doAnswer(invocation -> {
                held[0] = invocation.getArgument(0);
                return null;
            }).when(holder).wormholesStraddle(any());
            when(holder.wormholesStraddle()).thenAnswer(invocation -> held[0]);
            return held;
        }

        void approach() {
            Vec3 eye = new Vec3(0.5D, 1.62D, nearZ());
            StraddleTracker.register(player, ClientSeamlessTravel.straddle(begin, nether,
                new Box(0.2D, 0.8D, 0.0D, 1.8D, eye.z - 0.3D, eye.z + 0.3D), new Vec3d(eye.x, eye.y, eye.z)));
            when(scope.minecraft.getCameraEntity()).thenReturn(player);
            when(player.getEyePosition(0.0F)).thenReturn(eye);
            when(player.getPosition(0.0F)).thenReturn(new Vec3(0.5D, 0.0D, eye.z));
            when(player.position()).thenReturn(new Vec3(0.5D, 0.0D, 1.0D - eye.z));
            travel.afterTick();
        }

        void cross() {
            double farZ = 1.0D - nearZ();
            when(player.getEyePosition(0.0F)).thenReturn(new Vec3(0.5D, 1.62D, farZ));
            when(player.getPosition(0.0F)).thenReturn(new Vec3(0.5D, 0.0D, farZ));
            when(player.getEyePosition()).thenReturn(new Vec3(0.5D, 1.62D, farZ));
            travel.afterTick();
        }

        private double nearZ() {
            return begin.sourceGeometry().signedDistance(0.5D, 1.62D, 0.2D) > 0.0D ? 0.2D : 0.8D;
        }

        @SuppressWarnings("unchecked")
        private ArrayDeque<Object> pending() throws ReflectiveOperationException {
            Field field = ClientSeamlessTravel.class.getDeclaredField("pending");
            field.setAccessible(true);
            return (ArrayDeque<Object>) field.get(travel);
        }

        private static Object crossing(TravelMessage.TravelBegin begin, ClientLevel from, ClientLevel to) throws ReflectiveOperationException {
            Pose before = new Pose(new Vec3d(0.5, 0, 0.2), new Vec3d(0.5, 0, 0.5), new Vec3d(0.5, 0, 0.5), new Vec3d(0, 0, -0.3),
                180, 10, 178, 9, 179, 177, 181, 179);
            ClientTravelMotion.Carry carry = new ClientTravelMotion.Carry(180, 10, 178, 9, new Vec3d(0.5, 0, 0.2), new Vec3d(0.5, 0, 0.5));
            Class<?> type = Class.forName(ClientSeamlessTravel.class.getName() + "$Crossing");
            Constructor<?> constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return constructor.newInstance(begin, from, to, before, carry, SeamlessTravelFixtures.DESTINATION, SeamlessTravelFixtures.EXPECTED_ARRIVAL,
                System.currentTimeMillis() + 60_000L, 1.0D, GravityFrame.WORLD);
        }

        @Override
        public void close() {
            renderers.close();
            clients.close();
            scope.close();
        }
    }
}
