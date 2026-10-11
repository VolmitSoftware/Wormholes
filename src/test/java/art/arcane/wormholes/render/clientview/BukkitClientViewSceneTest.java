package art.arcane.wormholes.render.clientview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.ConnectionState;

import art.arcane.wormholes.Settings;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.stream.ViewStreamInbound;
import art.arcane.optics.view.ContentView;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class BukkitClientViewSceneTest {
    private final boolean oldLighting = Settings.LIGHTING_FIDELITY;
    private final double oldEntityRange = Settings.ENTITY_SPOOF_RANGE;

    @AfterEach
    void restore() {
        Settings.LIGHTING_FIDELITY = oldLighting;
        Settings.ENTITY_SPOOF_RANGE = oldEntityRange;
    }

    @Test
    void streamedMirrorFramesCarryTheObserverReflection() throws ViewStreamProtocolException {
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS)) {
            EntitySnapshot self = EntitySnapshot.full(fixture.playerId, "minecraft:player", 1.5D, 64.0D, 3.0D, 1.8D,
                0.0D, 0.0D, 1.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "Observer", "", "", null, null,
                EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
            ProjectionEntityView entities = (ProjectionEntityView) fixture.view;
            when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(self));
            when(entities.isVisibleTo(any(Player.class), eq(fixture.playerId))).thenReturn(true);
            fixture.route();
            fixture.route();
            ViewStreamMessage.EntityFrame frame = lastFrame(fixture.messages());
            assertNotNull(frame);
            assertEquals(1, frame.presentIds().size(), "a streamed mirror shows the observer's reflection");
            assertNotEquals(fixture.playerId, frame.presentIds().getFirst());
        }
    }

    @Test
    void destinationLightRidesInThePlateBricksWhenLightingFidelityIsOn() throws ViewStreamProtocolException {
        Settings.LIGHTING_FIDELITY = true;
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS)) {
            when(fixture.view.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ContentView.packLight(13, 5));
            fixture.route();
            fixture.buildPlates();
            fixture.route();
            int lit = 0;
            for (ViewStreamMessage message : fixture.messages()) {
                if (!(message instanceof ViewStreamMessage.PlateBricks bricks)) {
                    continue;
                }
                for (Brick brick : bricks.bricks()) {
                    if (!brick.hasLight()) {
                        continue;
                    }
                    for (int cell = 0; cell < ViewStreamLimits.BRICK_CELLS; cell++) {
                        if (BrickLightSource.nibble(brick.skyLight(), cell) == 13) {
                            assertEquals(5, BrickLightSource.nibble(brick.blockLight(), cell));
                            lit++;
                        }
                    }
                }
            }
            assertTrue(lit > 0, "no plate cell carried the destination light");
        }
    }

    @Test
    void destinationEntitiesStreamInLocalSpaceUnderOpaqueIdsAndFollowVisibility() throws ViewStreamProtocolException {
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS)) {
            UUID source = UUID.randomUUID();
            EntitySnapshot stand = EntitySnapshot.full(source, "minecraft:armor_stand", 1.5D, 64.0D, 3.0D, 1.975D, 0.0D, 0.0D, 1.0D, 0.0F, 0.0F,
                0.0D, 0.0D, 0.0D, true, "", "", "", null, null, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
            ProjectionEntityView entities = (ProjectionEntityView) fixture.view;
            when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(stand));
            when(entities.isVisibleTo(any(Player.class), eq(source))).thenReturn(true);
            fixture.route();
            ViewStreamMessage.EntityFrame frame = lastFrame(fixture.messages());
            assertNotNull(frame, "no entity frame was streamed");
            assertEquals(1, frame.entities().size());
            EntitySnapshot local = frame.entities().get(0);
            assertNotEquals(source, local.id());
            assertTrue(local.isFull());
            assertTrue(local.z() < 0.5D, "the reflected stand should sit behind the mirror plane, was " + local.z());
            assertEquals(List.of(local.id()), frame.presentIds());
            when(entities.isVisibleTo(any(Player.class), eq(source))).thenReturn(false);
            fixture.route();
            ViewStreamMessage.EntityFrame hidden = lastFrame(fixture.messages());
            assertNotNull(hidden);
            assertTrue(hidden.presentIds().isEmpty(), "entities the observer cannot see leave the frame");
        }
    }

    @Test
    void clientDrawnMirrorsLeaveTheObserverOutOfItsOwnEntityFrames() throws ViewStreamProtocolException {
        assertEquals(1, mirrorPresence(ClientViewFixture.CLIENT_CAPS | ViewStreamCapability.CLIENT_MIRROR.mask()),
            "the client draws its own reflection");
        assertEquals(2, mirrorPresence(ClientViewFixture.CLIENT_CAPS), "a streamed mirror plate keeps the projected observer");
    }

    @Test
    void recursiveLinkedObserverRequiresSeparationFromTheComposedEye() {
        BukkitClientViewScene scene = new BukkitClientViewScene(mock(BukkitClientViewPortalAccess.class));
        ClientViewObserver observer = new ClientViewObserver(UUID.randomUUID(), null);
        Player player = mock(Player.class);
        World world = mock(World.class);
        Location eye = new Location(world, 0.0D, 65.6D, 0.0D);
        observer.beginFrame(player, eye, List.of(), List.of(), Map.of(), 1L);
        observer.meshDepth(64);
        ILocalPortal portal = mock(ILocalPortal.class);
        UUID portalId = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        when(portal.getId()).thenReturn(portalId);
        ClientViewPortalSource source = mock(ClientViewPortalSource.class);
        when(source.portal()).thenReturn(portal);
        Vec3d origin = new Vec3d(0.0D, 64.0D, 0.0D);
        Frame frame = Frame.canonical(Face.N);
        ViewWindow window = ViewWindow.between(origin, frame, new Vec3d(0.0D, 64.0D, 4.0D), frame, true, 64.0D);
        when(source.transformFrame()).thenReturn(window);
        Location parentEye = new Location(world, 0.0D, 65.6D, 4.0D);
        Vec3d composedEye = window.toward().point(new Vec3d(parentEye.getX(), parentEye.getY(), parentEye.getZ()));
        observer.nestedContext(child, source, parentEye);
        observer.reflectedEye(child, new Location(world, composedEye.x(), composedEye.y(), composedEye.z()));
        assertTrue(scene.observerVisible(observer, child));
        observer.nestedContext(portalId, source, eye);
        observer.reflectedEye(portalId, observer.reflectedEye(child));
        assertFalse(scene.observerVisible(observer, portalId), "the root view still conceals the observer");
        observer.reflectedEye(child, eye.clone());
        assertFalse(scene.observerVisible(observer, child), "an identity loop conceals the observer");
        observer.reflectedEye(child, new Location(world, 0.0D, 65.6D, 1.0D));
        assertFalse(scene.observerVisible(observer, child));
        observer.reflectedEye(child, new Location(world, 0.0D, 65.6D, 1.001D));
        assertTrue(scene.observerVisible(observer, child));
        when(source.transformFrame()).thenReturn(ViewWindow.mirror(origin, frame, QuarterTurn.DEGREES_0, true, 64.0D));
        assertFalse(scene.observerVisible(observer, child), "the client retains ownership of mirror reflections");
        when(source.transformFrame()).thenReturn(ViewWindow.between(origin, frame, origin, frame, true, 64.0D));
        observer.reflectedEye(child, new Location(mock(World.class), 0.0D, 65.6D, 8.0D));
        assertFalse(scene.observerVisible(observer, child));
        observer.reflectedEye(child, new Location(world, 0.0D, 65.6D, 8.0D));
        observer.meshDepth(0);
        assertFalse(scene.observerVisible(observer, child));
    }

    @Test
    void meshEntitiesUseClientDepthWhileLegacyEntitiesKeepPortalDepth() throws ViewStreamProtocolException {
        Settings.ENTITY_SPOOF_RANGE = 128;
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS | ViewStreamCapability.MESH_RENDER.mask())) {
            when(fixture.player.getClientViewDistance()).thenReturn(10);
            ProjectionEntityView entities = (ProjectionEntityView) fixture.view;
            EntitySnapshot stand = EntitySnapshot.full(UUID.randomUUID(), "minecraft:armor_stand", 1.5D, 64.0D, 111.0D, 1.975D,
                0.0D, 0.0D, 1.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null,
                EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
            when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(stand));
            when(entities.isVisibleTo(any(Player.class), any(UUID.class))).thenReturn(true);
            fixture.route();
            ViewStreamMessage.EntityFrame frame = lastFrame(fixture.messages());
            assertNotNull(frame);
            assertEquals(1, frame.presentIds().size(), "entities deeper than the legacy portal depth must reach mesh clients");
            verify(entities).getEntities(anyDouble(), anyDouble(), anyDouble(), eq(128.0D));
        }
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS)) {
            when(fixture.player.getClientViewDistance()).thenReturn(10);
            ProjectionEntityView entities = (ProjectionEntityView) fixture.view;
            fixture.route();
            verify(entities).getEntities(anyDouble(), anyDouble(), anyDouble(), eq(8.0D));
        }
    }

    private static int mirrorPresence(long clientCaps) throws ViewStreamProtocolException {
        try (ClientViewFixture fixture = negotiated(clientCaps)) {
            EntitySnapshot self = EntitySnapshot.full(fixture.playerId, "minecraft:player", 1.5D, 64.0D, 3.0D, 1.8D, 0.0D, 0.0D, 1.0D, 0.0F, 0.0F,
                0.0D, 0.0D, 0.0D, true, "", "", "", null, null, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
            EntitySnapshot stand = EntitySnapshot.full(UUID.randomUUID(), "minecraft:armor_stand", 0.5D, 64.0D, 3.0D, 1.975D, 0.0D, 0.0D, 1.0D, 0.0F,
                0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, 0);
            ProjectionEntityView entities = (ProjectionEntityView) fixture.view;
            when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(self, stand));
            when(entities.isVisibleTo(any(Player.class), any(UUID.class))).thenReturn(true);
            fixture.route();
            ViewStreamMessage.EntityFrame frame = lastFrame(fixture.messages());
            assertNotNull(frame, "no entity frame was streamed");
            return frame.presentIds().size();
        }
    }

    private static ViewStreamMessage.EntityFrame lastFrame(List<ViewStreamMessage> messages) {
        ViewStreamMessage.EntityFrame last = null;
        for (ViewStreamMessage message : messages) {
            if (message instanceof ViewStreamMessage.EntityFrame frame) {
                last = frame;
            }
        }
        return last;
    }

    private static ClientViewFixture negotiated(long clientCaps) throws ViewStreamProtocolException {
        ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY);
        fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
        assertTrue(fixture.negotiator.offerPlay(fixture.player));
        assertEquals(ViewStreamInbound.HELLO_ACCEPTED, fixture.hello(clientCaps));
        List<ViewStreamMessage> handshake = new ArrayList<ViewStreamMessage>(fixture.messages());
        assertEquals(2, handshake.size());
        return fixture;
    }
}
