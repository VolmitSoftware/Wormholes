package art.arcane.wormholes.render.clientview;

import art.arcane.wormholes.Settings;

import art.arcane.wormholes.door.view.DoorProjectionAdapter;
import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.math.Face;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class BukkitClientViewDoorsTest {
    @ParameterizedTest
    @MethodSource("crossWorldRoutes")
    void crossWorldNestedAperturesUseTheProjectedCamera(ApertureKind rootKind, ApertureKind childKind, boolean returning) {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 0), ConnectionState.PLAY)) {
            World pocket = mock(World.class);
            when(pocket.getUID()).thenReturn(UUID.randomUUID());
            World sourceWorld = returning ? pocket : fixture.world;
            World destinationWorld = returning ? fixture.world : pocket;
            when(fixture.player.getWorld()).thenReturn(sourceWorld);
            Location eye = new Location(sourceWorld, fixture.eye.getX(), fixture.eye.getY(), fixture.eye.getZ());
            ILocalPortal root = aperture(fixture, rootKind, sourceWorld, destinationWorld, 0);
            ILocalPortal child = aperture(fixture, childKind, destinationWorld, sourceWorld, 5);
            ILocalPortal reflectedDoor = childKind == ApertureKind.MIRROR
                ? aperture(fixture, ApertureKind.DOOR, destinationWorld, sourceWorld, -5) : null;
            PortalProjector.RtpProjectionTarget rootTarget = target(destinationWorld);
            PortalProjector.RtpProjectionTarget childTarget = target(sourceWorld);
            Map<UUID, PortalProjector.RtpProjectionTarget> targets = new HashMap<>();
            if (rootKind == ApertureKind.DOOR) {
                targets.put(root.getId(), rootTarget);
            }
            if (childKind == ApertureKind.DOOR) {
                targets.put(child.getId(), childTarget);
            }
            if (reflectedDoor != null) {
                targets.put(reflectedDoor.getId(), childTarget);
            }
            ClientViewObserver observer = new ClientViewObserver(fixture.playerId, fixture.user);
            observer.meshDepth(128);
            List<ILocalPortal> candidates = reflectedDoor == null ? List.of(root, child) : List.of(root, child, reflectedDoor);
            observer.beginFrame(fixture.player, eye, List.of(root), candidates, targets, 1L);
            BukkitClientViewPortalAccess access = new BukkitClientViewPortalAccess(fixture.views, fixture.plates, ignored -> null,
                (player, portal) -> { }, () -> 71L);
            access.prepareNested(observer, root.getId(), null, root.getId());
            ApertureDescriptor rootGeometry = access.geometry(observer, root.getId(), new SessionPalette());
            assertNotNull(rootGeometry);
            assertSame(destinationWorld, observer.reflectedEye(root.getId()).getWorld());
            List<UUID> children = new ArrayList<>();
            access.nested(observer, root.getId(), rootGeometry.withDepth(128), children);
            assertEquals(List.of(child.getId()), children);
            UUID childContext = UUID.randomUUID();
            access.prepareNested(observer, childContext, root.getId(), child.getId());
            ApertureDescriptor childGeometry = access.nestedGeometry(observer, root.getId(), child.getId(), new SessionPalette());
            assertNotNull(childGeometry);
            assertEquals(childKind == ApertureKind.DOOR ? ApertureDescriptor.KIND_DOOR : ApertureDescriptor.KIND_FRAME,
                childGeometry.kind());
            assertNotNull(observer.nestedContext(childContext));
            assertSame(destinationWorld, observer.nestedContext(childContext).sourceEye().getWorld());
            assertSame(childKind == ApertureKind.MIRROR ? destinationWorld : sourceWorld,
                observer.reflectedEye(childContext).getWorld());
            assertSame(sourceWorld, fixture.player.getWorld());
            if (reflectedDoor != null) {
                List<UUID> reflectedChildren = new ArrayList<>();
                access.nested(observer, childContext, childGeometry.withDepth(128), reflectedChildren);
                assertEquals(List.of(reflectedDoor.getId()), reflectedChildren);
                UUID reflectedContext = UUID.randomUUID();
                access.prepareNested(observer, reflectedContext, childContext, reflectedDoor.getId());
                assertNotNull(access.nestedGeometry(observer, childContext, reflectedDoor.getId(), new SessionPalette()));
                assertSame(destinationWorld, observer.nestedContext(reflectedContext).sourceEye().getWorld());
                assertSame(sourceWorld, observer.reflectedEye(reflectedContext).getWorld());
            }
        }
    }

    @Test
    void rootAperturesRejectACameraInAnotherWorld() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 0), ConnectionState.PLAY)) {
            World pocket = mock(World.class);
            when(pocket.getUID()).thenReturn(UUID.randomUUID());
            ILocalPortal portal = aperture(fixture, ApertureKind.MIRROR, pocket, pocket, 0);
            ClientViewObserver observer = new ClientViewObserver(fixture.playerId, fixture.user);
            observer.meshDepth(128);
            observer.beginFrame(fixture.player, fixture.eye, List.of(portal), List.of(portal), Map.of(), 1L);
            BukkitClientViewPortalAccess access = new BukkitClientViewPortalAccess(fixture.views, fixture.plates, ignored -> null,
                (player, aperture) -> { }, () -> 71L);
            assertNull(access.geometry(observer, portal.getId(), new SessionPalette()));
            access.prepareNested(observer, portal.getId(), null, portal.getId());
            assertNull(observer.nestedContext(portal.getId()));
        }
    }

    @Test
    void sameTickCameraAndRouteChangesRefreshTheSharedSource() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 0), ConnectionState.PLAY)) {
            DoorProjectionAdapter door = door(fixture);
            ClientViewPortalSource source = new ClientViewPortalSource(door, fixture.views, fixture.plates);
            PortalProjector.RtpProjectionTarget first = target(fixture.world);
            SessionPalette palette = new SessionPalette();
            source.update(fixture.player, fixture.eye, first, 1L, true);
            ApertureDescriptor before = source.geometry(palette, 71L);
            Location opposite = new Location(fixture.world, fixture.eye.getX(), fixture.eye.getY(), -fixture.eye.getZ());
            source.update(fixture.player, opposite, first, 1L, true);
            assertNotEquals(before.frontSide(), source.geometry(palette, 71L).frontSide());
            long identity = source.geometry(palette, 71L).targetIdentity();
            PortalProjector.RtpProjectionTarget next = new PortalProjector.RtpProjectionTarget(fixture.world, 101.5D, 65.5D, 0.5D,
                Frame.canonical(Face.E), 2L);
            source.update(fixture.player, opposite, next, 1L, true);
            assertNotEquals(identity, source.geometry(palette, 71L).targetIdentity());
            World pocket = mock(World.class);
            source.update(fixture.player, new Location(pocket, opposite.getX(), opposite.getY(), opposite.getZ()), next, 1L, true);
            assertTrue(source.unlinked());
            assertNull(source.geometry(palette, 71L));
        }
    }

    @Test
    void nestedAperturesRequireAnOnlineObserver() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 0), ConnectionState.PLAY)) {
            World pocket = mock(World.class);
            when(pocket.getUID()).thenReturn(UUID.randomUUID());
            ILocalPortal portal = aperture(fixture, ApertureKind.MIRROR, pocket, pocket, 0);
            ClientViewPortalSource source = new ClientViewPortalSource(portal, fixture.views, fixture.plates);
            Location camera = new Location(pocket, fixture.eye.getX(), fixture.eye.getY(), fixture.eye.getZ());
            source.update(fixture.player, camera, null, 1L, true);
            assertNotNull(source.geometry(new SessionPalette(), 71L));
            when(fixture.player.isOnline()).thenReturn(false);
            source.update(fixture.player, camera, null, 2L, true);
            assertTrue(source.unlinked());
            assertNull(source.geometry(new SessionPalette(), 71L));
        }
    }

    @Test
    void sharedDoorEntityScenesDistinguishRouteFramesAndNativeState() {
        boolean previousEntities = Settings.ENTITY_SPOOFING;
        Settings.ENTITY_SPOOFING = true;
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 0), ConnectionState.PLAY)) {
            DoorProjectionAdapter door = door(fixture);
            UUID id = door.getId();
            BukkitClientViewPortalAccess access = new BukkitClientViewPortalAccess(fixture.views, fixture.plates, ignored -> null,
                (observer, portal) -> { }, () -> 71L);
            ClientViewObserver first = new ClientViewObserver(fixture.playerId, fixture.user);
            ClientViewObserver second = new ClientViewObserver(UUID.randomUUID(), fixture.user);
            first.meshDepth(208);
            second.meshDepth(208);
            PortalProjector.RtpProjectionTarget north = new PortalProjector.RtpProjectionTarget(fixture.world, 100.5, 65, 100.5,
                Frame.canonical(Face.N), 1);
            PortalProjector.RtpProjectionTarget east = new PortalProjector.RtpProjectionTarget(fixture.world, 100.5, 65, 100.5,
                Frame.canonical(Face.E), 2);
            first.beginFrame(fixture.player, fixture.eye, List.of(door), List.of(door), Map.of(id, north), 1);
            second.beginFrame(fixture.player, fixture.eye, List.of(door), List.of(door), Map.of(id, east), 1);

            Object northScene = access.scene().sceneKey(first, id);
            Object eastScene = access.scene().sceneKey(second, id);
            assertNotNull(northScene);
            assertNotNull(eastScene);
            assertNotEquals(northScene, eastScene);

            PortalProjector.RtpProjectionTarget matchingNorth = new PortalProjector.RtpProjectionTarget(fixture.world, 100.5, 65, 100.5,
                Frame.canonical(Face.N), 3);
            second.beginFrame(fixture.player, fixture.eye, List.of(door), List.of(door), Map.of(id, matchingNorth), 2);
            assertEquals(northScene, access.scene().sceneKey(second, id));

            first.meshDepth(door.getNetworkViewDepth());
            Object nativeScene = access.scene().sceneKey(first, id);
            first.meshDepth(0);
            assertNotEquals(nativeScene, access.scene().sceneKey(first, id));
        } finally {
            Settings.ENTITY_SPOOFING = previousEntities;
        }
    }

    @Test
    void observerRoutedDoorsKeepTheirKindAndRecaptureAfterRetargeting() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 0), ConnectionState.PLAY)) {
            DoorProjectionAdapter door = door(fixture);
            BukkitClientViewPortalAccess access = new BukkitClientViewPortalAccess(fixture.views, fixture.plates, id -> null,
                (observer, portal) -> { }, () -> 71L);
            ClientViewObserver observer = new ClientViewObserver(fixture.playerId, fixture.user);
            observer.meshDepth(208);
            PortalProjector.RtpProjectionTarget first = new PortalProjector.RtpProjectionTarget(fixture.world, 100.5, 65, 100.5,
                Frame.canonical(Face.S), 1);
            observer.beginFrame(fixture.player, fixture.eye, List.of(door), List.of(door), Map.of(door.getId(), first), 1);
            ApertureDescriptor geometry = access.geometry(observer, door.getId(), new SessionPalette());
            assertNotNull(geometry);
            assertEquals(ApertureDescriptor.KIND_DOOR, geometry.kind());
            assertEquals(ApertureDescriptor.BLACKOUT_OFF, geometry.blackoutPolicy());
            assertNotEquals(0L, geometry.targetIdentity());
            long revision = access.geometryRevision(observer, door.getId());
            PlateBox clip = new PlateBox(0, 64, 0, 16, 16, 16);
            assertNull(access.meshSection(observer, door.getId(), clip, 208));
            assertEquals(1, fixture.jobs.size());
            long firstIdentity = fixture.jobs.getFirst().key().targetIdentity();

            PortalProjector.RtpProjectionTarget next = new PortalProjector.RtpProjectionTarget(fixture.world, 200.5, 65, 100.5,
                Frame.canonical(Face.S), 2);
            observer.beginFrame(fixture.player, fixture.eye, List.of(door), List.of(door), Map.of(door.getId(), next), 2);
            assertNotEquals(revision, access.geometryRevision(observer, door.getId()));
            assertNotEquals(geometry.targetIdentity(), access.geometry(observer, door.getId(), new SessionPalette()).targetIdentity());
            assertNull(access.meshSection(observer, door.getId(), clip, 208));
            assertEquals(2, fixture.jobs.size());
            assertNotEquals(firstIdentity, fixture.jobs.getLast().key().targetIdentity());
        }
    }

    @Test
    void reinstalledDoorReplacesCachedRootAndNestedSources() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 0), ConnectionState.PLAY)) {
            DoorProjectionAdapter first = door(fixture);
            DoorProjectionAdapter replacement = door(fixture);
            when(first.isOpen()).thenReturn(false);
            when(replacement.isOpen()).thenReturn(false);
            BukkitClientViewPortalAccess access = new BukkitClientViewPortalAccess(fixture.views, fixture.plates, id -> null,
                (observer, portal) -> { }, () -> 71L);
            ClientViewObserver observer = new ClientViewObserver(fixture.playerId, fixture.user);
            observer.beginFrame(fixture.player, fixture.eye, List.of(first), List.of(first), Map.of(), 1);
            observer.reflectedEye(fixture.portal.getId(), fixture.eye);
            ClientViewPortalSource root = access.source(observer, first.getId());
            ClientViewPortalSource nested = access.nestedSource(observer, fixture.portal.getId(), first.getId());
            assertSame(first, root.portal());
            assertSame(first, nested.portal());

            observer.beginFrame(fixture.player, fixture.eye, List.of(replacement), List.of(replacement), Map.of(), 2);
            observer.reflectedEye(fixture.portal.getId(), fixture.eye);
            assertSame(replacement, access.source(observer, replacement.getId()).portal());
            assertNotSame(root, access.source(observer, replacement.getId()));
            assertSame(replacement, access.nestedSource(observer, fixture.portal.getId(), replacement.getId()).portal());
            assertNotSame(nested, access.nestedSource(observer, fixture.portal.getId(), replacement.getId()));
            when(replacement.isDestroyed()).thenReturn(true);
            assertNull(access.source(observer, replacement.getId()));
            assertNull(access.nestedSource(observer, fixture.portal.getId(), replacement.getId()));
        }
    }

    private static DoorProjectionAdapter door(ClientViewFixture fixture) {
        DoorProjectionAdapter door = mock(DoorProjectionAdapter.class);
        ILocalPortal source = fixture.portal;
        UUID id = source.getId();
        Vec3 origin = source.getOrigin();
        Frame frame = source.getFrame();
        PortalStructure structure = source.getStructure();
        when(door.getId()).thenReturn(id);
        when(door.getWorld()).thenReturn(fixture.world);
        when(door.getOrigin()).thenReturn(origin);
        when(door.getFrame()).thenReturn(frame);
        when(door.getStructure()).thenReturn(structure);
        when(door.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
        when(door.getNetworkViewDepth()).thenReturn(24);
        when(door.getNetworkViewLateralPad()).thenReturn(4);
        when(door.isOpen()).thenReturn(true);
        return door;
    }

    private static Stream<Arguments> crossWorldRoutes() {
        return Stream.of(ApertureKind.DOOR, ApertureKind.LINKED).flatMap(root -> Stream.of(ApertureKind.values())
            .flatMap(child -> Stream.of(false, true).map(returning -> Arguments.of(root, child, returning))));
    }

    private static ILocalPortal aperture(ClientViewFixture fixture, ApertureKind kind, World world, World destinationWorld, int z) {
        ILocalPortal template = fixture.linkedPortal(z);
        ILocalPortal aperture = kind == ApertureKind.DOOR ? mock(DoorProjectionAdapter.class) : template;
        PortalStructure structure = template.getStructure();
        when(aperture.getId()).thenReturn(UUID.randomUUID());
        when(aperture.getWorld()).thenReturn(world);
        when(aperture.getOrigin()).thenReturn(structure.getArea().center());
        when(aperture.getFrame()).thenReturn(Frame.canonical(Face.N));
        when(aperture.getStructure()).thenReturn(structure);
        when(aperture.getRenderMode()).thenReturn(ProjectionRenderMode.PANOPTIC);
        when(aperture.getNetworkViewDepth()).thenReturn(24);
        when(aperture.getNetworkViewLateralPad()).thenReturn(4);
        when(aperture.isOpen()).thenReturn(true);
        if (kind == ApertureKind.MIRROR) {
            when(aperture.isMirrorMode()).thenReturn(true);
            when(aperture.getMirrorRotation()).thenReturn(QuarterTurn.DEGREES_0);
        } else if (kind == ApertureKind.LINKED) {
            ILocalPortal destination = mock(ILocalPortal.class);
            Vec3 destinationOrigin = fixture.portal.getOrigin();
            when(destination.getWorld()).thenReturn(destinationWorld);
            when(destination.getFrame()).thenReturn(Frame.canonical(Face.S));
            when(destination.getOrigin()).thenReturn(destinationOrigin);
            ITunnel tunnel = aperture.getTunnel();
            when(tunnel.getDestination()).thenReturn(destination);
        }
        return aperture;
    }

    private static PortalProjector.RtpProjectionTarget target(World world) {
        return new PortalProjector.RtpProjectionTarget(world, 1.4995D, 65.4995D, 0.4995D,
            Frame.canonical(Face.S), 1L);
    }

    private enum ApertureKind {
        DOOR,
        LINKED,
        MIRROR
    }
}
