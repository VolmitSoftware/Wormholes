package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.crossing.GravityFrame;

import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.plate.ChunkLeaseRegistry;
import art.arcane.wormholes.modded.MinecraftDoorService;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.SeamlessListenerAccess;
import art.arcane.wormholes.modded.seamless.RemoteRoutes;
import art.arcane.wormholes.modded.seamless.RemoteRoute;
import art.arcane.wormholes.modded.seamless.RouteWindow;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.vanilla.EndPortalContact;
import art.arcane.wormholes.render.client.session.ClientViewTravel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MinecraftSeamlessTravelTest extends MinecraftTestBase {
    @Test
    public void seamlessSessionArmsRemoteRoutesWithoutCapturingChunks() {
        Fixture fixture = new Fixture();

        fixture.seamless.tick(fixture.session, fixture.player);

        verify(fixture.routes).update(eq(fixture.player), eq(fixture.session), eq(List.of()), anyLong());
        verify(fixture.leases, never()).retain(any(), any(), anyInt(), anyInt());
    }

    @Test
    public void aCrossingForAnUnarmedRouteIsRejectedWithTheVanillaCorrection() {
        Fixture fixture = new Fixture();
        TravelMessage.TravelCross cross = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L,
            new TravelMessage.TravelPose(0.5D, 64.0D, 0.4D, 0.0F, 0.0F), new Vec3d(0.5D, 65.62D, 0.6D), new Vec3d(0.5D, 65.62D, 0.4D));
        when(fixture.session.takeSeamlessCross()).thenReturn(cross, (TravelMessage.TravelCross) null);

        fixture.seamless.settle(fixture.session, fixture.player);

        verify(fixture.player.connection).teleport(4.0D, 64.0D, -2.0D, 30.0F, 5.0F);
        verify(fixture.session).sendTravel(new TravelMessage.TravelCancel(cross.token(), cross.generation()));
        verify(fixture.registry, never()).crossSeamless(any(), any(), any(), any());
        verify(fixture.doors, never()).crossSeamless(any(), any(), any());
    }

    @Test
    public void serverDetectedCrossingsThroughUnarmedPortalsKeepTheOrdinaryPath() {
        Fixture fixture = new Fixture();

        fixture.seamless.tick(fixture.session, fixture.player);

        assertFalse(fixture.seamless.defer(fixture.player, UUID.randomUUID()));
        assertFalse(fixture.seamless.crossing(fixture.player.getUUID()));
    }

    @Test
    public void aServerDetectedEndPortalContactWaitsForTheClientClaim() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        MinecraftPortal source = floor(DimensionalPortalKind.END_SOURCE, 64);
        fixture.arm(source, floor(DimensionalPortalKind.END_ARRIVAL, 80));

        assertTrue(fixture.seamless.defer(fixture.player, source.getId()));
        assertFalse(fixture.seamless.crossing(fixture.player.getUUID()));
    }

    @Test
    public void aClaimedEndPortalContactCrossesFromJustBelowTheSourcePlane() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        MinecraftPortal source = floor(DimensionalPortalKind.END_SOURCE, 64);
        MinecraftPortal destination = floor(DimensionalPortalKind.END_ARRIVAL, 80);
        TravelMessage.TravelBegin begin = fixture.endArm(source, destination);
        TravelMessage.TravelCross cross = new TravelMessage.TravelCross(begin.token(), begin.generation(), 1L,
            new TravelMessage.TravelPose(11.5D, 64.7D, 11.5D, 0.0F, 40.0F), new Vec3d(11.5D, 66.32D, 11.6D), new Vec3d(11.5D, 66.32D, 11.5D));
        when(fixture.session.takeSeamlessCross()).thenReturn(cross, (TravelMessage.TravelCross) null);

        fixture.seamless.settle(fixture.session, fixture.player);

        ArgumentCaptor<PlaneCrossing> crossing = ArgumentCaptor.forClass(PlaneCrossing.class);
        verify(fixture.registry).crossSeamless(eq(fixture.player), eq(source.getId()), eq(destination), crossing.capture());
        assertEquals(11.5D, crossing.getValue().point().x(), 1.0E-9D);
        assertEquals(64.499D, crossing.getValue().point().y(), 1.0E-9D);
        assertEquals(11.5D, crossing.getValue().point().z(), 1.0E-9D);
        assertFalse(crossing.getValue().frontSide());
    }

    @Test
    public void aServerDetectedFrameFloorCrossingStillWaitsForTheClientClaim() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        MinecraftPortal source = floor(DimensionalPortalKind.NONE, 64);
        fixture.arm(source, floor(DimensionalPortalKind.NONE, 80));

        assertTrue(fixture.seamless.defer(fixture.player, source.getId()));
        assertFalse(fixture.seamless.crossing(fixture.player.getUUID()));
    }

    @Test
    public void aServerStartedCrossingIgnoresClientMovesUntilTheClientReportsItsSwap() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        Listener listener = fixture.listener(41, 900);
        TravelMessage.TravelBegin begin = fixture.serverStarted();

        fixture.seamless.moved(fixture.player, true);

        assertEquals(new Vec3(11.5D, 80.0D, 11.5D), listener.position);
        assertEquals(42, listener.teleport);
        assertEquals(900, listener.time);
        TravelMessage.TravelCross swap = Fixture.swap(begin);
        when(fixture.session.takeSeamlessCross()).thenReturn(swap, (TravelMessage.TravelCross) null);
        fixture.seamless.settle(fixture.session, fixture.player);
        assertNull(listener.position);
        assertEquals(42, listener.teleport);
        verify(fixture.player.connection).resetPosition();
        verify(fixture.session, never()).sendTravel(any());
        verify(fixture.player.connection, never()).teleport(4.0D, 64.0D, -2.0D, 30.0F, 5.0F);
    }

    @Test
    public void aServerStartedCrossingWrapsTheTeleportIdLikeVanilla() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        Listener listener = fixture.listener(Integer.MAX_VALUE - 1, 900);
        fixture.serverStarted();

        fixture.seamless.moved(fixture.player, true);

        assertEquals(0, listener.teleport);
    }

    @Test
    public void aVanillaTeleportDuringTheWaitKeepsItsOwnAcknowledgement() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        Listener listener = fixture.listener(41, 900);
        TravelMessage.TravelBegin begin = fixture.serverStarted();
        fixture.seamless.moved(fixture.player, true);
        listener.teleport = 43;
        listener.position = new Vec3(3.0D, 70.0D, 3.0D);
        TravelMessage.TravelCross swap = Fixture.swap(begin);
        when(fixture.session.takeSeamlessCross()).thenReturn(swap, (TravelMessage.TravelCross) null);

        fixture.seamless.settle(fixture.session, fixture.player);

        assertEquals(new Vec3(3.0D, 70.0D, 3.0D), listener.position);
        assertEquals(43, listener.teleport);
        verify(fixture.player.connection, never()).resetPosition();
    }

    @Test
    public void aRefusedServerStartedMoveLeavesTheClientMovesAlone() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        Listener listener = fixture.listener(41, 900);
        fixture.serverStarted();

        fixture.seamless.moved(fixture.player, false);

        assertNull(listener.position);
        assertEquals(41, listener.teleport);
    }

    @Test
    public void aClaimedCrossingLeavesTheClientMovesAlone() throws ReflectiveOperationException {
        Fixture fixture = new Fixture();
        Listener listener = fixture.listener(41, 900);
        MinecraftPortal source = floor(DimensionalPortalKind.END_SOURCE, 64);
        MinecraftPortal destination = floor(DimensionalPortalKind.END_ARRIVAL, 80);
        TravelMessage.TravelBegin begin = fixture.endArm(source, destination);
        when(fixture.registry.crossSeamless(eq(fixture.player), eq(source.getId()), eq(destination), any())).thenReturn(true);
        when(fixture.registry.travelling(fixture.player.getUUID())).thenReturn(true);
        TravelMessage.TravelCross claim = new TravelMessage.TravelCross(begin.token(), begin.generation(), 1L,
            new TravelMessage.TravelPose(11.5D, 64.7D, 11.5D, 0.0F, 40.0F), new Vec3d(11.5D, 66.32D, 11.6D), new Vec3d(11.5D, 66.32D, 11.5D));
        when(fixture.session.takeSeamlessCross()).thenReturn(claim, (TravelMessage.TravelCross) null);
        fixture.seamless.settle(fixture.session, fixture.player);
        assertTrue(fixture.seamless.crossing(fixture.player.getUUID()));

        fixture.seamless.moved(fixture.player, true);

        assertNull(listener.position);
        assertEquals(41, listener.teleport);
    }

    @Test
    public void arrivalStaysOnTheTeleportPathWithoutACrossingInFlight() {
        Fixture fixture = new Fixture();

        assertNull(fixture.seamless.arrival(fixture.session, fixture.player, UUID.randomUUID(), fixture.level,
            new TravelMessage.TravelPose(0, 64, 0, 0, 0), new Vec3d(0, 0, 0)));
    }

    @Test
    public void acceptPoseKeepsTheCrossingYawContinuous() {
        Frame frame = Frame.canonical(Face.N);
        Vec3d feet = new Vec3d(0.5D, 64.0D, -0.1D);
        TravelMessage.TravelCross cross = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L,
            new TravelMessage.TravelPose(feet.x(), feet.y(), feet.z(), 725.0F, 10.0F), new Vec3d(0.5D, 65.62D, 0.1D),
            new Vec3d(0.5D, 65.62D, -0.1D));
        PlaneCrossing crossing = new PlaneCrossing(frame.view(true), new Vec3d(0.5D, 65.0D, 0.0D), feet, new Vec3d(0.0D, 0.0D, -0.2D),
            Angles.direction(725.0F, 10.0F), true);
        Vec3d destination = new Vec3d(400.5D, 65.0D, 0.0D);
        Vec3d arrived = crossing.outPoint(frame, destination);
        Angles.Look look = ArrivalOrientation.apply(crossing, frame, TravelMessage.ArrivalRules.FRAME.orientation(), false);

        TravelMessage.TravelPose pose = MinecraftSeamlessTravel.pose(new TravelMessage.TravelPose(arrived.x(), arrived.y(), arrived.z(),
            look.yaw(), look.pitch()), cross, crossing, TravelMessage.ArrivalRules.FRAME, frame, destination, GravityFrame.WORLD);

        assertEquals(725.0F, pose.yaw(), 1.0E-3F);
        assertEquals(look.pitch(), pose.pitch(), 1.0E-3F);
        assertEquals(arrived.x(), pose.x(), 0.0D);
        assertEquals(arrived.z(), pose.z(), 0.0D);
    }

    @Test
    public void aGenericPortalNeverUsesDoorCollisionMetadataFromACoincidentDestinationId() throws Exception {
        Fixture fixture = new Fixture();
        MinecraftPortal source = mock(MinecraftPortal.class);
        MinecraftPortal destination = mock(MinecraftPortal.class);
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        when(source.getId()).thenReturn(sourceId);
        when(destination.getId()).thenReturn(destinationId);
        TravelMessage.DoorCollisionTarget target = new TravelMessage.DoorCollisionTarget(4, 70, 9, true);
        when(fixture.doors.collisionTarget(destinationId)).thenReturn(target);
        Method metadata = MinecraftSeamlessTravel.class.getDeclaredMethod("collisionTarget", MinecraftClientViewPeer.class,
            MinecraftPortal.class, MinecraftPortal.class);
        metadata.setAccessible(true);

        assertNull(metadata.invoke(fixture.seamless, fixture.peer, source, destination));
        verify(fixture.doors, never()).collisionTarget(destinationId);
        when(fixture.peer.door(sourceId)).thenReturn(source);
        assertEquals(target, metadata.invoke(fixture.seamless, fixture.peer, source, destination));
    }

    @Test
    public void aReceiveOnlyArrivalIsArmedWithoutAReturn() {
        Fixture fixture = new Fixture();
        Pair pair = fixture.pair();
        when(pair.projector().projectionDestination(pair.destination())).thenReturn(null);

        assertFalse(fixture.seamless.returnTraversable(fixture.peer, fixture.player, pair.source(), pair.destination()));
    }

    @Test
    public void aLinkedPairIsArmedWithItsReturnWhileTheTravellerMayUseIt() {
        Fixture fixture = new Fixture();
        Pair pair = fixture.pair();

        assertTrue(fixture.seamless.returnTraversable(fixture.peer, fixture.player, pair.source(), pair.destination()));
        when(fixture.registry.canDepart(fixture.player, pair.destination())).thenReturn(false);
        assertFalse(fixture.seamless.returnTraversable(fixture.peer, fixture.player, pair.source(), pair.destination()));
        when(fixture.registry.canDepart(fixture.player, pair.destination())).thenReturn(true);
        when(fixture.registry.canArrive(fixture.player, pair.source())).thenReturn(false);
        assertFalse(fixture.seamless.returnTraversable(fixture.peer, fixture.player, pair.source(), pair.destination()));
    }

    @Test
    public void aReturnIntoAnotherLevelOrThroughAClosedArrivalIsNotArmed() {
        Fixture fixture = new Fixture();
        Pair elsewhere = fixture.pair();
        when(fixture.registry.resolveLevel(elsewhere.source())).thenReturn(mock(ServerLevel.class));
        assertFalse(fixture.seamless.returnTraversable(fixture.peer, fixture.player, elsewhere.source(), elsewhere.destination()));
        Pair closed = fixture.pair();
        when(closed.destination().isOpen()).thenReturn(false);
        assertFalse(fixture.seamless.returnTraversable(fixture.peer, fixture.player, closed.source(), closed.destination()));
        Pair mirrored = fixture.pair();
        when(mirrored.source().isMirrorMode()).thenReturn(true);
        assertFalse(fixture.seamless.returnTraversable(fixture.peer, fixture.player, mirrored.source(), mirrored.destination()));
    }

    @Test
    public void doorsAreArmedWithTheirReturn() {
        Fixture fixture = new Fixture();
        MinecraftPortal door = mock(MinecraftPortal.class);
        UUID doorId = UUID.randomUUID();
        when(door.getId()).thenReturn(doorId);
        when(fixture.peer.door(doorId)).thenReturn(door);

        assertTrue(fixture.seamless.returnTraversable(fixture.peer, fixture.player, door, mock(MinecraftPortal.class)));
    }

    @Test
    public void aChangedReturnInvalidatesAnOtherwiseIdenticalArm() throws Exception {
        ArmFixture fixture = new ArmFixture();
        assertTrue(fixture.matches(fixture.target, true));
        assertFalse(fixture.matches(fixture.target, false));
    }

    @Test
    public void changedDoorCollisionMetadataInvalidatesAnOtherwiseIdenticalArm() throws Exception {
        ArmFixture fixture = new ArmFixture();
        assertTrue(fixture.matches(fixture.target));
        assertFalse(fixture.matches(new TravelMessage.DoorCollisionTarget(4, 70, 9, false)));
        assertFalse(fixture.matches(new TravelMessage.DoorCollisionTarget(5, 70, 9, true)));
        assertFalse(fixture.matches(null));
    }

    @Test
    public void spinningTheSameSourceObjectInvalidatesItsCapturedArm() throws Exception {
        ArmFixture fixture = new ArmFixture();
        Frame rotated = fixture.source.getFrame().rotateClockwise();
        when(fixture.source.getFrame()).thenReturn(rotated);
        assertFalse(fixture.matches(fixture.target));
    }

    @Test
    public void spinningTheSameDestinationObjectInvalidatesTheCapturedMapping() throws Exception {
        ArmFixture fixture = new ArmFixture();
        Frame rotated = fixture.destination.getFrame().rotateClockwise();
        when(fixture.destination.getFrame()).thenReturn(rotated);
        assertFalse(fixture.matches(fixture.target));
    }

    @Test
    public void movingEitherSamePortalObjectInvalidatesItsCapturedArm() throws Exception {
        ArmFixture source = new ArmFixture();
        Vec3d movedSource = source.source.getOrigin().add(Vec3d.UNIT_X);
        when(source.source.getOrigin()).thenReturn(movedSource);
        assertFalse(source.matches(source.target));
        ArmFixture destination = new ArmFixture();
        Vec3d movedDestination = destination.destination.getOrigin().add(Vec3d.UNIT_Y);
        when(destination.destination.getOrigin()).thenReturn(movedDestination);
        assertFalse(destination.matches(destination.target));
    }

    @Test
    public void changingCellsInPlaceInvalidatesBothEndpointSnapshots() throws Exception {
        ArmFixture source = new ArmFixture();
        ApertureCells sourceCells = source.source.getGeometry();
        sourceCells.restore(sourceCells.getArea(), List.of(new Vec3d(4, 70, 9)));
        assertFalse(source.matches(source.target));
        ArmFixture destination = new ArmFixture();
        ApertureCells destinationCells = destination.destination.getGeometry();
        destinationCells.restore(destinationCells.getArea(), List.of(new Vec3d(4, 70, 9)));
        assertFalse(destination.matches(destination.target));
    }

    @Test
    public void changingAnExactShapeWithTheSameCellsInvalidatesTheArm() throws Exception {
        ArmFixture source = new ArmFixture();
        when(source.source.getApertureShape()).thenReturn(ShapeDescriptor.parse("circle"));
        assertFalse(source.matches(source.target));
        ArmFixture destination = new ArmFixture();
        when(destination.destination.getApertureShape()).thenReturn(ShapeDescriptor.parse("circle"));
        assertFalse(destination.matches(destination.target));
    }

    @Test
    public void replacingEffectiveCellsWithTheSameRevisionStillInvalidatesTheArm() throws Exception {
        ArmFixture fixture = new ArmFixture();
        ApertureCells replacement = ArmFixture.cells();
        assertEquals(fixture.source.getGeometry().getRevision(), replacement.getRevision());
        when(fixture.source.getGeometry()).thenReturn(replacement);
        assertFalse(fixture.matches(fixture.target));
    }

    @Test
    public void equalImmutableFrameAndOriginValuesDoNotChurnTheArm() throws Exception {
        ArmFixture fixture = new ArmFixture();
        when(fixture.source.getFrame()).thenReturn(Frame.fromNormalUp(Face.N, Face.U));
        when(fixture.source.getOrigin()).thenReturn(new Vec3d(4.5D, 70.5D, 9.5D));
        assertTrue(fixture.matches(fixture.target));
    }

    private static MinecraftPortal floor(DimensionalPortalKind kind, int y) {
        List<Vec3d> cells = new ArrayList<>(9);
        for (int x = 10; x <= 12; x++) {
            for (int z = 10; z <= 12; z++) {
                cells.add(new Vec3d(x, y, z));
            }
        }
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(cells);
        UUID id = UUID.randomUUID();
        MinecraftPortal portal = new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, geometry.getApertureCenter(), "Floor",
            Frame.canonical(Face.D), true), geometry, "minecraft:overworld", Map.of("owner", id.toString(), "type", PortalType.PORTAL.name())));
        portal.setDimensionalKind(kind);
        return portal;
    }

    private static final class ArmFixture {
        private final MinecraftPortal source = mock(MinecraftPortal.class);
        private final MinecraftPortal destination = mock(MinecraftPortal.class);
        private final TravelMessage.DoorCollisionTarget target = new TravelMessage.DoorCollisionTarget(4, 70, 9, true);
        private final RemoteRoute route;
        private final Object arm;
        private final Method matches;

        private ArmFixture() throws ReflectiveOperationException {
            configure(source);
            configure(destination);
            ServerLevel level = mock(ServerLevel.class);
            route = new RemoteRoute(new RemoteRoute.Key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), level,
                new Vec3d(4, 70, 9), new RouteWindow(0, 0, 2), 3);
            TravelMessage.TravelBegin begin = mock(TravelMessage.TravelBegin.class);
            when(begin.doorCollision()).thenReturn(target);
            when(begin.returnTraversable()).thenReturn(true);
            Class<?> stateType = Class.forName(MinecraftSeamlessTravel.class.getName() + "$PortalState");
            Method capture = stateType.getDeclaredMethod("capture", MinecraftPortal.class);
            capture.setAccessible(true);
            Class<?> armType = Class.forName(MinecraftSeamlessTravel.class.getName() + "$Arm");
            Constructor<?> constructor = armType.getDeclaredConstructor(MinecraftPortal.class, MinecraftPortal.class, ServerLevel.class,
                boolean.class, long.class, int.class, TravelMessage.TravelBegin.class, stateType, stateType);
            constructor.setAccessible(true);
            arm = constructor.newInstance(source, destination, level, true, 7L, 3, begin, capture.invoke(null, source), capture.invoke(null, destination));
            matches = armType.getDeclaredMethod("matches", MinecraftPortal.class, MinecraftPortal.class, RemoteRoute.class,
                boolean.class, long.class, TravelMessage.DoorCollisionTarget.class, boolean.class);
            matches.setAccessible(true);
        }

        private boolean matches(TravelMessage.DoorCollisionTarget next) throws ReflectiveOperationException {
            return matches(next, true);
        }

        private boolean matches(TravelMessage.DoorCollisionTarget next, boolean returnTraversable) throws ReflectiveOperationException {
            return (boolean) matches.invoke(arm, source, destination, route, true, 7L, next, returnTraversable);
        }

        private static void configure(MinecraftPortal portal) {
            when(portal.getFrame()).thenReturn(Frame.canonical(Face.N));
            when(portal.getOrigin()).thenReturn(new Vec3d(4.5D, 70.5D, 9.5D));
            when(portal.getGeometry()).thenReturn(cells());
            when(portal.getApertureShape()).thenReturn(ShapeDescriptor.FULL);
        }

        private static ApertureCells cells() {
            ApertureCells cells = new ApertureCells();
            cells.setBlocks(List.of(new Vec3d(4, 70, 9), new Vec3d(5, 70, 9), new Vec3d(4, 71, 9), new Vec3d(5, 71, 9)));
            return cells;
        }
    }

    private static final class Listener {
        private Vec3 position;
        private int teleport;
        private int time;
    }

    private static final class Fixture {
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        private final MinecraftDoorService doors = mock(MinecraftDoorService.class);
        private final RemoteRoutes routes = mock(RemoteRoutes.class);
        private final MinecraftClientViewPortalAccess portals = mock(MinecraftClientViewPortalAccess.class);
        private final MinecraftClientViewPeer peer = mock(MinecraftClientViewPeer.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final ServerLevel level = mock(ServerLevel.class);
        @SuppressWarnings("unchecked")
        private final ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
        @SuppressWarnings("unchecked")
        private final ClientViewTravel<MinecraftClientViewPeer> session = mock(ClientViewTravel.class);
        private final MinecraftSeamlessTravel seamless = new MinecraftSeamlessTravel(runtime, portals);

        private Fixture() {
            UUID playerId = UUID.randomUUID();
            player.connection = mock(ServerGamePacketListenerImpl.class, withSettings().extraInterfaces(SeamlessListenerAccess.class));
            when(runtime.portals()).thenReturn(registry);
            when(registry.observedVelocity(player)).thenReturn(new Vec3d(0.0D, 0.0D, 0.0D));
            when(runtime.doors()).thenReturn(doors);
            when(runtime.remoteRoutes()).thenReturn(routes);
            when(runtime.leases()).thenReturn(leases);
            when(runtime.server()).thenReturn(server);
            when(server.getTickCount()).thenReturn(20);
            when(player.getUUID()).thenReturn(playerId);
            when(player.getX()).thenReturn(4.0D);
            when(player.getY()).thenReturn(64.0D);
            when(player.getZ()).thenReturn(-2.0D);
            when(player.getYRot()).thenReturn(30.0F);
            when(player.getXRot()).thenReturn(5.0F);
            when(player.level()).thenReturn(level);
            when(session.player()).thenReturn(peer);
            when(session.playerId()).thenReturn(playerId);
            when(session.seamlessSelected()).thenReturn(true);
            when(routes.routes(playerId)).thenReturn(List.of());
            PlayerList list = mock(PlayerList.class);
            when(server.getPlayerList()).thenReturn(list);
            when(list.getViewDistance()).thenReturn(10);
        }

        private Listener listener(int teleport, int tick) {
            Listener listener = new Listener();
            listener.teleport = teleport;
            SeamlessListenerAccess access = (SeamlessListenerAccess) player.connection;
            when(access.wormholesAwaitingPosition()).thenAnswer(call -> listener.position);
            doAnswer(call -> {
                listener.position = call.getArgument(0);
                return null;
            }).when(access).wormholesAwaitingPosition(any());
            when(access.wormholesAwaitingTeleport()).thenAnswer(call -> listener.teleport);
            doAnswer(call -> {
                listener.teleport = call.getArgument(0);
                return null;
            }).when(access).wormholesAwaitingTeleport(anyInt());
            doAnswer(call -> {
                listener.time = call.getArgument(0);
                return null;
            }).when(access).wormholesAwaitingTeleportTime(anyInt());
            when(access.wormholesTickCount()).thenReturn(tick);
            when(player.position()).thenReturn(new Vec3(11.5D, 80.0D, 11.5D));
            return listener;
        }

        private TravelMessage.TravelBegin serverStarted() throws ReflectiveOperationException {
            MinecraftPortal source = floor(DimensionalPortalKind.NONE, 64);
            TravelMessage.TravelBegin begin = mock(TravelMessage.TravelBegin.class);
            when(begin.token()).thenReturn(UUID.randomUUID());
            when(begin.generation()).thenReturn(4L);
            arm(source, floor(DimensionalPortalKind.NONE, 80), begin);
            assertTrue(seamless.defer(player, source.getId()));
            when(server.getTickCount()).thenReturn(23);
            assertFalse(seamless.defer(player, source.getId()));
            assertTrue(seamless.crossing(player.getUUID()));
            return begin;
        }

        private static TravelMessage.TravelCross swap(TravelMessage.TravelBegin begin) {
            return new TravelMessage.TravelCross(begin.token(), begin.generation(), 1L, new TravelMessage.TravelPose(11.5D, 64.0D, 9.0D, 0.0F, 0.0F),
                new Vec3d(11.5D, 65.62D, 9.0D), new Vec3d(11.5D, 65.62D, 9.0D));
        }

        private TravelMessage.TravelBegin endArm(MinecraftPortal source, MinecraftPortal destination) throws ReflectiveOperationException {
            ApertureDescriptor geometry = new ApertureDescriptor(10, 64, 10, Face.D.ordinal(), false, 0, false, 3, 3, new long[] {511L},
                ShapeDescriptor.FULL, 0.0F, 0.0F, 1.0F, 64, 0, 0, 0, 0, 0, 0, ApertureKind.VANILLA_REPLACEMENT, 0.0D, 0, 1L, List.of());
            TravelMessage.TravelBegin begin = mock(TravelMessage.TravelBegin.class);
            when(begin.token()).thenReturn(UUID.randomUUID());
            when(begin.generation()).thenReturn(4L);
            when(begin.sourceWorld()).thenReturn("minecraft:overworld");
            when(begin.sourceGeometry()).thenReturn(geometry);
            when(begin.scale()).thenReturn(1.0F);
            when(begin.world()).thenReturn(new TravelMessage.TravelWorld(EndPortalContact.END, EndPortalContact.END, 7L, false, false, 0, 0, 256));
            arm(source, destination, begin);
            MinecraftProjectorPortalAccess projector = mock(MinecraftProjectorPortalAccess.class);
            when(peer.portals()).thenReturn(projector);
            when(projector.projectionDestination(source)).thenReturn(destination);
            when(projector.routeIdentity(source)).thenReturn(7L);
            when(portals.travelGeometry(peer, source, false)).thenReturn(geometry);
            RemoteRoute route = new RemoteRoute(new RemoteRoute.Key(player.getUUID(), source.getId(), destination.getId()), level,
                new Vec3d(11.5D, 80.5D, 11.5D), new RouteWindow(2, 2, 2), 3);
            route.stream().markDelivered(ChunkPos.pack(0, 0));
            when(routes.route(player.getUUID(), source.getId())).thenReturn(route);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(registry.resolveLevel(source)).thenReturn(level);
            when(registry.get(source.getId())).thenReturn(source);
            when(registry.canDepart(player, source)).thenReturn(true);
            when(registry.canArrive(player, destination)).thenReturn(true);
            when(registry.resolveDestination(eq(source), eq(player), any())).thenReturn(new NetworkMember(destination.getId(), "", "", 0, null));
            when(player.getX()).thenReturn(11.5D);
            when(player.getY()).thenReturn(64.8125D);
            when(player.getZ()).thenReturn(11.5D);
            when(player.getEyeHeight()).thenReturn(1.62F);
            when(player.getBoundingBox()).thenReturn(new AABB(11.2D, 64.8125D, 11.2D, 11.8D, 66.6125D, 11.8D));
            worldGravity(player);
            return begin;
        }

        private void arm(MinecraftPortal source, MinecraftPortal destination) throws ReflectiveOperationException {
            arm(source, destination, mock(TravelMessage.TravelBegin.class));
        }

        @SuppressWarnings("unchecked")
        private void arm(MinecraftPortal source, MinecraftPortal destination, TravelMessage.TravelBegin begin) throws ReflectiveOperationException {
            Class<?> stateType = Class.forName(MinecraftSeamlessTravel.class.getName() + "$PortalState");
            Method capture = stateType.getDeclaredMethod("capture", MinecraftPortal.class);
            capture.setAccessible(true);
            Class<?> armType = Class.forName(MinecraftSeamlessTravel.class.getName() + "$Arm");
            Constructor<?> armConstructor = armType.getDeclaredConstructor(MinecraftPortal.class, MinecraftPortal.class, ServerLevel.class,
                boolean.class, long.class, int.class, TravelMessage.TravelBegin.class, stateType, stateType);
            armConstructor.setAccessible(true);
            Object arm = armConstructor.newInstance(source, destination, level, false, 7L, 3, begin,
                capture.invoke(null, source), capture.invoke(null, destination));
            Class<?> travelerType = Class.forName(MinecraftSeamlessTravel.class.getName() + "$Traveler");
            Constructor<?> travelerConstructor = travelerType.getDeclaredConstructor();
            travelerConstructor.setAccessible(true);
            Object traveler = travelerConstructor.newInstance();
            Field arms = travelerType.getDeclaredField("arms");
            arms.setAccessible(true);
            ((Map<UUID, Object>) arms.get(traveler)).put(source.getId(), arm);
            Field travelers = MinecraftSeamlessTravel.class.getDeclaredField("travelers");
            travelers.setAccessible(true);
            ((Map<UUID, Object>) travelers.get(seamless)).put(player.getUUID(), traveler);
        }

        private Pair pair() {
            MinecraftProjectorPortalAccess projector = mock(MinecraftProjectorPortalAccess.class);
            when(peer.portals()).thenReturn(projector);
            MinecraftPortal source = portal();
            MinecraftPortal destination = portal();
            when(projector.projectionDestination(destination)).thenReturn(source);
            when(registry.resolveLevel(source)).thenReturn(level);
            when(registry.canDepart(player, destination)).thenReturn(true);
            when(registry.canArrive(player, source)).thenReturn(true);
            return new Pair(projector, source, destination);
        }

        private MinecraftPortal portal() {
            MinecraftPortal portal = mock(MinecraftPortal.class);
            UUID id = UUID.randomUUID();
            when(portal.getId()).thenReturn(id);
            when(portal.isOpen()).thenReturn(true);
            when(registry.get(id)).thenReturn(portal);
            return portal;
        }
    }

    private record Pair(MinecraftProjectorPortalAccess projector, MinecraftPortal source, MinecraftPortal destination) {
    }
}
