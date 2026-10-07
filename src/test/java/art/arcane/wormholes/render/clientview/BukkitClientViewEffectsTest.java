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

import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.optics.stream.ViewStreamInbound;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.FxMessage;

final class BukkitClientViewEffectsTest {
    private static final long FX_CAPS = ClientViewFixture.CLIENT_CAPS | ClientViewExtensions.FX_EMITTERS;

    @Test
    void particlesSkipReceiversAndHandThemTheClientEmitter() throws ViewStreamProtocolException {
        try (ClientViewFixture fixture = negotiated(FX_CAPS)) {
            Player nearby = vanilla(fixture, 4.0D);
            Player far = vanilla(fixture, 90.0D);
            when(fixture.world.getPlayers()).thenReturn(List.of(fixture.player, nearby, far));
            List<Player> vanilla = new ArrayList<Player>();
            FxMessage.FxEmitter burst = ClientViewEmitters.burst("minecraft:portal", 1.0D, 65.0D, 1.0D, 4, 0.45D, 0.65D, 0.18D);

            assertTrue(fixture.clientView.receiver(fixture.player));
            assertFalse(fixture.clientView.receiver(nearby));
            assertTrue(fixture.clientView.particles(fixture.world, 1.0D, 65.0D, 1.0D, vanilla::add, burst));

            assertEquals(List.of(nearby), vanilla, "vanilla players inside particle range keep their packets");
            List<FxMessage.Fx> fx = fx(fixture.messages());
            assertEquals(1, fx.size());
            assertEquals(FxMessage.WORLD_FX_KEY, fx.get(0).portalKey());
            assertEquals(List.of(burst), fx.get(0).emitters());
        }
    }

    @Test
    void sessionsWithoutFxLeaveTheWorldBroadcastAlone() throws ViewStreamProtocolException {
        try (ClientViewFixture fixture = negotiated(ClientViewFixture.CLIENT_CAPS)) {
            when(fixture.world.getPlayers()).thenReturn(List.of(fixture.player));
            assertFalse(fixture.clientView.receiver(fixture.player));
            assertFalse(fixture.clientView.hasReceivers(fixture.world));
            assertFalse(fixture.clientView.particles(fixture.world, 1.0D, 65.0D, 1.0D, player -> { }, null));
        }
    }

    @Test
    void ambientTouchesOpenAnEffectSlotWithoutOwningThePortal() throws ViewStreamProtocolException {
        try (ClientViewFixture fixture = negotiated(FX_CAPS)) {
            when(fixture.portal.getAmbientStyle()).thenReturn(AmbientParticleStyle.SPARKS);
            when(fixture.portal.getAmbientColor()).thenReturn(0xB969FF);
            fixture.clientView.touchNear(fixture.world, 1.0D, 65.0D, 0.5D, fixture.portal.getId());

            fixture.clientView.route(fixture.player, fixture.eye.clone(), new ArrayList<ILocalPortal>(), List.of(), Map.of(), ++fixture.tick);

            List<ViewStreamMessage> messages = fixture.messages();
            ViewStreamMessage.Portal portal = null;
            for (ViewStreamMessage message : messages) {
                if (message instanceof ViewStreamMessage.Portal announced) {
                    portal = announced;
                }
            }
            assertTrue(portal != null, "the effect slot announces the aperture: " + messages);
            assertFalse(portal.geometry().mirror());
            List<FxMessage.Fx> fx = fx(messages);
            assertEquals(1, fx.size());
            assertEquals(portal.portalKey(), fx.get(0).portalKey());
            assertEquals(FxMessage.FxKind.SURFACE, fx.get(0).emitters().get(0).kind());
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

    private static List<FxMessage.Fx> fx(List<ViewStreamMessage> messages) {
        List<FxMessage.Fx> out = new ArrayList<FxMessage.Fx>();
        for (ViewStreamMessage message : messages) {
            if (message instanceof ViewStreamMessage.Extension extension && extension.payload() instanceof FxMessage.Fx fx) {
                out.add(fx);
            }
        }
        return out;
    }

    private static ClientViewFixture negotiated(long clientCaps) throws ViewStreamProtocolException {
        ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY);
        when(fixture.player.getLocation()).thenAnswer(call -> fixture.eye.clone());
        fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
        assertTrue(fixture.negotiator.offerPlay(fixture.player));
        assertEquals(ViewStreamInbound.HELLO_ACCEPTED, fixture.hello(clientCaps));
        fixture.messages();
        return fixture;
    }
}
