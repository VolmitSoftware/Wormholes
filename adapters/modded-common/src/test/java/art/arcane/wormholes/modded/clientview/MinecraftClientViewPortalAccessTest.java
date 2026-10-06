package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.modded.MinecraftDoorService;
import art.arcane.wormholes.modded.MinecraftLocalEntityView;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalProjector;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftProjectionService;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.rtp.MinecraftRtpRuntime;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.PlateCaptureJob;
import art.arcane.optics.plate.ViewPlateCache;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

public class MinecraftClientViewPortalAccessTest extends MinecraftTestBase {
    @Test
    public void nativeMeshBlackoutChangesDoNotChangeGeometryOrLighting() {
        Fixture fixture = fixture(PortalType.PORTAL);
        fixture.peer().meshDepth(208);
        when(fixture.source().isBlackoutBackground()).thenReturn(true);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        SessionPalette palette = new SessionPalette();
        ApertureDescriptor geometry = portals.geometry(fixture.peer(), fixture.source().getId(), palette);
        long revision = portals.geometryRevision(fixture.peer(), fixture.source().getId());
        assertEquals(ApertureDescriptor.BLACKOUT_OFF, geometry.blackoutPolicy());
        assertEquals(0, geometry.blackoutState());
        assertEquals(ProjectedBlockClaim.LightingPolicy.SOURCE.ordinal(), geometry.lightingPolicy());
        when(fixture.source().isBlackoutBackground()).thenReturn(false);
        when(fixture.source().getBlackoutColor()).thenReturn(BlackoutColor.WHITE);
        assertEquals(revision, portals.geometryRevision(fixture.peer(), fixture.source().getId()));
        assertEquals(geometry, portals.geometry(fixture.peer(), fixture.source().getId(), palette));
    }

    @Test
    public void nativeMirrorUsesItsOwnDestinationAndNativeLightingWithoutLink() {
        Fixture fixture = fixture(PortalType.PORTAL);
        fixture.peer().meshDepth(208);
        when(fixture.source().isMirrorMode()).thenReturn(true);
        when(fixture.source().isBlackoutBackground()).thenReturn(true);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        ApertureDescriptor geometry = portals.geometry(fixture.peer(), fixture.source().getId(), new SessionPalette());
        assertTrue(geometry.mirror());
        assertEquals(ApertureDescriptor.BLACKOUT_OFF, geometry.blackoutPolicy());
        assertNotNull(portals.target(fixture.peer(), fixture.source(), true));
        assertTrue(portals.target(fixture.peer(), fixture.source(), true).mirrorMode());
        assertEquals(fixture.source().getOrigin().x(), portals.target(fixture.peer(), fixture.source(), true).originX(), 0);
    }

    @Test
    public void rotatingEitherLinkedFrameChangesTheNativeStreamIdentity() {
        Fixture fixture = fixture(PortalType.PORTAL);
        fixture.peer().meshDepth(208);
        MinecraftPortal destination = fixture.access().projectionDestination(fixture.source());
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        SessionPalette palette = new SessionPalette();
        ApertureDescriptor before = portals.geometry(fixture.peer(), fixture.source().getId(), palette);
        long revision = portals.geometryRevision(fixture.peer(), fixture.source().getId());
        Frame rotated = destination.getFrame().rotateClockwise();
        when(destination.getFrame()).thenReturn(rotated);
        ApertureDescriptor destinationRotated = portals.geometry(fixture.peer(), fixture.source().getId(), palette);
        assertNotEquals(before.targetIdentity(), destinationRotated.targetIdentity());
        assertNotEquals(revision, portals.geometryRevision(fixture.peer(), fixture.source().getId()));
        revision = portals.geometryRevision(fixture.peer(), fixture.source().getId());
        rotated = fixture.source().getFrame().rotateCounterClockwise();
        when(fixture.source().getFrame()).thenReturn(rotated);
        assertNotEquals(destinationRotated.targetIdentity(), portals.geometry(fixture.peer(), fixture.source().getId(), palette).targetIdentity());
        assertNotEquals(revision, portals.geometryRevision(fixture.peer(), fixture.source().getId()));
    }

    @Test
    public void wallMirrorRotationUsesRawMeshGeometryAndClampedPacketGeometry() {
        Fixture fixture = fixture(PortalType.PORTAL);
        when(fixture.source().isMirrorMode()).thenReturn(true);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        for (QuarterTurn rotation : List.of(QuarterTurn.DEGREES_90, QuarterTurn.DEGREES_270)) {
            when(fixture.source().getMirrorRotation()).thenReturn(rotation);
            int ordinary = rotation.coherentFor(fixture.source().getFrame()).getQuarterTurns();
            when(fixture.access().mirrorQuarterTurns(fixture.source())).thenReturn(ordinary);
            fixture.peer().meshDepth(208);
            ApertureDescriptor geometry = portals.geometry(fixture.peer(), fixture.source().getId(), new SessionPalette());
            assertEquals(rotation.getQuarterTurns(), geometry.mirrorQuarterTurns());
            assertEquals(rotation.getQuarterTurns(), portals.target(fixture.peer(), fixture.source(), true).mirrorQuarterTurns());
            fixture.peer().meshDepth(0);
            assertEquals(ordinary, portals.geometry(fixture.peer(), fixture.source().getId(), new SessionPalette()).mirrorQuarterTurns());
            assertEquals(ordinary, portals.target(fixture.peer(), fixture.source(), true).mirrorQuarterTurns());
        }
    }

    @Test
    public void projectedDoorUsesNativeMeshAndRetargetsItsGeometryAndCapture() {
        Fixture fixture = fixture(PortalType.PORTAL);
        MinecraftDoorService doors = mock(MinecraftDoorService.class);
        when(fixture.runtime().doors()).thenReturn(doors);
        fixture.runtime().configuration().settings().getDoors().projectionEnabled = true;
        when(fixture.player().level().dimension()).thenReturn(Level.OVERWORLD);
        MinecraftProjectorPortalAccess access = new MinecraftProjectorPortalAccess(fixture.runtime());
        fixture.peer().attach(fixture.player(), access);
        DoorItemIdentity identity = DoorItemIdentity.newPersonal();
        PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 2, 64, 3),
            identity, DoorOpenState.OPEN, DoorProjectionState.INHERIT);
        MinecraftDoorService.DoorView door = new MinecraftDoorService.DoorView(endpoint, fixture.player().level(),
            new DoorwayPlane(2, 64, 3, Face.N), true);
        when(fixture.runtime().projections().projectableDoors()).thenReturn(List.of(door));
        UUID destination = UUID.randomUUID();
        ServerLevel destinationWorld = fixture.player().level();
        when(doors.projectionDestination(door, fixture.player().getUUID())).thenReturn(Optional.of(
            new MinecraftDoorService.ProjectionDestination(destination, destinationWorld, new Vec3d(20.5, 65, 30.5),
                Frame.canonical(Face.S))));
        when(fixture.runtime().projections().attendable(eq(fixture.player()), any(), eq(access))).thenAnswer(call -> {
            MinecraftPortal source = call.getArgument(1);
            return access.eligible(source) && access.hasDestination(source);
        });
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        List<UUID> interest = new ArrayList<>();
        portals.interested(fixture.peer(), interest);
        assertEquals(List.of(identity.itemId()), interest);
        ApertureDescriptor geometry = portals.geometry(fixture.peer(), identity.itemId(), new SessionPalette());
        assertNotNull(geometry);
        assertEquals(ApertureDescriptor.KIND_DOOR, geometry.kind());
        assertEquals(2, geometry.openCellCount());
        assertEquals(ApertureDescriptor.BLACKOUT_OFF, geometry.blackoutPolicy());
        assertNotEquals(0L, geometry.targetIdentity());
        assertFalse(portals.refused(fixture.peer(), identity.itemId()));
        long revision = portals.geometryRevision(fixture.peer(), identity.itemId());
        Object scene = portals.scene().sceneKey(fixture.peer(), identity.itemId());
        assertNotNull(scene);
        BlockBox clip = new BlockBox(0, 64, 0, 16, 16, 16);
        assertNull(portals.meshSection(fixture.peer(), identity.itemId(), clip, 208));
        assertEquals(1, fixture.scheduled().size());
        long routeIdentity = fixture.scheduled().getFirst().key().targetIdentity();
        assertNotEquals(0L, routeIdentity);

        when(doors.projectionDestination(door, fixture.player().getUUID())).thenReturn(Optional.of(
            new MinecraftDoorService.ProjectionDestination(destination, destinationWorld, new Vec3d(120.5, 65, 30.5),
                Frame.canonical(Face.S))));
        portals.interested(fixture.peer(), new ArrayList<UUID>());
        assertNotEquals(revision, portals.geometryRevision(fixture.peer(), identity.itemId()));
        assertNotEquals(geometry.targetIdentity(), portals.geometry(fixture.peer(), identity.itemId(), new SessionPalette()).targetIdentity());
        assertNotEquals(scene, portals.scene().sceneKey(fixture.peer(), identity.itemId()));
        assertNull(portals.meshSection(fixture.peer(), identity.itemId(), clip, 208));
        assertEquals(2, fixture.scheduled().size());
        assertNotEquals(routeIdentity, fixture.scheduled().getLast().key().targetIdentity());

        MinecraftDoorService.DoorView disabled = new MinecraftDoorService.DoorView(endpoint.withProjection(DoorProjectionState.OFF),
            fixture.player().level(), door.plane(), true);
        when(fixture.runtime().projections().projectableDoors()).thenReturn(List.of(disabled));
        interest.clear();
        portals.interested(fixture.peer(), interest);
        assertTrue(interest.isEmpty());
        assertNull(portals.geometry(fixture.peer(), identity.itemId(), new SessionPalette()));
        assertNull(portals.meshSection(fixture.peer(), identity.itemId(), clip, 208));
        assertNull(portals.scene().sceneKey(fixture.peer(), identity.itemId()));

        when(fixture.runtime().projections().projectableDoors()).thenReturn(List.of(door));
        fixture.runtime().configuration().settings().getDoors().projectionEnabled = false;
        portals.interested(fixture.peer(), interest);
        assertTrue(interest.isEmpty());
    }

    @Test
    public void meshDistanceUsesTheClientsRequestedDistanceAndEye() {
        Fixture fixture = fixture(PortalType.PORTAL);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        assertEquals(128, portals.meshDistanceBlocks(fixture.peer()));
        when(fixture.player().requestedViewDistance()).thenReturn(1);
        assertEquals(32, portals.meshDistanceBlocks(fixture.peer()));
        when(fixture.player().requestedViewDistance()).thenReturn(64);
        assertEquals(512, portals.meshDistanceBlocks(fixture.peer()));
        when(fixture.player().getEyePosition()).thenReturn(new Vec3(-35.5D, 75.25D, -0.25D));
        assertEquals(new Vec3d(-35.5D, 75.25D, -0.25D), portals.meshEye(fixture.peer()));
    }

    @Test
    public void meshCaptureIsBoundedAndDoesNotShareTheVanillaPlateOrAdjacentSections() {
        Fixture fixture = fixture(PortalType.PORTAL);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        BlockBox first = new BlockBox(-16, 64, -16, 16, 16, 16);
        assertNull(portals.meshSection(fixture.peer(), fixture.source().getId(), first, 512));
        assertEquals(1, fixture.scheduled().size());
        ViewPlateBuilder.Job<BlockState, ServerLevel> firstJob = fixture.scheduled().get(0);
        assertTrue(firstJob instanceof PlateCaptureJob<?, ?, ?>);
        assertTrue(((PlateCaptureJob<?, ?, ?>) firstJob).pendingChunks() <= 9);
        assertTrue(firstJob.predictedBytes() < 32_768L);
        assertNull(portals.meshSection(fixture.peer(), fixture.source().getId(), first, 512));
        assertEquals(1, fixture.scheduled().size());
        assertNull(portals.meshSection(fixture.peer(), fixture.source().getId(), new BlockBox(0, 64, -16, 16, 16, 16), 512));
        assertEquals(2, fixture.scheduled().size());
        assertNotEquals(firstJob.key(), fixture.scheduled().get(1).key());
        assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
        assertEquals(3, fixture.scheduled().size());
        assertNotEquals(firstJob.key(), fixture.scheduled().get(2).key());
    }

    @Test
    public void meshCapturePreservesRtpRouteIdentity() {
        Fixture fixture = fixture(PortalType.RTP);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        BlockBox clip = new BlockBox(0, 64, -16, 16, 16, 16);
        when(fixture.rtp().plateIdentity(any(), any())).thenReturn(91L);
        assertNull(portals.meshSection(fixture.peer(), fixture.source().getId(), clip, 128));
        assertEquals(91L, fixture.scheduled().get(0).key().targetIdentity());
        when(fixture.rtp().plateIdentity(any(), any())).thenReturn(92L);
        assertNull(portals.meshSection(fixture.peer(), fixture.source().getId(), clip, 128));
        assertEquals(2, fixture.scheduled().size());
        assertEquals(92L, fixture.scheduled().get(1).key().targetIdentity());
    }

    @Test
    public void clientViewAndVanillaObserversOfOneSideShareOnePlate() {
        Fixture fixture = fixture(PortalType.PORTAL);
        try (MinecraftPortalProjector projector = new MinecraftPortalProjector(fixture.runtime(),
            new MinecraftPortalProjector.Context(fixture.vanilla(), fixture.source(), ignored -> fixture.view(), fixture.access(), fixture.cache()))) {
            assertEquals(MinecraftPortalProjector.Result.READY, projector.update(1L, Long.MAX_VALUE));
        }
        assertEquals(1, fixture.scheduled().size());
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
        assertFalse(portals.refused(fixture.peer(), fixture.source().getId()));
        assertEquals(1, fixture.scheduled().size());
        assertEquals(fixture.source().getId(), fixture.scheduled().get(0).key().portalId());
        assertTrue(fixture.scheduled().get(0).key().frontSide());
    }

    @Test
    public void rtpObserversShareOnlyTheirOwnRoute() {
        Fixture fixture = fixture(PortalType.RTP);
        when(fixture.rtp().plateIdentity(any(), any())).thenReturn(0L);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
        assertEquals(0, fixture.scheduled().size());
        assertFalse(portals.refused(fixture.peer(), fixture.source().getId()));
        when(fixture.rtp().plateIdentity(any(), any())).thenReturn(91L);
        assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
        assertEquals(1, fixture.scheduled().size());
        assertEquals(91L, fixture.scheduled().get(0).key().targetIdentity());
        ApertureDescriptor geometry = portals.geometry(fixture.peer(), fixture.source().getId(), new SessionPalette());
        assertEquals(ApertureDescriptor.KIND_RTP, geometry.kind());
        assertNotEquals(0L, geometry.targetIdentity());
        assertNotEquals(91L, geometry.targetIdentity());
        boolean gate = FidelitySettings.rtpPlates;
        FidelitySettings.rtpPlates = false;
        try {
            assertTrue(portals.refused(fixture.peer(), fixture.source().getId()));
        } finally {
            FidelitySettings.rtpPlates = gate;
        }
    }

    @Test
    public void remoteTunnelsAndDisabledPlatesStayOnTheVanillaPath() {
        Fixture fixture = fixture(PortalType.PORTAL);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        assertTrue(MinecraftClientViewPortalAccess.ownable(fixture.source()));
        when(fixture.source().getTunnelType()).thenReturn("UNIVERSAL");
        assertFalse(MinecraftClientViewPortalAccess.ownable(fixture.source()));
        assertTrue(portals.refused(fixture.peer(), fixture.source().getId()));
        assertNull(portals.geometry(fixture.peer(), fixture.source().getId(), new SessionPalette()));
        portals.frame(List.of(fixture.source()));
        List<UUID> interest = new ArrayList<>();
        portals.interested(fixture.peer(), interest);
        assertTrue(interest.isEmpty());
        when(fixture.source().isMirrorMode()).thenReturn(true);
        assertTrue(MinecraftClientViewPortalAccess.ownable(fixture.source()));
        when(fixture.source().isMirrorMode()).thenReturn(false);
        when(fixture.source().getTunnelType()).thenReturn("LOCAL");
        boolean shared = FidelitySettings.sharedPlate;
        FidelitySettings.sharedPlate = false;
        try {
            assertTrue(portals.refused(fixture.peer(), fixture.source().getId()));
            assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
        } finally {
            FidelitySettings.sharedPlate = shared;
        }
    }

    @Test
    public void geometryDescribesTheApertureAndFollowsTheObserverSide() {
        Fixture fixture = fixture(PortalType.PORTAL);
        when(fixture.source().isBlackoutBackground()).thenReturn(true);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        SessionPalette palette = new SessionPalette();
        ApertureDescriptor geometry = portals.geometry(fixture.peer(), fixture.source().getId(), palette);
        assertNotNull(geometry);
        assertTrue(geometry.valid());
        assertEquals(3, geometry.apertureWidth());
        assertEquals(3, geometry.apertureHeight());
        assertEquals(9, geometry.openCellCount());
        assertTrue(geometry.frontSide());
        assertEquals(8, geometry.depthBlocks());
        assertEquals(ApertureDescriptor.KIND_FRAME, geometry.kind());
        assertEquals(0L, geometry.targetIdentity());
        assertEquals(ApertureDescriptor.BLACKOUT_SHELL, geometry.blackoutPolicy());
        assertEquals(BlockStateParser.serialize(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState()), palette.state(geometry.blackoutState()));
        long front = portals.geometryRevision(fixture.peer(), fixture.source().getId());
        assertEquals(front, portals.geometryRevision(fixture.peer(), fixture.source().getId()));
        when(fixture.player().getEyePosition()).thenReturn(new Vec3(1.0D, 65.0D, -4.0D));
        assertNotEquals(front, portals.geometryRevision(fixture.peer(), fixture.source().getId()));
        assertFalse(portals.geometry(fixture.peer(), fixture.source().getId(), palette).frontSide());
        ServerLevel elsewhere = mock(ServerLevel.class);
        when(fixture.player().level()).thenReturn(elsewhere);
        assertNull(portals.geometry(fixture.peer(), fixture.source().getId(), palette));
        assertNull(portals.plate(fixture.peer(), fixture.source().getId(), false));
    }

    @Test
    public void sceneMarksOnlyTheObserversOwnProjectedEntity() {
        Fixture fixture = fixture(PortalType.PORTAL);
        MinecraftLocalEntityView entities = mock(MinecraftLocalEntityView.class);
        when(fixture.runtime().projections().scene(any(), any(), anyDouble())).thenReturn(entities);
        EntitySnapshot self = stand(fixture.peer().id(), 11.5D);
        EntitySnapshot other = stand(UUID.randomUUID(), 11.0D);
        when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(self, other));
        MinecraftClientViewScene scene = new MinecraftClientViewPortalAccess(fixture.runtime()).scene();
        List<EntitySnapshot> projected = scene.capture(fixture.peer(), fixture.source().getId(), 1L);
        assertEquals(2, projected.size());
        int observers = 0;
        for (EntitySnapshot visual : projected) {
            assertNotEquals(fixture.peer().id(), visual.id());
            observers += scene.isObserver(fixture.peer(), visual) ? 1 : 0;
        }
        assertEquals(1, observers);
    }

    @Test
    public void meshEntityDepthExtendsLegacyVolumeAndSeparatesSceneCaches() {
        Fixture fixture = fixture(PortalType.PORTAL);
        fixture.runtime().configuration().settings().getRender().entitySpoofRange = 128;
        MinecraftLocalEntityView entities = mock(MinecraftLocalEntityView.class);
        when(fixture.runtime().projections().scene(any(), any(), anyDouble())).thenReturn(entities);
        EntitySnapshot stand = EntitySnapshot.full(UUID.randomUUID(), "minecraft:armor_stand", 11.5D, 65.0D, -110.0D, 1.975D,
            0.0D, 0.0D, 1.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
        when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(stand));
        MinecraftClientViewScene scene = new MinecraftClientViewPortalAccess(fixture.runtime()).scene();
        Object legacyKey = scene.sceneKey(fixture.peer(), fixture.source().getId());
        assertTrue(scene.capture(fixture.peer(), fixture.source().getId(), 1L).isEmpty());
        verify(entities).getEntities(anyDouble(), anyDouble(), anyDouble(), eq(8.0D));
        fixture.peer().meshDepth(160);
        assertNotEquals(legacyKey, scene.sceneKey(fixture.peer(), fixture.source().getId()));
        assertEquals(1, scene.capture(fixture.peer(), fixture.source().getId(), 2L).size());
        verify(entities).getEntities(anyDouble(), anyDouble(), anyDouble(), eq(128.0D));
    }

    @Test
    public void nativeBranchesResolveMirrorsInTheLinkedWorldWithIndependentEyes() {
        Fixture fixture = fixture(PortalType.PORTAL);
        fixture.peer().meshDepth(128);
        MinecraftPortal destination = fixture.access().projectionDestination(fixture.source());
        ServerLevel remoteWorld = mock(ServerLevel.class);
        MinecraftProjectionWorldView remoteView = mock(MinecraftProjectionWorldView.class);
        when(remoteView.getWorld()).thenReturn(remoteWorld);
        when(fixture.access().world(destination)).thenReturn(remoteWorld);
        when(fixture.runtime().projections().view(remoteWorld)).thenReturn(remoteView);
        MinecraftPortal mirror = portal(12);
        mirror.getGeometry().setArea(new Box(12, 14.999D, 64, 66.999D, -4, -3.001D));
        when(mirror.getOrigin()).thenReturn(new Vec3d(13.5D, 65.5D, -3.5D));
        when(mirror.isMirrorMode()).thenReturn(true);
        when(fixture.runtime().portals().get(mirror.getId())).thenReturn(mirror);
        when(fixture.access().world(mirror)).thenReturn(remoteWorld);
        when(fixture.access().eligible(mirror)).thenReturn(true);
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        portals.frame(List.of(fixture.source(), mirror));
        UUID root = fixture.source().getId();
        UUID branch = UUID.randomUUID();
        UUID secondBranch = UUID.randomUUID();
        portals.prepareNested(fixture.peer(), root, null, root);
        ApertureDescriptor rootGeometry = portals.geometry(fixture.peer(), root, new SessionPalette()).withDepth(128);
        ArrayList<UUID> discovered = new ArrayList<>();
        portals.nested(fixture.peer(), root, rootGeometry, discovered);
        assertEquals(List.of(mirror.getId()), discovered);
        Vec3d parentEye = portals.nestedEye(fixture.peer(), root);
        assertNotEquals(portals.meshEye(fixture.peer()), parentEye);
        portals.prepareNested(fixture.peer(), branch, root, mirror.getId());
        assertEquals(parentEye, fixture.peer().nestedContext(branch).sourceEye());
        assertEquals(mirror, portals.portal(fixture.peer(), branch));
        assertNotNull(portals.nestedGeometry(fixture.peer(), root, mirror.getId(), new SessionPalette()));
        Vec3d firstEye = portals.nestedEye(fixture.peer(), branch);
        portals.prepareNested(fixture.peer(), secondBranch, branch, mirror.getId());
        assertEquals(firstEye, fixture.peer().nestedContext(secondBranch).sourceEye());
        assertNotEquals(firstEye, portals.nestedEye(fixture.peer(), secondBranch));
        portals.releaseNested(fixture.peer(), branch);
        assertNull(fixture.peer().nestedContext(branch));
        assertNotNull(fixture.peer().nestedContext(secondBranch));
        fixture.peer().attach(fixture.player(), fixture.access());
        assertNull(fixture.peer().nestedContext(secondBranch));
        fixture.peer().meshDepth(0);
        assertNull(portals.nestedGeometry(fixture.peer(), root, mirror.getId(), new SessionPalette()));
    }

    @Test
    public void packetMirrorDiscoversLinkedAperturesUsingTheDestinationTransform() {
        Fixture fixture = fixture(PortalType.PORTAL);
        when(fixture.source().isMirrorMode()).thenReturn(true);
        MinecraftPortal front = portal(0.0D);
        MinecraftPortal back = portal(0.0D);
        front.getGeometry().setArea(new Box(0, 2.999D, 64, 66.999D, 4, 4.999D));
        back.getGeometry().setArea(new Box(0, 2.999D, 64, 66.999D, -4, -3.001D));
        for (MinecraftPortal child : List.of(front, back)) {
            when(fixture.access().eligible(child)).thenReturn(true);
            when(fixture.access().hasDestination(child)).thenReturn(true);
            ServerLevel world = fixture.player().level();
            when(fixture.access().world(child)).thenReturn(world);
        }
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        portals.frame(List.of(fixture.source(), front, back));
        for (int turns : new int[] {0, 2}) {
            when(fixture.access().mirrorQuarterTurns(fixture.source())).thenReturn(turns);
            for (boolean frontSide : new boolean[] {true, false}) {
                when(fixture.player().getEyePosition()).thenReturn(new Vec3(1.0D, 65.0D, frontSide ? 4.0D : -4.0D));
                ApertureDescriptor geometry = portals.geometry(fixture.peer(), fixture.source().getId(), new SessionPalette());
                ArrayList<UUID> discovered = new ArrayList<>();
                portals.nested(fixture.peer(), fixture.source().getId(), geometry, discovered);
                assertEquals(List.of((frontSide ? front : back).getId()), discovered);
            }
        }
    }

    @Test
    public void nativeReturnDoorDiscoversFramesMirrorsAndDoorsInItsDestinationWorld() {
        Fixture fixture = fixture(PortalType.PORTAL);
        fixture.peer().meshDepth(128);
        ServerLevel pocket = fixture.player().level();
        ServerLevel overworld = mock(ServerLevel.class);
        when(pocket.dimension()).thenReturn(Level.NETHER);
        when(overworld.dimension()).thenReturn(Level.OVERWORLD);
        MinecraftProjectionWorldView destinationView = mock(MinecraftProjectionWorldView.class);
        when(destinationView.getWorld()).thenReturn(overworld);
        when(fixture.runtime().projections().view(overworld)).thenReturn(destinationView);
        when(fixture.runtime().portals().resolveLevel(any())).thenAnswer(call -> {
            MinecraftPortal portal = call.getArgument(0);
            return "minecraft:the_nether".equals(portal.getWorldKey()) ? pocket : overworld;
        });
        MinecraftDoorService doors = mock(MinecraftDoorService.class);
        when(fixture.runtime().doors()).thenReturn(doors);
        MinecraftProjectorPortalAccess access = new MinecraftProjectorPortalAccess(fixture.runtime());
        fixture.peer().attach(fixture.player(), access);
        DoorItemIdentity identity = DoorItemIdentity.newReturn(UUID.randomUUID());
        PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:the_nether", 2, 64, 3),
            identity, DoorOpenState.OPEN, DoorProjectionState.OFF);
        MinecraftDoorService.DoorView rootDoor = new MinecraftDoorService.DoorView(endpoint, pocket,
            new DoorwayPlane(2, 64, 3, Face.N), true);
        when(doors.projectionDestination(rootDoor, fixture.player().getUUID())).thenReturn(Optional.of(
            new MinecraftDoorService.ProjectionDestination(UUID.randomUUID(), overworld, new Vec3d(20.5D, 65.0D, 30.5D),
                Frame.canonical(Face.S))));
        when(fixture.runtime().projections().projectableDoors()).thenReturn(List.of(rootDoor));
        fixture.runtime().configuration().settings().getDoors().projectionEnabled = false;
        when(fixture.runtime().projections().attendable(eq(fixture.player()), any(), eq(access))).thenAnswer(call -> {
            MinecraftPortal portal = call.getArgument(1);
            return access.world(portal) == pocket && access.eligible(portal) && access.hasDestination(portal);
        });
        MinecraftClientViewPortalAccess portals = new MinecraftClientViewPortalAccess(fixture.runtime());
        List<UUID> interest = new ArrayList<>();
        portals.interested(fixture.peer(), interest);
        assertEquals(List.of(identity.itemId()), interest);
        UUID root = identity.itemId();
        portals.prepareNested(fixture.peer(), root, null, root);
        ApertureDescriptor rootGeometry = portals.geometry(fixture.peer(), root, new SessionPalette()).withDepth(128);
        assertEquals(ApertureDescriptor.KIND_DOOR, rootGeometry.kind());
        assertEquals(fixture.runtime().configuration().settings().getProjection().recursivePortalDepth, rootGeometry.recursionDepth());
        Vec3d childOrigin = fixture.peer().nestedContext(root).transform().inverse().point(new Vec3d(2.5D, 65.0D, -4.5D));
        MinecraftPortal mirror = portal(childOrigin.x() - 1.5D);
        MinecraftPortal frame = portal(childOrigin.x() + 2.5D);
        MinecraftPortal linked = portal(childOrigin.x() + 12.5D);
        for (MinecraftPortal child : List.of(mirror, frame)) {
            double x = child.getOrigin().x();
            double y = Math.floor(childOrigin.y()) - 1.0D;
            double z = Math.floor(childOrigin.z());
            child.getGeometry().setArea(new Box(x - 1.5D, x + 1.499D, y, y + 2.999D, z, z + 0.999D));
            when(child.getOrigin()).thenReturn(new Vec3d(x, y + 1.5D, z + 0.5D));
            when(child.getWorldKey()).thenReturn("minecraft:overworld");
            when(child.getProjectionMode()).thenReturn(ProjectionMode.ON);
            when(fixture.runtime().portals().get(child.getId())).thenReturn(child);
        }
        when(mirror.isMirrorMode()).thenReturn(true);
        when(frame.getTunnelType()).thenReturn("LOCAL");
        UUID linkedId = linked.getId();
        when(frame.getDestinationId()).thenReturn(linkedId);
        when(fixture.runtime().portals().get(linked.getId())).thenReturn(linked);
        DoorItemIdentity childIdentity = DoorItemIdentity.newPersonal();
        int childX = (int) Math.floor(childOrigin.x()) - 4;
        int childY = (int) Math.floor(childOrigin.y()) - 1;
        int childZ = (int) Math.floor(childOrigin.z());
        PlacedDoorEndpoint childEndpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", childX, childY, childZ),
            childIdentity, DoorOpenState.OPEN, DoorProjectionState.OFF);
        MinecraftDoorService.DoorView childDoor = new MinecraftDoorService.DoorView(childEndpoint, overworld,
            new DoorwayPlane(childX, childY, childZ, Face.N), true);
        when(doors.projectionDestination(childDoor, fixture.player().getUUID())).thenReturn(Optional.of(
            new MinecraftDoorService.ProjectionDestination(UUID.randomUUID(), pocket, new Vec3d(40.5D, 65.0D, 40.5D),
                Frame.canonical(Face.S))));
        when(fixture.runtime().projections().projectableDoors()).thenReturn(List.of(rootDoor, childDoor));
        portals.frame(List.of(mirror, frame));
        interest.clear();
        portals.interested(fixture.peer(), interest);
        assertEquals(List.of(root), interest);
        ArrayList<UUID> discovered = new ArrayList<>();
        portals.nested(fixture.peer(), root, rootGeometry, discovered);
        assertEquals(3, discovered.size());
        assertTrue(discovered.containsAll(List.of(mirror.getId(), frame.getId(), childIdentity.itemId())));
        for (UUID childId : discovered) {
            UUID branch = UUID.randomUUID();
            portals.prepareNested(fixture.peer(), branch, root, childId);
            assertEquals(portals.nestedEye(fixture.peer(), root), fixture.peer().nestedContext(branch).sourceEye());
            ApertureDescriptor childGeometry = portals.nestedGeometry(fixture.peer(), root, childId, new SessionPalette());
            assertNotNull(childId.toString(), childGeometry);
            assertEquals(childId.equals(childIdentity.itemId()) ? ApertureDescriptor.KIND_DOOR : ApertureDescriptor.KIND_FRAME,
                childGeometry.kind());
            assertEquals(childId.equals(mirror.getId()), childGeometry.mirror());
            assertNotNull(portals.scene().sceneKey(fixture.peer(), branch));
            assertEquals(childId.equals(childIdentity.itemId()) ? pocket : overworld, fixture.peer().nestedContext(branch).destinationWorld());
        }
    }

    private static EntitySnapshot stand(UUID id, double x) {
        return EntitySnapshot.full(id, "minecraft:armor_stand", x, 65.0D, -2.5D, 1.975D, 0.0D, 0.0D, 1.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "",
            "", "", null, null, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
    }

    private static Fixture fixture(PortalType type) {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftProjectorPortalAccess access = mock(MinecraftProjectorPortalAccess.class);
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        MinecraftRtpRuntime rtp = mock(MinecraftRtpRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerLevel world = mock(ServerLevel.class);
        when(runtime.server()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getViewDistance()).thenReturn(8);
        MinecraftPortal source = portal(0.0D);
        MinecraftPortal target = portal(10.0D);
        when(source.getType()).thenReturn(type);
        when(source.getNetworkViewLateralPad()).thenReturn(8);
        MinecraftProjectionWorldView view = mock(MinecraftProjectionWorldView.class);
        ProjectionConfig projection = new ProjectionConfig();
        projection.maxProjectedCells = 10_000;
        WormholesSettings settings = new WormholesSettings(new MainConfig(), projection, new RenderConfig(), new NetworkConfig());
        List<ViewPlateBuilder.Job<BlockState, ServerLevel>> scheduled = new ArrayList<>();
        ViewPlateCache<BlockState, ServerLevel> cache = new ViewPlateCache<>(FidelitySettings.plateMaxBytes, scheduled::add);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(runtime.portals()).thenReturn(registry);
        when(runtime.projections()).thenReturn(projections);
        when(runtime.rtp()).thenReturn(rtp);
        when(rtp.projectionDestination(any(), any())).thenReturn(target);
        when(rtp.knownDestination(any(), any())).thenReturn(target);
        when(projections.changes()).thenReturn(new WorldChangeTracker());
        when(projections.plates()).thenReturn(cache);
        when(projections.view(world)).thenReturn(view);
        when(registry.get(source.getId())).thenReturn(source);
        when(registry.resolveLevel(any())).thenReturn(world);
        when(access.eligible(source)).thenReturn(true);
        when(access.current(source)).thenReturn(true);
        when(access.world(source)).thenReturn(world);
        when(access.world(target)).thenReturn(world);
        when(access.projectionDestination(source)).thenReturn(target);
        when(access.portals()).thenReturn(List.of());
        when(access.createRecursiveIndex()).thenAnswer(ignored -> new RecursiveEndpoints<>(access,
            () -> new RecursiveEndpoints.Options(0.75D, 64.0D)));
        when(view.getWorld()).thenReturn(world);
        when(view.worldId()).thenReturn(UUID.randomUUID());
        when(view.getMinHeight()).thenReturn(-64);
        when(view.getMaxHeight()).thenReturn(320);
        when(view.isChunkReady(anyInt(), anyInt())).thenReturn(true);
        BlockState stone = Blocks.STONE.defaultBlockState();
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        when(view.sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        ServerPlayer player = viewer(world);
        MinecraftClientViewPeer peer = new MinecraftClientViewPeer(player.getUUID(), "Viewer", new Connection(PacketFlow.SERVERBOUND));
        peer.attach(player, access);
        return new Fixture(runtime, access, viewer(world), player, peer, source, view, rtp, cache, scheduled);
    }

    private static ServerPlayer viewer(ServerLevel world) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.requestedViewDistance()).thenReturn(8);
        when(player.level()).thenReturn(world);
        when(player.getEyePosition()).thenReturn(new Vec3(1.0D, 65.0D, 4.0D));
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static MinecraftPortal portal(double x) {
        MinecraftPortal portal = mock(MinecraftPortal.class);
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(x, x + 2.999D, 64.0D, 66.999D, 0.0D, 0.999D));
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getGeometry()).thenReturn(geometry);
        when(portal.getFrame()).thenReturn(Frame.canonical(Face.S));
        when(portal.getOrigin()).thenReturn(new Vec3d(x + 1.5D, 65.5D, 0.5D));
        when(portal.frame()).thenCallRealMethod();
        when(portal.origin()).thenCallRealMethod();
        when(portal.id()).thenCallRealMethod();
        when(portal.getNetworkViewDepth()).thenReturn(8);
        when(portal.getBlackoutColor()).thenReturn(BlackoutColor.BLACK);
        when(portal.getMirrorRotation()).thenReturn(QuarterTurn.DEGREES_0);
        when(portal.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
        when(portal.getType()).thenReturn(PortalType.PORTAL);
        when(portal.isOpen()).thenReturn(true);
        return portal;
    }

    private record Fixture(WormholesModRuntime runtime, MinecraftProjectorPortalAccess access, ServerPlayer vanilla, ServerPlayer player,
                           MinecraftClientViewPeer peer, MinecraftPortal source, MinecraftProjectionWorldView view, MinecraftRtpRuntime rtp,
                           ViewPlateCache<BlockState, ServerLevel> cache, List<ViewPlateBuilder.Job<BlockState, ServerLevel>> scheduled) {
    }
}
