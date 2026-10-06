package art.arcane.wormholes.render.clientview;

import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.aperture.ApertureDescriptor;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.client.ClientViewEnvironmentTransform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BukkitClientViewMeshGeometryTest {
    @Test
    void nativeFacingMirrorsDiscoverEachOtherWithIndependentBranchEyes() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            ILocalPortal second = fixture.linkedPortal(5);
            when(second.isMirrorMode()).thenReturn(true);
            when(second.getMirrorRotation()).thenReturn(QuarterTurn.DEGREES_0);
            ClientViewObserver observer = new ClientViewObserver(fixture.playerId, fixture.user);
            observer.meshDepth(128);
            observer.beginFrame(fixture.player, fixture.eye, List.of(fixture.portal), List.of(fixture.portal, second), Map.of(), 1L);
            BukkitClientViewPortalAccess access = new BukkitClientViewPortalAccess(fixture.views, fixture.plates, ignored -> null,
                (player, portal) -> {}, () -> 1L);
            UUID root = fixture.portal.getId();
            UUID child = UUID.randomUUID();
            UUID repeatedRoot = UUID.randomUUID();
            access.prepareNested(observer, root, null, root);
            ApertureDescriptor rootGeometry = access.geometry(observer, root, new SessionPalette()).withDepth(128);
            List<UUID> firstChildren = new ArrayList<>();
            access.nested(observer, root, rootGeometry, firstChildren);
            assertEquals(List.of(second.getId()), firstChildren);
            access.prepareNested(observer, child, root, second.getId());
            ApertureDescriptor childGeometry = access.nestedGeometry(observer, root, second.getId(), new SessionPalette()).withDepth(128);
            List<UUID> secondChildren = new ArrayList<>();
            access.nested(observer, child, childGeometry, secondChildren);
            assertEquals(List.of(root), secondChildren);
            access.prepareNested(observer, repeatedRoot, child, root);
            Vec3d rootEye = access.nestedEye(observer, root);
            Vec3d childEye = access.nestedEye(observer, child);
            Vec3d repeatedEye = access.nestedEye(observer, repeatedRoot);
            assertSame(observer.nestedContext(repeatedRoot).source(), access.source(observer, repeatedRoot));
            assertTrue(rootEye.z() < 0);
            assertTrue(childEye.z() > 5);
            assertTrue(repeatedEye.z() < rootEye.z());
            assertNotEquals(rootEye, repeatedEye);
            assertEquals(fixture.eye.getZ(), observer.nestedContext(root).sourceEye().getZ());
            access.releaseNested(observer, child);
            assertNull(observer.nestedContext(child));
            assertNull(access.nestedEye(observer, child));
            assertEquals(rootEye, access.nestedEye(observer, root));
        }
    }

    @Test
    void nativeLinkedBranchUsesItsParentsRotatedDestinationEye() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            ILocalPortal linked = fixture.linkedPortal(5);
            IPortal destination = linked.getTunnel().getDestination();
            Frame rotated = destination.getFrame().rotateClockwise();
            when(destination.getFrame()).thenReturn(rotated);
            ClientViewObserver observer = new ClientViewObserver(fixture.playerId, fixture.user);
            observer.meshDepth(128);
            observer.beginFrame(fixture.player, fixture.eye, List.of(linked), List.of(linked, fixture.portal), Map.of(), 1L);
            BukkitClientViewPortalAccess access = new BukkitClientViewPortalAccess(fixture.views, fixture.plates, ignored -> null,
                (player, portal) -> {}, () -> 1L);
            access.prepareNested(observer, linked.getId(), null, linked.getId());
            Vec3d expected = ClientViewEnvironmentTransform.of(observer.source(linked.getId()).transformFrame())
                .destinationPoint(fixture.eye.getX(), fixture.eye.getY(), fixture.eye.getZ());
            assertEquals(expected, access.nestedEye(observer, linked.getId()));
            UUID childContext = UUID.randomUUID();
            access.prepareNested(observer, childContext, linked.getId(), fixture.portal.getId());
            assertEquals(expected.x(), observer.nestedContext(childContext).sourceEye().getX());
            assertEquals(expected.y(), observer.nestedContext(childContext).sourceEye().getY());
            assertEquals(expected.z(), observer.nestedContext(childContext).sourceEye().getZ());
            assertNotEquals(fixture.eye.getX(), expected.x());
        }
    }

    @Test
    void nativeMenuControlsRequireAnActiveMeshNegotiation() throws ClientViewProtocolException {
        for (boolean mesh : List.of(false, true)) {
            try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
                fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
                assertTrue(fixture.negotiator.offerPlay(fixture.player));
                assertFalse(fixture.clientView.nativeMesh(fixture.player));
                fixture.hello(mesh ? ClientViewFixture.CLIENT_CAPS | ViewStreamCapability.MESH_RENDER.mask()
                    : ClientViewFixture.CLIENT_CAPS & ~ViewStreamCapability.MESH_RENDER.mask());
                assertEquals(mesh, fixture.clientView.nativeMesh(fixture.player));
                fixture.session().end(ClientViewMessage.ResetReason.DISABLED);
                assertFalse(fixture.clientView.nativeMesh(fixture.player));
            }
        }
    }

    @Test
    void nativeMirrorIgnoresBlackoutWhileOrdinaryProjectionKeepsIt() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            when(fixture.portal.isBlackoutBackground()).thenReturn(true);
            when(fixture.portal.getBlackoutColor()).thenReturn(BlackoutColor.BLACK);
            ClientViewPortalSource source = new ClientViewPortalSource(fixture.portal, fixture.views, fixture.plates);
            SessionPalette palette = new SessionPalette();
            source.update(fixture.player, fixture.eye, null, 1L, true);
            ApertureDescriptor nativeGeometry = source.geometry(palette, 1L);
            long revision = source.geometryRevision();
            assertTrue(nativeGeometry.mirror());
            assertEquals(ApertureDescriptor.BLACKOUT_OFF, nativeGeometry.blackoutPolicy());
            assertEquals(0, nativeGeometry.blackoutState());
            assertEquals(ProjectedBlockClaim.LightingPolicy.SOURCE.ordinal(), nativeGeometry.lightingPolicy());
            when(fixture.portal.getBlackoutColor()).thenReturn(BlackoutColor.WHITE);
            source.update(fixture.player, fixture.eye, null, 2L, true);
            assertEquals(revision, source.geometryRevision());
            assertEquals(nativeGeometry, source.geometry(palette, 1L));
            source.update(fixture.player, fixture.eye, null, 2L, false);
            ApertureDescriptor ordinaryGeometry = source.geometry(palette, 1L);
            assertEquals(ApertureDescriptor.BLACKOUT_SHELL, ordinaryGeometry.blackoutPolicy());
            assertEquals(ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT.ordinal(), ordinaryGeometry.lightingPolicy());
        }
    }

    @Test
    void wallMirrorRotationUsesRawMeshGeometryAndClampedPacketGeometry() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            ClientViewPortalSource source = new ClientViewPortalSource(fixture.portal, fixture.views, fixture.plates);
            SessionPalette palette = new SessionPalette();
            long tick = 0L;
            for (QuarterTurn rotation : List.of(QuarterTurn.DEGREES_90, QuarterTurn.DEGREES_270)) {
                when(fixture.portal.getMirrorRotation()).thenReturn(rotation);
                source.update(fixture.player, fixture.eye, null, ++tick, true);
                assertEquals(rotation.getQuarterTurns(), source.geometry(palette, 1L).mirrorQuarterTurns());
                assertEquals(rotation.getQuarterTurns(), source.transformFrame().quarterTurns());
                source.update(fixture.player, fixture.eye, null, tick, false);
                int ordinary = rotation.coherentFor(fixture.portal.getFrame()).getQuarterTurns();
                assertEquals(ordinary, source.geometry(palette, 1L).mirrorQuarterTurns());
                assertEquals(ordinary, source.transformFrame().quarterTurns());
            }
        }
    }

    @Test
    void linkedDestinationRotationChangesTheNativeStreamIdentity() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            ILocalPortal linked = fixture.linkedPortal(5);
            IPortal destination = linked.getTunnel().getDestination();
            ClientViewPortalSource source = new ClientViewPortalSource(linked, fixture.views, fixture.plates);
            SessionPalette palette = new SessionPalette();
            source.update(fixture.player, fixture.eye, null, 1L, true);
            ApertureDescriptor before = source.geometry(palette, 1L);
            long revision = source.geometryRevision();
            Frame rotated = destination.getFrame().rotateClockwise();
            when(destination.getFrame()).thenReturn(rotated);
            source.update(fixture.player, fixture.eye, null, 2L, true);
            assertNotEquals(before.targetIdentity(), source.geometry(palette, 1L).targetIdentity());
            assertNotEquals(revision, source.geometryRevision());
        }
    }

    @Test
    void nestedMeshIgnoresLegacyPlateRefusal() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            UUID parent = UUID.randomUUID();
            UUID child = UUID.randomUUID();
            ClientViewObserver observer = mock(ClientViewObserver.class);
            ClientViewPortalSource source = mock(ClientViewPortalSource.class);
            ApertureDescriptor geometry = mock(ApertureDescriptor.class);
            when(observer.player()).thenReturn(fixture.player);
            when(observer.reflectedEye(parent)).thenReturn(fixture.eye);
            when(observer.nestedSource(parent, child)).thenReturn(source);
            when(observer.portal(child)).thenReturn(fixture.portal);
            when(source.portal()).thenReturn(fixture.portal);
            when(observer.meshDepth()).thenReturn(208);
            when(source.refused()).thenReturn(true);
            when(source.geometry(any(), anyLong())).thenReturn(geometry);
            BukkitClientViewPortalAccess access = new BukkitClientViewPortalAccess(fixture.views, fixture.plates, ignored -> null,
                (player, portal) -> {}, () -> 1L);
            assertSame(geometry, access.nestedGeometry(observer, parent, child, new SessionPalette()));
            when(observer.meshDepth()).thenReturn(0);
            assertNull(access.nestedGeometry(observer, parent, child, new SessionPalette()));
        }
    }
}
