package art.arcane.wormholes.network.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientPreparedTravelReuseTest {
    @Test
    void clientPauseAndRouteReplacementKeepReplyDebtBoundedWhileNativeArrivalCompletes() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        List<ClientViewMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 16);
        ClientViewMessage.TravelBegin initial = withCoordinates(begin(1), coordinates);
        server.begin(initial, 0L);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        List<ClientViewMessage> sent = new ArrayList<>();
        for (int tick = 1; tick <= 30; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
        }
        List<ClientViewMessage.TravelReuse> delayed = offers(sent);
        assertEquals(ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK, delayed.size());
        ClientViewMessage.TravelEnd firstEnd = assertInstanceOf(ClientViewMessage.TravelEnd.class, sent.getLast());
        assertEquals(coordinates.size(), sent.stream().filter(ClientViewMessage.TravelChunk.class::isInstance).count());
        assertTrue(server.ready(new ClientViewMessage.TravelReady(initial.token(), initial.generation(), firstEnd.contentRevision())));
        server.cancel();
        ClientViewMessage.TravelBegin returning = withCoordinates(begin(2), coordinates);
        server.begin(returning, 1_500L);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        sent.clear();
        server.tick(1_550L, 128 * 1024, sent::add);
        assertTrue(offers(sent).isEmpty());
        ClientViewMessage.TravelEnd returnEnd = assertInstanceOf(ClientViewMessage.TravelEnd.class, sent.getLast());
        assertTrue(server.ready(new ClientViewMessage.TravelReady(returning.token(), returning.generation(), returnEnd.contentRevision())));
        for (ClientViewMessage.TravelReuse probe : delayed) {
            assertFalse(server.cached(ack(probe, true)));
        }
        server.close();
    }

    @Test
    void cancelledProofSlotsRequireExactLateAcknowledgmentsBeforeAnotherRouteCanProbe() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        List<ClientViewMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 3);
        ClientViewMessage.TravelBegin initial = withCoordinates(begin(1), coordinates);
        server.begin(initial, 0L);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        List<ClientViewMessage> sent = new ArrayList<>();
        for (int tick = 1; tick <= 5; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
        }
        List<ClientViewMessage.TravelReuse> delayed = offers(sent);
        assertEquals(ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK, delayed.size());
        server.cancel();
        server.begin(withCoordinates(begin(2), coordinates), 250L);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        for (ClientViewMessage.TravelReuse probe : delayed) {
            byte[] wrongHash = probe.hash();
            wrongHash[0] ^= 1;
            List<ClientViewMessage.TravelCached> invalid = List.of(
                new ClientViewMessage.TravelCached(UUID.randomUUID(), probe.generation(), probe.chunkX(), probe.chunkZ(), probe.revision(), probe.hash(), true),
                new ClientViewMessage.TravelCached(probe.token(), probe.generation() + 1, probe.chunkX(), probe.chunkZ(), probe.revision(), probe.hash(), true),
                new ClientViewMessage.TravelCached(probe.token(), probe.generation(), probe.chunkX() + 100, probe.chunkZ(), probe.revision(), probe.hash(), true),
                new ClientViewMessage.TravelCached(probe.token(), probe.generation(), probe.chunkX(), probe.chunkZ() + 100, probe.revision(), probe.hash(), true),
                new ClientViewMessage.TravelCached(probe.token(), probe.generation(), probe.chunkX(), probe.chunkZ(), probe.revision() + 1, probe.hash(), true),
                new ClientViewMessage.TravelCached(probe.token(), probe.generation(), probe.chunkX(), probe.chunkZ(), probe.revision(), wrongHash, true));
            for (ClientViewMessage.TravelCached reply : invalid) {
                assertFalse(server.cached(reply));
            }
        }
        sent.clear();
        server.tick(300L, 128 * 1024, sent::add);
        assertTrue(offers(sent).isEmpty());
        for (ClientViewMessage.TravelReuse probe : delayed) {
            assertFalse(server.cached(ack(probe, true)));
        }
        server.tick(350L, 128 * 1024, sent::add);
        assertTrue(offers(sent).size() > 0);
        assertTrue(offers(sent).size() <= ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
        server.close();
    }

    @Test
    void rejectedTransportOffersDoNotReserveReplySlotsOrConsumeTheSuccessfulProbeWindow() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        List<ClientViewMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 3);
        server.begin(withCoordinates(begin(1), coordinates), 0L);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        for (int attempt = 0; attempt < ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK; attempt++) {
            server.tick(50L, 128 * 1024, message -> !(message instanceof ClientViewMessage.TravelReuse));
        }
        List<ClientViewMessage> sent = new ArrayList<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            server.tick(50L, 128 * 1024, sent::add);
        }
        assertEquals(ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK, offers(sent).size());
        server.close();
    }

    @Test
    void immediateAcknowledgmentsAndCatchUpTicksCannotRestartTheWallTimeProbeWindow() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        List<ClientViewMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 16);
        server.begin(withCoordinates(begin(1), coordinates), 0L);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        List<ClientViewMessage.TravelReuse> sent = new ArrayList<>();
        for (int tick = 0; tick < 100; tick++) {
            server.tick(50L, 128 * 1024, message -> {
                if (message instanceof ClientViewMessage.TravelReuse probe) {
                    sent.add(probe);
                    assertTrue(server.cached(ack(probe, true)));
                }
                return true;
            });
        }
        assertEquals(ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK, sent.size());
        server.tick(99L, 128 * 1024, message -> {
            assertFalse(message instanceof ClientViewMessage.TravelReuse);
            return true;
        });
        server.tick(100L, 128 * 1024, message -> {
            if (message instanceof ClientViewMessage.TravelReuse probe) {
                sent.add(probe);
                assertTrue(server.cached(ack(probe, true)));
            }
            return true;
        });
        assertTrue(sent.size() > ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
        assertTrue(sent.size() <= ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK * 2);
        server.close();
    }

    @Test
    void hotPrefixCannotStarveUncapturedManifestTailAcrossBoundedBatches() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        List<ClientViewMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                coordinates.add(new ClientViewMessage.TravelCoordinate(x, z));
            }
        }
        ClientViewMessage.TravelBegin begin = withCoordinates(begin(1), coordinates);
        server.begin(begin, 0L);
        server.reuseSelected(true);
        List<ClientViewMessage> sent = new ArrayList<>();
        for (int batch = 0; batch < 13; batch++) {
            for (int hot = 0; hot < 4; hot++) {
                server.invalidate(coordinates.get(hot));
            }
            for (int slot = 0; slot < 4; slot++) {
                ClientViewMessage.TravelCoordinate coordinate = server.nextCapture();
                assertTrue(coordinate != null);
                assertTrue(server.column(coordinate, server.nextRevision(coordinate), new byte[]{1, 2, 3}));
            }
        }
        for (int index = 4; index < coordinates.size(); index++) {
            assertFalse(server.needs(coordinates.get(index)));
        }
        ClientViewMessage.TravelCoordinate coordinate;
        while ((coordinate = server.nextCapture()) != null) {
            assertTrue(server.column(coordinate, server.nextRevision(coordinate), new byte[]{1, 2, 3}));
        }
        for (int tick = 1; tick <= 49; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
            for (ClientViewMessage.TravelReuse offer : offers(sent)) {
                assertTrue(server.cached(ack(offer, true)));
            }
        }
        ClientViewMessage.TravelEnd end = assertInstanceOf(ClientViewMessage.TravelEnd.class, sent.getLast());
        assertEquals(49, end.chunks().size());
        assertTrue(server.ready(new ClientViewMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        server.begin(withCoordinates(begin(2), coordinates), 100L);
        assertEquals(coordinates.getFirst(), server.nextCapture());
        server.close();
    }

    @Test
    void cacheCapabilityRequiresNativeAndPreparedParentsFromBothPeers() {
        long[] masks = new long[]{ClientViewCapability.ALL,
            ClientViewCapability.ALL & ~ClientViewCapability.PREPARED_TRAVEL.mask(),
            ClientViewCapability.ALL & ~ClientViewCapability.MESH_RENDER.mask()};
        for (long serverCaps : masks) {
            for (long clientCaps : masks) {
                ClientViewHandshake.Policy policy = new ClientViewHandshake.Policy(true, 4325, serverCaps,
                    ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 100, 20, 8, false);
                ClientViewHandshake handshake = new ClientViewHandshake(policy, 0L, () -> 1, () -> 1L);
                ClientViewMessage.Offer offer = handshake.offer(0);
                ClientViewMessage.Hello hello = ClientViewHandshake.clientHello(offer, 4325, clientCaps,
                    ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 256, 0L, "fabric");
                ClientViewMessage.Accept accept = assertInstanceOf(ClientViewMessage.Accept.class,
                    handshake.onHello(hello, 1, true).reply());
                boolean expected = ClientViewCapability.PREPARED_TRAVEL.in(serverCaps & clientCaps)
                    && ClientViewCapability.MESH_RENDER.in(serverCaps & clientCaps);
                assertEquals(expected, ClientViewCapability.PREPARED_TRAVEL_CACHE.in(accept.caps()));
            }
        }
    }

    @Test
    void largeCapturedPayloadsDoNotEscapeWireBudgetOnCacheMiss() throws ClientViewProtocolException {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        ClientViewMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : begin.chunks()) {
            assertTrue(server.column(coordinate, 1, new byte[64 * 1024]));
        }
        List<ClientViewMessage> sent = new ArrayList<>();
        for (int tick = 1; tick <= 9; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
        }
        List<ClientViewMessage.TravelReuse> proofs = offers(sent);
        assertEquals(ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK, proofs.size());
        int proofBytes = 0;
        for (ClientViewMessage.TravelReuse proof : proofs) {
            proofBytes += ClientViewCodec.encodeS2C(proof, 1, 0).length;
        }
        assertTrue(proofBytes <= 128 * 1024);
        assertFalse(sent.stream().anyMatch(ClientViewMessage.TravelChunk.class::isInstance));
        for (ClientViewMessage.TravelReuse proof : proofs) {
            assertTrue(server.cached(ack(proof, false)));
        }
        sent.clear();
        server.tick(500L, 128 * 1024, sent::add);
        int wireBytes = 0;
        for (ClientViewMessage message : sent) {
            wireBytes += ClientViewCodec.encodeS2C(message, 1, 0).length;
        }
        assertTrue(sent.stream().anyMatch(ClientViewMessage.TravelChunk.class::isInstance));
        assertTrue(wireBytes <= 128 * 1024);
        assertFalse(sent.stream().anyMatch(ClientViewMessage.TravelEnd.class::isInstance));
        server.close();
    }

    @Test
    void freshEndAndReadyRequireEveryExactCacheAcknowledgment() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        ClientViewMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : begin.chunks()) {
            assertTrue(server.column(coordinate, 1, new byte[]{1, 2, 3}));
        }
        List<ClientViewMessage> sent = new ArrayList<>();
        for (int tick = 1; tick <= 9; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
        }
        List<ClientViewMessage.TravelReuse> offers = offers(sent);
        assertEquals(ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK, offers.size());
        assertFalse(sent.stream().anyMatch(ClientViewMessage.TravelChunk.class::isInstance));
        assertFalse(sent.stream().anyMatch(ClientViewMessage.TravelEnd.class::isInstance));
        for (int index = 0; index < offers.size() - 1; index++) {
            assertTrue(server.cached(ack(offers.get(index), true)));
        }
        assertTrue(server.cached(ack(offers.getLast(), true)));
        server.tick(500L, 128 * 1024, sent::add);
        assertFalse(sent.stream().anyMatch(ClientViewMessage.TravelEnd.class::isInstance));
        ClientViewMessage.TravelReuse last = offers(sent).getLast();
        assertEquals(9, offers(sent).size());
        assertTrue(server.cached(ack(last, true)));
        server.tick(550L, 128 * 1024, sent::add);
        ClientViewMessage.TravelEnd end = assertInstanceOf(ClientViewMessage.TravelEnd.class, sent.getLast());
        assertTrue(server.commit(commit(begin, 12)).isEmpty());
        assertTrue(server.ready(new ClientViewMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        assertTrue(server.commit(commit(begin, 12)).isPresent());
        assertTrue(server.preparing().isEmpty());
    }

    @Test
    void missesAndLostCacheAcknowledgmentsFallBackToAllNativeBytes() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        ClientViewMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        byte[] payload = new byte[ClientViewProtocol.TRAVEL_FRAGMENT_BYTES + 17];
        payload[0] = 42;
        for (ClientViewMessage.TravelCoordinate coordinate : begin.chunks()) {
            server.column(coordinate, 1, payload);
        }
        List<ClientViewMessage> sent = new ArrayList<>();
        server.tick(50L, 128 * 1024, sent::add);
        for (int tick = 2; tick <= 9; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
        }
        List<ClientViewMessage.TravelReuse> offers = offers(sent);
        assertEquals(ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK, offers.size());
        assertFalse(sent.stream().anyMatch(ClientViewMessage.TravelChunk.class::isInstance));
        assertFalse(sent.stream().anyMatch(ClientViewMessage.TravelEnd.class::isInstance));
        for (int index = 0; index < offers.size() - 1; index++) {
            assertTrue(server.cached(ack(offers.get(index), false)));
        }
        for (int tick = 0; tick < 32; tick++) {
            server.tick(1_500L + tick * 50L, 128 * 1024, sent::add);
        }
        assertInstanceOf(ClientViewMessage.TravelEnd.class, sent.getLast());
        assertEquals(18, sent.stream().filter(ClientViewMessage.TravelChunk.class::isInstance).count());
        ClientViewMessage.TravelChunk first = sent.stream().filter(ClientViewMessage.TravelChunk.class::isInstance)
            .map(ClientViewMessage.TravelChunk.class::cast).findFirst().orElseThrow();
        assertArrayEquals(Arrays.copyOf(payload, ClientViewProtocol.TRAVEL_FRAGMENT_BYTES), first.payload());
        assertFalse(server.cached(ack(offers.getLast(), true)));
    }

    @Test
    void freshAndValidatedWarmPayloadsBothUseBoundedProbeBatches() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        ProjectionWorldChangeTracker changes = new ProjectionWorldChangeTracker();
        UUID world = UUID.randomUUID();
        ClientViewMessage.TravelBegin initial = begin(1);
        List<ClientViewMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                coordinates.add(new ClientViewMessage.TravelCoordinate(x, z));
            }
        }
        ClientViewMessage.TravelBegin begin = withCoordinates(initial, coordinates);
        server.begin(begin, 0);
        server.watchWorld(changes, world);
        server.reuseSelected(true);
        byte[] bytes = new byte[32 * 1024];
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, bytes));
        }
        List<ClientViewMessage> sent = new ArrayList<>();
        server.tick(50L, 128 * 1024, sent::add);
        assertTrue(offers(sent).size() > 0);
        assertTrue(offers(sent).size() <= ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
        for (ClientViewMessage.TravelReuse offer : offers(sent)) {
            assertTrue(server.cached(ack(offer, true)));
        }
        for (int tick = 2; tick <= 49; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
            for (ClientViewMessage.TravelReuse offer : offers(sent)) {
                assertTrue(server.cached(ack(offer, true)));
            }
        }
        assertEquals(49, offers(sent).size());
        server.cancel();
        server.begin(withCoordinates(begin(2), coordinates), 2_500);
        server.watchWorld(changes, world);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, bytes.clone()));
        }
        sent.clear();
        server.tick(2550L, 128 * 1024, sent::add);
        assertTrue(offers(sent).size() > 0);
        assertTrue(offers(sent).size() <= ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
        for (ClientViewMessage.TravelReuse offer : offers(sent)) {
            assertTrue(server.cached(ack(offer, true)));
        }
        for (int tick = 52; tick <= 100; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
            for (ClientViewMessage.TravelReuse offer : offers(sent)) {
                assertTrue(server.cached(ack(offer, true)));
            }
        }
        assertEquals(49, offers(sent).size());
        assertFalse(sent.stream().anyMatch(ClientViewMessage.TravelChunk.class::isInstance));
    }

    @Test
    void maximumArrivalHorizonPacesWarmRepliesAndStillCompletesCurrentProofBarrier() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        ProjectionWorldChangeTracker changes = new ProjectionWorldChangeTracker();
        UUID world = UUID.randomUUID();
        List<ClientViewMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 16);
        byte[] payload = new byte[]{1, 2, 3};
        ClientViewMessage.TravelBegin initial = withCoordinates(begin(1), coordinates);
        server.begin(initial, 0);
        server.watchWorld(changes, world);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, payload));
        }
        List<ClientViewMessage> sent = new ArrayList<>();
        int offered = 0;
        for (int tick = 1; tick <= coordinates.size() && offered < coordinates.size(); tick++) {
            sent.clear();
            server.tick(tick * 50L, 128 * 1024, sent::add);
            List<ClientViewMessage.TravelReuse> probes = offers(sent);
            assertTrue(probes.size() <= ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
            offered += probes.size();
            for (ClientViewMessage.TravelReuse probe : probes) {
                assertTrue(server.cached(ack(probe, true)));
            }
        }
        assertEquals(ClientViewProtocol.MAX_TRAVEL_CHUNKS, offered);
        server.cancel();
        ClientViewMessage.TravelBegin returning = withCoordinates(begin(2), coordinates);
        server.begin(returning, 20_000);
        server.watchWorld(changes, world);
        server.reuseSelected(true);
        for (int index = coordinates.size() - 256; index < coordinates.size(); index++) {
            assertTrue(server.column(coordinates.get(index), 1, payload));
        }
        for (int index = 0; index < coordinates.size() - 256; index++) {
            assertTrue(server.column(coordinates.get(index), 1, payload));
        }
        offered = 0;
        ClientViewMessage.TravelEnd end = null;
        for (int tick = 1; tick <= coordinates.size() + 1 && end == null; tick++) {
            sent.clear();
            server.tick(20_000L + tick * 50L, 128 * 1024, sent::add);
            List<ClientViewMessage.TravelReuse> probes = offers(sent);
            assertTrue(probes.size() <= ClientViewProtocol.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
            offered += probes.size();
            for (ClientViewMessage.TravelReuse probe : probes) {
                assertTrue(server.cached(ack(probe, true)));
            }
            for (ClientViewMessage message : sent) {
                if (message instanceof ClientViewMessage.TravelEnd barrier) {
                    end = barrier;
                }
            }
        }
        assertEquals(ClientViewProtocol.MAX_TRAVEL_CHUNKS, offered);
        assertTrue(end != null);
        assertEquals(ClientViewProtocol.MAX_TRAVEL_CHUNKS, end.chunks().size());
        assertTrue(server.ready(new ClientViewMessage.TravelReady(returning.token(), returning.generation(), end.contentRevision())));
        server.close();
    }

    @Test
    void changedCurrentNativeBytesRejectEarlierRevisionAndDigest() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        ClientViewMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        ClientViewMessage.TravelCoordinate coordinate = begin.chunks().getFirst();
        server.column(coordinate, 1, new byte[]{1, 2, 3});
        List<ClientViewMessage> sent = new ArrayList<>();
        server.tick(50L, 128 * 1024, sent::add);
        ClientViewMessage.TravelReuse old = offers(sent).getFirst();
        server.invalidate(coordinate);
        assertFalse(server.cached(ack(old, true)));
        server.column(coordinate, 2, new byte[]{1, 2, 4});
        sent.clear();
        server.tick(100L, 128 * 1024, sent::add);
        ClientViewMessage.TravelReuse current = offers(sent).getFirst();
        assertFalse(Arrays.equals(old.hash(), current.hash()));
        assertFalse(server.cached(ack(old, true)));
        assertFalse(server.cached(new ClientViewMessage.TravelCached(current.token(), current.generation(), current.chunkX(),
            current.chunkZ(), current.revision(), old.hash(), true)));
        assertTrue(server.cached(ack(current, true)));
        assertTrue(server.cached(ack(current, true)));
        assertFalse(server.cached(ack(current, false)));
    }

    @Test
    void tokenGenerationCoordinatesAndUnnegotiatedPeersCannotClaimReuse() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        ClientViewMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        ClientViewMessage.TravelCoordinate coordinate = begin.chunks().getFirst();
        server.column(coordinate, 1, new byte[]{1});
        List<ClientViewMessage> sent = new ArrayList<>();
        server.tick(50L, 128 * 1024, sent::add);
        ClientViewMessage.TravelReuse old = offers(sent).getFirst();
        assertFalse(server.cached(new ClientViewMessage.TravelCached(UUID.randomUUID(), old.generation(), old.chunkX(), old.chunkZ(), 1, old.hash(), true)));
        assertFalse(server.cached(new ClientViewMessage.TravelCached(old.token(), old.generation() + 1, old.chunkX(), old.chunkZ(), 1, old.hash(), true)));
        assertFalse(server.cached(new ClientViewMessage.TravelCached(old.token(), old.generation(), 100, 100, 1, old.hash(), true)));
        server.begin(begin(2), 2);
        server.column(coordinate, 1, new byte[]{1});
        assertFalse(server.cached(ack(old, true)));
        sent.clear();
        server.tick(150L, 128 * 1024, sent::add);
        assertFalse(sent.stream().anyMatch(ClientViewMessage.TravelReuse.class::isInstance));
        assertTrue(sent.stream().anyMatch(ClientViewMessage.TravelChunk.class::isInstance));
    }

    @Test
    void probesRespectControlByteBudgetAndDuplicatesDoNotEmitAnotherBarrier() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        ClientViewMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        for (ClientViewMessage.TravelCoordinate coordinate : begin.chunks()) {
            server.column(coordinate, 1, new byte[]{1});
        }
        List<ClientViewMessage> sent = new ArrayList<>();
        for (int tick = 1; tick <= 9; tick++) {
            int before = offers(sent).size();
            server.tick(tick * 50L, ClientViewProtocol.TRAVEL_REUSE_BYTES, sent::add);
            assertEquals(before + 1, offers(sent).size());
            assertTrue(server.cached(ack(offers(sent).getLast(), true)));
        }
        for (ClientViewMessage.TravelReuse offer : offers(sent)) {
            assertTrue(server.cached(ack(offer, true)));
        }
        server.tick(500L, ClientViewProtocol.TRAVEL_REUSE_BYTES, sent::add);
        assertEquals(1, sent.stream().filter(ClientViewMessage.TravelEnd.class::isInstance).count());
        for (ClientViewMessage.TravelReuse offer : offers(sent)) {
            assertTrue(server.cached(ack(offer, true)));
        }
        server.tick(550L, ClientViewProtocol.TRAVEL_REUSE_BYTES, sent::add);
        assertEquals(1, sent.stream().filter(ClientViewMessage.TravelEnd.class::isInstance).count());
    }

    @Test
    void immutablePayloadStorageSurvivesTransactionClearButPartitionsWorldAndSession() throws ReflectiveOperationException {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        ProjectionWorldChangeTracker changes = new ProjectionWorldChangeTracker();
        UUID world = UUID.randomUUID();
        ClientViewMessage.TravelBegin begin = begin(1);
        ClientViewMessage.TravelCoordinate coordinate = begin.chunks().getFirst();
        server.begin(begin, 0);
        server.watchWorld(changes, world);
        byte[] input = new byte[]{1, 2, 3};
        server.column(coordinate, 1, input);
        Object original = retained(server).values().iterator().next();
        input[0] = 9;
        server.cancel();
        server.begin(begin(2), 1);
        server.watchWorld(changes, world);
        server.column(coordinate, 1, new byte[]{1, 2, 3});
        assertSame(original, retained(server).values().iterator().next());
        server.cancel();
        server.begin(begin(3), 2);
        server.watchWorld(changes, UUID.randomUUID());
        server.column(coordinate, 1, new byte[]{1, 2, 3});
        assertEquals(2, retained(server).size());
        ClientPreparedTravelServer other = new ClientPreparedTravelServer();
        other.begin(begin(4), 0);
        other.watchWorld(changes, world);
        other.column(coordinate, 1, new byte[]{1, 2, 3});
        assertNotSame(original, retained(other).values().iterator().next());
        server.close();
        assertTrue(retained(server).isEmpty());
    }

    @Test
    void retainedPayloadEntryBudgetEvictsOlderWorldsAndWorldClearRevokesCurrentCache() throws ReflectiveOperationException {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        ProjectionWorldChangeTracker changes = new ProjectionWorldChangeTracker();
        Object first = null;
        UUID world = null;
        for (int index = 0; index < 257; index++) {
            ClientViewMessage.TravelBegin begin = begin(index + 1);
            world = new UUID(0, index + 1);
            server.begin(begin, 0);
            server.watchWorld(changes, world);
            server.column(begin.chunks().getFirst(), 1, new byte[]{1});
            if (index == 0) {
                first = retained(server).values().iterator().next();
            }
        }
        assertEquals(256, retained(server).size());
        assertFalse(retained(server).values().contains(first));
        changes.clearWorld(world);
        assertEquals(255, retained(server).size());
        assertFalse(server.column(begin(257).chunks().getFirst(), 2, new byte[]{2}));
        server.close();
        assertTrue(retained(server).isEmpty());
    }

    private static Map<?, ?> retained(ClientPreparedTravelServer server) throws ReflectiveOperationException {
        Field field = ClientPreparedTravelServer.class.getDeclaredField("retained");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(server);
    }

    private static List<ClientViewMessage.TravelReuse> offers(List<ClientViewMessage> sent) {
        return sent.stream().filter(ClientViewMessage.TravelReuse.class::isInstance)
            .map(ClientViewMessage.TravelReuse.class::cast).toList();
    }

    private static ClientViewMessage.TravelCached ack(ClientViewMessage.TravelReuse offer, boolean available) {
        return new ClientViewMessage.TravelCached(offer.token(), offer.generation(), offer.chunkX(), offer.chunkZ(),
            offer.revision(), offer.hash(), available);
    }

    private static ClientPreparedTravelServer.Commit commit(ClientViewMessage.TravelBegin begin, long time) {
        return new ClientPreparedTravelServer.Commit(begin.sourcePortal(), begin.sourceWorld(), begin.world().dimension(),
            begin.arrival(), new GeometryVector(0, 0, 0), time);
    }

    private static ClientViewMessage.TravelBegin withCoordinates(ClientViewMessage.TravelBegin begin,
                                                                 List<ClientViewMessage.TravelCoordinate> coordinates) {
        return new ClientViewMessage.TravelBegin(begin.token(), begin.generation(), begin.sourcePortal(), begin.sourceWorld(),
            begin.sourceGeometry(), begin.destinationToSource(), begin.world(), begin.arrival(), coordinates,
            begin.environment(), begin.expiresMillis());
    }

    private static ClientViewMessage.TravelBegin begin(long generation) {
        ClientViewMessage.TravelBegin fixture = ClientViewFixtures.travelBegin();
        List<ClientViewMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                coordinates.add(new ClientViewMessage.TravelCoordinate(x, z));
            }
        }
        return new ClientViewMessage.TravelBegin(new UUID(0, generation), generation, fixture.sourcePortal(), fixture.sourceWorld(),
            fixture.sourceGeometry(), fixture.destinationToSource(), fixture.world(), new ClientViewMessage.TravelPose(0, 64, 0, 0, 0),
            coordinates, fixture.environment(), fixture.expiresMillis());
    }
}
