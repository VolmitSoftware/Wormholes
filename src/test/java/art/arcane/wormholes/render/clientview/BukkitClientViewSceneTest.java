package art.arcane.wormholes.render.clientview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.ConnectionState;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.client.session.ClientViewInbound;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.render.view.ProjectionEntityView;

final class BukkitClientViewSceneTest {
    private final boolean oldLighting = Settings.LIGHTING_FIDELITY;
    private final double oldEntityRange = Settings.ENTITY_SPOOF_RANGE;

    @AfterEach
    void restore() {
        Settings.LIGHTING_FIDELITY = oldLighting;
        Settings.ENTITY_SPOOF_RANGE = oldEntityRange;
    }

    @Test
    void observerBindingMatchesItsOpaqueVisualBeforeTheFirstEntityFrame() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS | ClientViewCapability.ENTITY_SELF.mask())) {
            EntityVisual self = EntityVisual.full(fixture.playerId, "minecraft:player", 1.5D, 64.0D, 3.0D, 1.8D,
                0.0D, 0.0D, 1.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "Observer", "", "", null, null,
                EntityVisual.EMPTY, EntityVisual.EMPTY, 0);
            ProjectionEntityView entities = (ProjectionEntityView) fixture.view;
            when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(self));
            when(entities.isVisibleTo(any(Player.class), eq(fixture.playerId))).thenReturn(true);
            fixture.route();
            List<ClientViewMessage> messages = fixture.messages();
            ClientViewMessage.EntitySelf binding = null;
            for (ClientViewMessage message : messages) {
                if (message instanceof ClientViewMessage.EntitySelf found) {
                    binding = found;
                } else if (message instanceof ClientViewMessage.EntityFrame frame) {
                    assertNotNull(binding, "binding must precede the observer's first frame");
                    assertEquals(List.of(binding.projectedId()), frame.presentIds());
                    assertNotEquals(fixture.playerId, binding.projectedId());
                }
            }
            assertNotNull(binding);
            fixture.route();
            assertTrue(fixture.messages().stream().noneMatch(message -> message instanceof ClientViewMessage.EntitySelf));
        }
    }

    @Test
    void destinationLightRidesInThePlateBricksWhenLightingFidelityIsOn() throws ClientViewProtocolException {
        Settings.LIGHTING_FIDELITY = true;
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS)) {
            when(fixture.view.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ProjectionContentView.packLight(13, 5));
            fixture.route();
            fixture.buildPlates();
            fixture.route();
            int lit = 0;
            for (ClientViewMessage message : fixture.messages()) {
                if (!(message instanceof ClientViewMessage.PlateBricks bricks)) {
                    continue;
                }
                for (Brick brick : bricks.bricks()) {
                    if (!brick.hasLight()) {
                        continue;
                    }
                    for (int cell = 0; cell < ClientViewProtocol.BRICK_CELLS; cell++) {
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
    void destinationEntitiesStreamInLocalSpaceUnderOpaqueIdsAndFollowVisibility() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS)) {
            UUID source = UUID.randomUUID();
            EntityVisual stand = EntityVisual.full(source, "minecraft:armor_stand", 1.5D, 64.0D, 3.0D, 1.975D, 0.0D, 0.0D, 1.0D, 0.0F, 0.0F,
                0.0D, 0.0D, 0.0D, true, "", "", "", null, null, EntityVisual.EMPTY, EntityVisual.EMPTY, 0);
            ProjectionEntityView entities = (ProjectionEntityView) fixture.view;
            when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(stand));
            when(entities.isVisibleTo(any(Player.class), eq(source))).thenReturn(true);
            fixture.route();
            ClientViewMessage.EntityFrame frame = lastFrame(fixture.messages());
            assertNotNull(frame, "no entity frame was streamed");
            assertEquals(1, frame.entities().size());
            EntityVisual local = frame.entities().get(0);
            assertNotEquals(source, local.id());
            assertTrue(local.isFull());
            assertTrue(local.z() < 0.5D, "the reflected stand should sit behind the mirror plane, was " + local.z());
            assertEquals(List.of(local.id()), frame.presentIds());
            when(entities.isVisibleTo(any(Player.class), eq(source))).thenReturn(false);
            fixture.route();
            ClientViewMessage.EntityFrame hidden = lastFrame(fixture.messages());
            assertNotNull(hidden);
            assertTrue(hidden.presentIds().isEmpty(), "entities the observer cannot see leave the frame");
        }
    }

    @Test
    void clientDrawnMirrorsLeaveTheObserverOutOfItsOwnEntityFrames() throws ClientViewProtocolException {
        assertEquals(1, mirrorPresence(ClientViewFixture.CLIENT_CAPS | ClientViewCapability.CLIENT_MIRROR.mask()),
            "the client draws its own reflection");
        assertEquals(2, mirrorPresence(ClientViewFixture.CLIENT_CAPS), "a streamed mirror plate keeps the projected observer");
    }

    @Test
    void meshEntitiesUseClientDepthWhileLegacyEntitiesKeepPortalDepth() throws ClientViewProtocolException {
        Settings.ENTITY_SPOOF_RANGE = 128;
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS | ClientViewCapability.MESH_RENDER.mask())) {
            when(fixture.player.getClientViewDistance()).thenReturn(10);
            ProjectionEntityView entities = (ProjectionEntityView) fixture.view;
            EntityVisual stand = EntityVisual.full(UUID.randomUUID(), "minecraft:armor_stand", 1.5D, 64.0D, 111.0D, 1.975D,
                0.0D, 0.0D, 1.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null,
                EntityVisual.EMPTY, EntityVisual.EMPTY, 0);
            when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(stand));
            when(entities.isVisibleTo(any(Player.class), any(UUID.class))).thenReturn(true);
            fixture.route();
            ClientViewMessage.EntityFrame frame = lastFrame(fixture.messages());
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

    private static int mirrorPresence(long clientCaps) throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated(clientCaps)) {
            EntityVisual self = EntityVisual.full(fixture.playerId, "minecraft:player", 1.5D, 64.0D, 3.0D, 1.8D, 0.0D, 0.0D, 1.0D, 0.0F, 0.0F,
                0.0D, 0.0D, 0.0D, true, "", "", "", null, null, EntityVisual.EMPTY, EntityVisual.EMPTY, 0);
            EntityVisual stand = EntityVisual.full(UUID.randomUUID(), "minecraft:armor_stand", 0.5D, 64.0D, 3.0D, 1.975D, 0.0D, 0.0D, 1.0D, 0.0F,
                0.0F, 0.0D, 0.0D, 0.0D, true, "", "", "", null, null, EntityVisual.EMPTY, EntityVisual.EMPTY, 0);
            ProjectionEntityView entities = (ProjectionEntityView) fixture.view;
            when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(self, stand));
            when(entities.isVisibleTo(any(Player.class), any(UUID.class))).thenReturn(true);
            fixture.route();
            ClientViewMessage.EntityFrame frame = lastFrame(fixture.messages());
            assertNotNull(frame, "no entity frame was streamed");
            return frame.presentIds().size();
        }
    }

    private static ClientViewMessage.EntityFrame lastFrame(List<ClientViewMessage> messages) {
        ClientViewMessage.EntityFrame last = null;
        for (ClientViewMessage message : messages) {
            if (message instanceof ClientViewMessage.EntityFrame frame) {
                last = frame;
            }
        }
        return last;
    }

    private static ClientViewFixture negotiated(long clientCaps) throws ClientViewProtocolException {
        ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY);
        fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
        assertTrue(fixture.negotiator.offerPlay(fixture.player));
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello(clientCaps));
        List<ClientViewMessage> handshake = new ArrayList<ClientViewMessage>(fixture.messages());
        assertEquals(2, handshake.size());
        return fixture;
    }
}
