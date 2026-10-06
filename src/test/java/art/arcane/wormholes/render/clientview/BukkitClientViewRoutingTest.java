package art.arcane.wormholes.render.clientview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import com.github.retrooper.packetevents.protocol.ConnectionState;

import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ViewStreamMessageType;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.optics.occlusion.LocalOcclusionArbiter;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.wormholes.render.ProjectionClaimArbiter;
import art.arcane.optics.stream.ClientViewInbound;
import art.arcane.optics.stream.ClientViewSessionState;
import art.arcane.wormholes.platform.QueuedOpticsScheduler;

final class BukkitClientViewRoutingTest {
    @Test
    void nativeGeometryFailureAndAutomaticResetsNeverReturnThePortalToPacketProjection() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
            assertTrue(fixture.negotiator.offerPlay(fixture.player));
            fixture.hello(ClientViewFixture.CLIENT_CAPS | ViewStreamCapability.MESH_RENDER.mask());
            when(fixture.portal.getFrame()).thenReturn(null);
            assertTrue(fixture.route().isEmpty());
            assertTrue(fixture.clientView.nativeMesh(fixture.player));
            assertTrue(fixture.session().owns(fixture.portal.getId()));
            for (ClientViewMessage.ResetReason reason : List.of(ClientViewMessage.ResetReason.PROTOCOL, ClientViewMessage.ResetReason.OVERLOAD)) {
                fixture.session().end(reason);
                assertTrue(fixture.route().isEmpty());
                assertTrue(fixture.clientView.nativeMesh(fixture.player));
                assertTrue(fixture.session().owns(fixture.portal.getId()));
            }
            fixture.clientView.runtimeEnabled(false);
            assertEquals(List.of(fixture.portal), fixture.route());
            assertFalse(fixture.clientView.nativeMesh(fixture.player));
        }
    }

    @Test
    void vanillaObserverKeepsEveryPortalOnTheVanillaProjector() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            List<ILocalPortal> interested = fixture.route();

            assertEquals(List.of(fixture.portal), interested);
            assertTrue(fixture.released.isEmpty());
            assertFalse(fixture.clientView.attending());
            assertTrue(fixture.messages().isEmpty());
        }
    }

    @Test
    void negotiatedObserverOwnsTheInterestedPortalAndReceivesItsPlate() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated()) {
            List<ILocalPortal> interested = fixture.route();

            assertTrue(interested.isEmpty());
            assertEquals(List.of(fixture.playerId + " " + fixture.portal.getId()), fixture.released);
            assertTrue(fixture.clientView.attending());
            assertTrue(fixture.messages().isEmpty());

            fixture.buildPlates();
            assertTrue(fixture.route().isEmpty());
            List<ClientViewMessage> plate = fixture.messages();
            assertEquals(List.of(ViewStreamMessageType.PALETTE, ViewStreamMessageType.PORTAL, ViewStreamMessageType.PLATE_BEGIN,
                ViewStreamMessageType.PLATE_BRICKS, ViewStreamMessageType.PLATE_END), types(plate));
            ClientViewMessage.Portal portal = (ClientViewMessage.Portal) plate.get(1);
            assertTrue(portal.geometry().mirror());
            ClientViewMessage.Palette palette = (ClientViewMessage.Palette) plate.get(0);
            assertTrue(palette.entries().stream().anyMatch(entry -> entry.state().equals("minecraft:stone")));
            ClientViewMessage.PlateBegin begin = (ClientViewMessage.PlateBegin) plate.get(2);
            assertEquals(portal.portalKey(), begin.portalKey());

            List<Player> observers = new ArrayList<Player>();
            fixture.clientView.observersOf(fixture.portal.getId(), observers);
            assertEquals(List.of(fixture.player), observers);
            assertEquals(1, fixture.released.size());
        }
    }

    @Test
    void refusedPlatesKeepThePortalOnTheVanillaProjector() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated()) {
            FidelitySettings.sharedPlate = false;
            List<ILocalPortal> interested = fixture.route();

            assertEquals(List.of(fixture.portal), interested);
            assertTrue(fixture.released.isEmpty());
            assertFalse(fixture.clientView.attending());
        }
    }

    @Test
    void killSwitchResetsTheClientAndReturnsThePortalToVanilla() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated()) {
            assertTrue(fixture.route().isEmpty());
            fixture.messages();

            fixture.clientView.runtimeEnabled(false);
            List<ClientViewMessage> reset = fixture.messages();
            assertEquals(1, reset.size());
            assertEquals(ClientViewMessage.ResetReason.DISABLED, ((ClientViewMessage.SessionReset) reset.get(0)).reason());
            assertEquals(ClientViewSessionState.VANILLA, fixture.session().state());

            assertEquals(List.of(fixture.portal), fixture.route());
            assertFalse(fixture.clientView.attending());
            assertTrue(fixture.messages().isEmpty());
        }
    }

    @Test
    void portalLeavingInterestIsDroppedAfterTheGrace() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated()) {
            fixture.route();
            fixture.buildPlates();
            fixture.route();
            fixture.messages();
            for (int tick = 0; tick < 7; tick++) {
                fixture.clientView.route(fixture.player, fixture.eye.clone(), new ArrayList<ILocalPortal>(), List.of(), Map.of(), ++fixture.tick);
            }
            List<ClientViewMessage> dropped = fixture.messages();
            assertEquals(1, dropped.size());
            assertInstanceOf(ClientViewMessage.PortalDrop.class, dropped.get(0));
            assertFalse(fixture.clientView.attending());
        }
    }

    @Test
    void vanillaAndClientViewObserversShareOnePlate() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated()) {
            fixture.route();
            fixture.buildPlates();
            fixture.route();
            assertTrue(types(fixture.messages()).contains(ViewStreamMessageType.PLATE_END));
            long builds = fixture.plates.buildsCompleted();
            ProjectionClaimArbiter arbiter = mock(ProjectionClaimArbiter.class, withSettings().defaultAnswer(Answers.RETURNS_MOCKS));
            LocalOcclusionArbiter<Player, Entity> occlusion = mock(LocalOcclusionArbiter.class);
            PortalProjector projector = new PortalProjector(fixture.portal, fixture.player, arbiter, fixture.views, () -> true, occlusion,
                fixture.plates, new QueuedOpticsScheduler());

            projector.project(true, false);

            assertTrue(fixture.jobs.isEmpty());
            assertEquals(1, fixture.plates.size());
            assertEquals(builds, fixture.plates.buildsCompleted());
            assertTrue(fixture.route().isEmpty());
            assertTrue(types(fixture.messages()).isEmpty());
        }
    }

    @Test
    void clientMirrorObserversOwnTheMirrorWithoutBuildingItsPlate() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS | ViewStreamCapability.CLIENT_MIRROR.mask())) {
            for (int tick = 0; tick < 3; tick++) {
                assertTrue(fixture.route().isEmpty());
            }
            assertTrue(fixture.jobs.isEmpty(), "a mirror plate build was scheduled");
            assertEquals(0, fixture.plates.size());
            assertEquals(List.of(fixture.playerId + " " + fixture.portal.getId()), fixture.released);
            List<ClientViewMessage> stream = fixture.messages();
            assertEquals(List.of(ViewStreamMessageType.PORTAL), types(stream));
            assertTrue(((ClientViewMessage.Portal) stream.get(0)).geometry().mirror());
            assertTrue(fixture.clientView.attending());
        }
    }

    @Test
    void portalsInFrontOfAClientMirrorStreamAsNestedChildren() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS | ViewStreamCapability.CLIENT_MIRROR.mask()
            | ViewStreamCapability.CLIENT_RECURSION.mask())) {
            ILocalPortal child = fixture.linkedPortal(2);
            fixture.routeWith(child);
            fixture.buildPlates();
            fixture.routeWith(child);
            List<ClientViewMessage> stream = fixture.messages();
            ClientViewMessage.Portal parent = null;
            ClientViewMessage.Portal nested = null;
            ClientViewMessage.Portal direct = null;
            for (ClientViewMessage message : stream) {
                if (message instanceof ClientViewMessage.Portal portal) {
                    if (portal.geometry().mirror()) {
                        parent = portal;
                    } else if (portal.geometry().parentPortalKey() != 0) {
                        nested = portal;
                    } else {
                        direct = portal;
                    }
                }
            }
            assertTrue(parent != null && nested != null, "expected a mirror and a nested PORTAL in " + types(stream));
            assertEquals(parent.portalKey(), nested.geometry().parentPortalKey());
            assertEquals(List.of(nested.geometry()), parent.geometry().nested());
            assertTrue(types(stream).contains(ViewStreamMessageType.PLATE_BEGIN));
            assertTrue(direct != null, "a projectable portal behind the player is also attended directly in " + types(stream));
            assertTrue(fixture.session().owns(child.getId()));
        }
    }

    @Test
    void negotiatedObserverAttendsEveryProjectablePortalWithoutWaitingForTheGaze() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated()) {
            List<ILocalPortal> interested = new ArrayList<ILocalPortal>();
            fixture.clientView.route(fixture.player, fixture.eye.clone(), interested, List.of(fixture.portal), Map.of(), ++fixture.tick);

            assertTrue(interested.isEmpty());
            assertTrue(fixture.clientView.attending(), "a projectable portal behind the player is attended by the client sweep");
            assertEquals(List.of(fixture.playerId + " " + fixture.portal.getId()), fixture.released);
        }
    }

    @Test
    void onlyObserversOwningPortalsCountAsProjectionObservers() throws ClientViewProtocolException {
        try (ClientViewFixture vanilla = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            vanilla.route();
            Set<UUID> none = new HashSet<UUID>();
            vanilla.clientView.projectingObservers(none);
            assertTrue(none.isEmpty());
        }
        try (ClientViewFixture fixture = negotiated()) {
            fixture.route();
            Set<UUID> observers = new HashSet<UUID>();
            fixture.clientView.projectingObservers(observers);
            assertEquals(Set.of(fixture.playerId), observers);
        }
    }

    private static ClientViewFixture negotiated() throws ClientViewProtocolException {
        return negotiated(ClientViewFixture.CLIENT_CAPS);
    }

    private static ClientViewFixture negotiated(long clientCaps) throws ClientViewProtocolException {
        ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY);
        fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
        assertTrue(fixture.negotiator.offerPlay(fixture.player));
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello(clientCaps));
        List<ClientViewMessage> handshake = fixture.messages();
        assertEquals(List.of(ViewStreamMessageType.OFFER, ViewStreamMessageType.ACCEPT), types(handshake));
        return fixture;
    }

    private static List<ViewStreamMessageType> types(List<ClientViewMessage> messages) {
        List<ViewStreamMessageType> types = new ArrayList<ViewStreamMessageType>(messages.size());
        for (ClientViewMessage message : messages) {
            types.add(message.type());
        }
        return types;
    }
}
