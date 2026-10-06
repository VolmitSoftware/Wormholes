package art.arcane.wormholes.render.clientview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.chunk.ChunkPacketAccess;

import com.github.retrooper.packetevents.protocol.ConnectionState;

import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewChannel;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewMessageType;
import art.arcane.wormholes.render.client.session.ClientViewInbound;
import art.arcane.wormholes.render.client.session.ClientViewSessionState;

final class BukkitClientViewNegotiatorTest {
    @Test
    void paperGreetingAdvertisesAndNegotiatesLocalMesh() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
            assertTrue(fixture.negotiator.offerPlay(fixture.player));
            assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello(ClientViewCapability.ALL));

            List<ClientViewMessage> messages = fixture.messages();
            assertEquals(List.of(ClientViewMessageType.OFFER, ClientViewMessageType.ACCEPT), types(messages));
            ClientViewMessage.Offer offer = (ClientViewMessage.Offer) messages.get(0);
            ClientViewMessage.Accept accept = (ClientViewMessage.Accept) messages.get(1);
            assertTrue(ClientViewCapability.LOCAL_MESH.in(offer.serverCaps()));
            assertTrue(ClientViewCapability.ENTITY_SELF.in(offer.serverCaps()));
            assertTrue(ClientViewCapability.LOCAL_MESH.in(accept.caps()));
            assertTrue(ClientViewCapability.ENTITY_SELF.in(accept.caps()));
            assertTrue(ClientViewCapability.MESH_RENDER.in(accept.caps()));
        }
    }

    @Test
    void exactNativeSnapshotCapabilityNegotiatesReuseAndOldPreparedPeersKeepNativeTransfer() throws Exception {
        ChunkPacketAccess packets = mock(ChunkPacketAccess.class);
        when(packets.snapshotSupported()).thenReturn(true);
        try (MockedStatic<NativeAdapters> adapters = mockStatic(NativeAdapters.class)) {
            adapters.when(() -> NativeAdapters.find(ChunkPacketAccess.class)).thenReturn(Optional.of(packets));
            for (boolean cache : new boolean[]{true, false}) {
                try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
                    fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
                    assertTrue(fixture.negotiator.offerPlay(fixture.player));
                    long caps = cache ? ClientViewCapability.ALL : ClientViewCapability.ALL & ~ClientViewCapability.PREPARED_TRAVEL_CACHE.mask();
                    assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello(caps));
                    List<ClientViewMessage> messages = fixture.messages();
                    ClientViewMessage.Offer offer = (ClientViewMessage.Offer) messages.get(0);
                    ClientViewMessage.Accept accept = (ClientViewMessage.Accept) messages.get(1);
                    assertTrue(ClientViewCapability.PREPARED_TRAVEL.in(offer.serverCaps()));
                    assertTrue(ClientViewCapability.PREPARED_TRAVEL_CACHE.in(offer.serverCaps()));
                    assertTrue(ClientViewCapability.PREPARED_TRAVEL.in(accept.caps()));
                    assertEquals(cache, ClientViewCapability.PREPARED_TRAVEL_CACHE.in(accept.caps()));
                    assertEquals(cache, fixture.session().preparedTravelCacheSelected());
                }
            }
        }
    }

    @Test
    void vanillaBrandIsNeverOffered() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, true, 5000), ConnectionState.CONFIGURATION)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("vanilla");

            assertEquals(ClientViewSessionState.VANILLA, fixture.negotiator.configure(fixture.playerId, fixture.user));
            fixture.user.setEncoderState(ConnectionState.PLAY);
            fixture.negotiator.on(join(fixture));

            assertTrue(fixture.drain().isEmpty());
            assertEquals(0, fixture.user.pings);
            assertNull(fixture.session());
            assertFalse(fixture.clientView.holdsVanilla(fixture.player, 1L));
        }
    }

    @Test
    void unknownBrandIsOfferedInConfigurationAndWaitsTheGrace() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, true, 120), ConnectionState.CONFIGURATION)) {
            long started = System.nanoTime();

            assertEquals(ClientViewSessionState.VANILLA, fixture.negotiator.configure(fixture.playerId, fixture.user));

            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 100L);
            List<ClientViewPacketEvents.Sent> sent = fixture.drain();
            assertEquals(2, sent.size());
            assertEquals(PacketEventsClientViewTransport.REGISTER_CHANNEL, sent.get(0).channel());
            assertArrayEquals(ClientViewChannel.CHANNEL.getBytes(StandardCharsets.UTF_8), sent.get(0).data());
            assertTrue(sent.get(1).configuration());
            ClientViewMessage.Offer offer = (ClientViewMessage.Offer) ClientViewCodec.decodeS2C(sent.get(1).data(), ClientViewCapability.ALL).message();
            assertEquals(ClientViewFixture.DATA_VERSION, offer.mcDataVersion());
            assertTrue(ClientViewCapability.CONFIG_PHASE.in(offer.serverCaps()));
            assertFalse(ClientViewCapability.ZERO_COPY.in(offer.serverCaps()));
            assertTrue(ClientViewCapability.DEST_LIGHT.in(offer.serverCaps()));
            assertTrue(ClientViewCapability.ENTITY_FRAMES.in(offer.serverCaps()));
            assertTrue(ClientViewCapability.FX_EMITTERS.in(offer.serverCaps()));
            assertTrue(ClientViewCapability.ATMOSPHERE.in(offer.serverCaps()));
            assertEquals(1, fixture.user.pings);
            assertTrue(fixture.verbose.get(0).contains("configuration handshake VANILLA"));
        }
    }

    @Test
    void moddedHelloDuringConfigurationAcceptsTheSession() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, true, 5000), ConnectionState.CONFIGURATION)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
            CompletableFuture<ClientViewSessionState> handshake = CompletableFuture.supplyAsync(
                () -> fixture.negotiator.configure(fixture.playerId, fixture.user));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (fixture.user.snapshot().size() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello());

            assertEquals(ClientViewSessionState.CLIENT_VIEW, handshake.get(5L, TimeUnit.SECONDS));
            List<ClientViewMessage> messages = fixture.messages();
            assertEquals(List.of(ClientViewMessageType.OFFER, ClientViewMessageType.ACCEPT), types(messages));
            ClientViewMessage.Accept accept = (ClientViewMessage.Accept) messages.get(1);
            assertTrue(ClientViewCapability.PLATES.in(accept.caps()));
            assertTrue(ClientViewCapability.CONFIG_PHASE.in(accept.caps()));
            assertTrue(ClientViewCapability.ENTITY_FRAMES.in(accept.caps()));
            assertFalse(ClientViewCapability.ZERO_COPY.in(accept.caps()));
        }
    }

    @Test
    void moddedBrandWithoutHelloFallsBackToVanillaAfterTheGrace() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, true, 120), ConnectionState.CONFIGURATION)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
            long started = System.nanoTime();

            assertEquals(ClientViewSessionState.VANILLA, fixture.negotiator.configure(fixture.playerId, fixture.user));

            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 100L);
        }
    }

    @Test
    void disabledClientViewNeverOffers() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(false, true, 100), ConnectionState.CONFIGURATION)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
            assertEquals(ClientViewSessionState.VANILLA, fixture.negotiator.configure(fixture.playerId, fixture.user));
            fixture.user.setEncoderState(ConnectionState.PLAY);
            fixture.negotiator.on(join(fixture));

            assertTrue(fixture.drain().isEmpty());
            assertNull(fixture.session());
        }
    }

    @Test
    void joinAfterAConfigurationOfferDoesNotOfferAgain() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, true, 20), ConnectionState.CONFIGURATION)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
            fixture.negotiator.configure(fixture.playerId, fixture.user);
            fixture.drain();
            fixture.user.setEncoderState(ConnectionState.PLAY);

            fixture.negotiator.on(join(fixture));

            assertTrue(fixture.drain().isEmpty());
            assertEquals(fixture.player, fixture.clientView.observer(fixture.playerId).player());
        }
    }

    @Test
    void playPhaseOfferHoldsTheVanillaProjectorUntilTheSessionSettles() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");

            fixture.negotiator.on(join(fixture));

            List<ClientViewPacketEvents.Sent> sent = fixture.drain();
            assertEquals(PacketEventsClientViewTransport.REGISTER_CHANNEL, sent.get(0).channel());
            assertFalse(sent.get(1).configuration());
            assertEquals(ClientViewMessageType.OFFER,
                ClientViewCodec.decodeS2C(sent.get(1).data(), ClientViewCapability.ALL).message().type());
            assertEquals(List.of(Long.valueOf(BukkitClientViewNegotiator.PLAY_EXPIRY_TICKS)), fixture.expiries);
            assertTrue(fixture.clientView.holdsVanilla(fixture.player, 1L));

            assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello());
            assertFalse(fixture.clientView.holdsVanilla(fixture.player, 2L));
            assertEquals(ClientViewSessionState.CLIENT_VIEW, fixture.session().state());
        }
    }

    @Test
    void unknownBrandAtJoinWaitsForAModdedBrand() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            fixture.negotiator.on(join(fixture));
            assertTrue(fixture.drain().isEmpty());
            assertFalse(fixture.clientView.holdsVanilla(fixture.player, 1L));

            ClientViewObserver observer = fixture.clientView.observer(fixture.playerId);
            observer.brand("fabric");
            fixture.negotiator.brandArrived(observer);
            assertEquals(List.of(Long.valueOf(1L)), fixture.expiries);
            fixture.expiryTasks.get(0).run();

            assertEquals(List.of(ClientViewMessageType.OFFER), types(fixture.messages()));
            assertTrue(fixture.clientView.holdsVanilla(fixture.player, 2L));
        }
    }

    @Test
    void registeringTheChannelOffersToAnyBrand() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("vanilla");
            fixture.negotiator.on(join(fixture));
            assertTrue(fixture.drain().isEmpty());

            fixture.negotiator.on(register(fixture, ClientViewChannel.CHANNEL));

            assertEquals(List.of(ClientViewMessageType.OFFER), types(fixture.messages()));
            fixture.negotiator.on(register(fixture, ClientViewChannel.CHANNEL));
            assertTrue(fixture.drain().isEmpty());
        }
    }

    @Test
    void lateHelloReleasesTheVanillaProjectionAndSwitches() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("vanilla");
            fixture.negotiator.on(join(fixture));
            fixture.negotiator.on(register(fixture, ClientViewChannel.CHANNEL));
            fixture.expiryTasks.get(0).run();
            assertEquals(ClientViewSessionState.VANILLA, fixture.session().state());
            assertEquals(List.of(fixture.portal), fixture.route());
            assertTrue(fixture.released.isEmpty());
            fixture.drain();

            assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello());
            assertTrue(fixture.route().isEmpty());

            assertEquals(1, fixture.released.size());
            assertEquals(1L, fixture.session().stats().lateSwitches());
            assertEquals(List.of(ClientViewMessageType.ACCEPT), types(fixture.messages()));
            fixture.buildPlates();
            fixture.route();
            assertTrue(types(fixture.messages()).contains(ClientViewMessageType.PORTAL));
        }
    }

    @Test
    void switchingClientViewBackOnOffersOnlineModdedClientsAgain() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            fixture.bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(ignored -> List.of(fixture.player));
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
            fixture.negotiator.on(join(fixture));
            assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello());
            fixture.drain();

            fixture.clientView.runtimeEnabled(false);
            fixture.clientView.runtimeEnabled(true);

            assertEquals(List.of(ClientViewMessageType.SESSION_RESET, ClientViewMessageType.OFFER), types(fixture.messages()));
            assertTrue(fixture.clientView.holdsVanilla(fixture.player, 1L));
            assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello());
            assertEquals(ClientViewSessionState.CLIENT_VIEW, fixture.session().state());
            assertEquals(List.of(ClientViewMessageType.ACCEPT), types(fixture.messages()));

            fixture.clientView.runtimeEnabled(true);
            assertTrue(fixture.messages().isEmpty());
        }
    }

    @Test
    void enablingThroughAReloadOffersClientsThatJoinedWhileItWasOff() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(false, false, 100), ConnectionState.PLAY)) {
            fixture.bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(ignored -> List.of(fixture.player));
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
            fixture.negotiator.on(join(fixture));
            assertTrue(fixture.drain().isEmpty());

            fixture.clientView.configure(ClientViewFixture.options(true, false, 100));

            assertEquals(List.of(ClientViewMessageType.OFFER), types(fixture.messages()));
            assertEquals(ClientViewInbound.HELLO_ACCEPTED, fixture.hello());
            assertEquals(ClientViewSessionState.CLIENT_VIEW, fixture.session().state());
        }
    }

    @Test
    void comingBackOnLeavesVanillaClientsWithoutTheChannelAlone() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(false, false, 100), ConnectionState.PLAY)) {
            fixture.bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(ignored -> List.of(fixture.player));
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("vanilla");
            fixture.negotiator.on(join(fixture));

            fixture.clientView.configure(ClientViewFixture.options(true, false, 100));

            assertTrue(fixture.drain().isEmpty());
            assertNull(fixture.session());
            assertFalse(fixture.clientView.holdsVanilla(fixture.player, 1L));
        }
    }

    @Test
    void quitForgetsTheSessionAndAReconnectRetiresTheStaleOne() throws Exception {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
            fixture.negotiator.on(join(fixture));
            fixture.hello();
            PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
            when(quit.getPlayer()).thenReturn(fixture.player);
            fixture.negotiator.on(quit);
            assertNull(fixture.clientView.observer(fixture.playerId));
            assertNull(fixture.session());

            fixture.clientView.observer(fixture.playerId, fixture.user).brand("fabric");
            fixture.negotiator.on(join(fixture));
            fixture.hello();
            ClientViewPacketEvents.RecordingUser reconnect = fixture.packets.user(fixture.playerId, "Observer", ConnectionState.CONFIGURATION);
            ClientViewObserver fresh = fixture.clientView.observer(fixture.playerId, reconnect);
            assertNull(fixture.session());

            fixture.negotiator.on(quit);
            assertEquals(fresh, fixture.clientView.observer(fixture.playerId));
        }
    }

    private static PlayerJoinEvent join(ClientViewFixture fixture) {
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(fixture.player);
        return event;
    }

    private static PlayerRegisterChannelEvent register(ClientViewFixture fixture, String channel) {
        PlayerRegisterChannelEvent event = mock(PlayerRegisterChannelEvent.class);
        when(event.getPlayer()).thenReturn(fixture.player);
        when(event.getChannel()).thenReturn(channel);
        return event;
    }

    private static List<ClientViewMessageType> types(List<ClientViewMessage> messages) {
        List<ClientViewMessageType> types = new ArrayList<ClientViewMessageType>(messages.size());
        for (ClientViewMessage message : messages) {
            types.add(message.type());
        }
        return types;
    }
}
