package art.arcane.wormholes.render.clientview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.ConnectionState;

import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.wormholes.render.client.session.ClientViewInbound;

final class BukkitClientViewEffectsTest {
    private static final long FX_CAPS = ClientViewFixture.CLIENT_CAPS | ClientViewCapability.FX_EMITTERS.mask();

    @Test
    void particlesSkipReceiversAndHandThemTheClientEmitter() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated(FX_CAPS)) {
            Player nearby = vanilla(fixture, 4.0D);
            Player far = vanilla(fixture, 90.0D);
            when(fixture.world.getPlayers()).thenReturn(List.of(fixture.player, nearby, far));
            List<Player> vanilla = new ArrayList<Player>();
            ClientViewMessage.FxEmitter burst = ClientViewEmitters.burst("minecraft:portal", 1.0D, 65.0D, 1.0D, 4, 0.45D, 0.65D, 0.18D);

            assertTrue(fixture.clientView.receiver(fixture.player));
            assertFalse(fixture.clientView.receiver(nearby));
            assertTrue(fixture.clientView.particles(fixture.world, 1.0D, 65.0D, 1.0D, vanilla::add, burst));

            assertEquals(List.of(nearby), vanilla, "vanilla players inside particle range keep their packets");
            List<ClientViewMessage.Fx> fx = fx(fixture.messages());
            assertEquals(1, fx.size());
            assertEquals(ClientViewProtocol.WORLD_FX_KEY, fx.get(0).portalKey());
            assertEquals(List.of(burst), fx.get(0).emitters());
        }
    }

    @Test
    void sessionsWithoutFxLeaveTheWorldBroadcastAlone() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS)) {
            when(fixture.world.getPlayers()).thenReturn(List.of(fixture.player));
            assertFalse(fixture.clientView.receiver(fixture.player));
            assertFalse(fixture.clientView.hasReceivers(fixture.world));
            assertFalse(fixture.clientView.particles(fixture.world, 1.0D, 65.0D, 1.0D, player -> { }, null));
        }
    }

    @Test
    void ambientTouchesOpenAnEffectSlotWithoutOwningThePortal() throws ClientViewProtocolException {
        try (ClientViewFixture fixture = negotiated(FX_CAPS)) {
            when(fixture.portal.getAmbientStyle()).thenReturn(AmbientParticleStyle.SPARKS);
            when(fixture.portal.getAmbientColor()).thenReturn(0xB969FF);
            fixture.clientView.touchNear(fixture.world, 1.0D, 65.0D, 0.5D, fixture.portal.getId());

            fixture.clientView.route(fixture.player, fixture.eye.clone(), new ArrayList<ILocalPortal>(), List.of(), Map.of(), ++fixture.tick);

            List<ClientViewMessage> messages = fixture.messages();
            ClientViewMessage.Portal portal = null;
            for (ClientViewMessage message : messages) {
                if (message instanceof ClientViewMessage.Portal announced) {
                    portal = announced;
                }
            }
            assertTrue(portal != null, "the effect slot announces the aperture: " + messages);
            assertFalse(portal.geometry().mirror());
            List<ClientViewMessage.Fx> fx = fx(messages);
            assertEquals(1, fx.size());
            assertEquals(portal.portalKey(), fx.get(0).portalKey());
            assertEquals(ClientViewMessage.FxKind.SURFACE, fx.get(0).emitters().get(0).kind());
            assertFalse(fixture.session().owns(fixture.portal.getId()));
            assertTrue(fixture.released.isEmpty(), "an effect slot never releases the vanilla projector");
            assertTrue(fixture.clientView.observer(fixture.playerId).attending());
        }
    }

    private static Player vanilla(ClientViewFixture fixture, double offset) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(fixture.world);
        when(player.getLocation()).thenReturn(new Location(fixture.world, 1.0D + offset, 65.0D, 1.0D));
        return player;
    }

    private static List<ClientViewMessage.Fx> fx(List<ClientViewMessage> messages) {
        List<ClientViewMessage.Fx> out = new ArrayList<ClientViewMessage.Fx>();
        for (ClientViewMessage message : messages) {
            if (message instanceof ClientViewMessage.Fx fx) {
                out.add(fx);
            }
        }
        return out;
    }

    private static ClientViewFixture negotiated(long clientCaps) throws ClientViewProtocolException {
        ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY);
        when(fixture.player.getLocation()).thenAnswer(call -> fixture.eye.clone());
        fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
        assertTrue(fixture.negotiator.offerPlay(fixture.player));
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello(clientCaps));
        fixture.messages();
        return fixture;
    }
}
