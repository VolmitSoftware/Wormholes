package art.arcane.wormholes.render.clientview;

import art.arcane.wormholes.Settings;

import art.arcane.wormholes.door.view.DoorProjectionAdapter;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.util.Direction;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class BukkitClientViewDoorsTest {
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
                PortalFrame.canonical(Direction.N), 1);
            PortalProjector.RtpProjectionTarget east = new PortalProjector.RtpProjectionTarget(fixture.world, 100.5, 65, 100.5,
                PortalFrame.canonical(Direction.E), 2);
            first.beginFrame(fixture.player, fixture.eye, List.of(door), List.of(door), Map.of(id, north), 1);
            second.beginFrame(fixture.player, fixture.eye, List.of(door), List.of(door), Map.of(id, east), 1);

            Object northScene = access.scene().sceneKey(first, id);
            Object eastScene = access.scene().sceneKey(second, id);
            assertNotNull(northScene);
            assertNotNull(eastScene);
            assertNotEquals(northScene, eastScene);

            PortalProjector.RtpProjectionTarget matchingNorth = new PortalProjector.RtpProjectionTarget(fixture.world, 100.5, 65, 100.5,
                PortalFrame.canonical(Direction.N), 3);
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
                PortalFrame.canonical(Direction.S), 1);
            observer.beginFrame(fixture.player, fixture.eye, List.of(door), List.of(door), Map.of(door.getId(), first), 1);
            ClientPortalGeometry geometry = access.geometry(observer, door.getId(), new SessionPalette());
            assertNotNull(geometry);
            assertEquals(ClientPortalGeometry.KIND_DOOR, geometry.kind());
            assertEquals(ClientPortalGeometry.BLACKOUT_OFF, geometry.blackoutPolicy());
            assertNotEquals(0L, geometry.targetIdentity());
            long revision = access.geometryRevision(observer, door.getId());
            PlateBox clip = new PlateBox(0, 64, 0, 16, 16, 16);
            assertNull(access.meshSection(observer, door.getId(), clip, 208));
            assertEquals(1, fixture.jobs.size());
            long firstIdentity = fixture.jobs.getFirst().key().targetIdentity();

            PortalProjector.RtpProjectionTarget next = new PortalProjector.RtpProjectionTarget(fixture.world, 200.5, 65, 100.5,
                PortalFrame.canonical(Direction.S), 2);
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
        GeometryVector origin = source.getOrigin();
        PortalFrame frame = source.getFrame();
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
}
