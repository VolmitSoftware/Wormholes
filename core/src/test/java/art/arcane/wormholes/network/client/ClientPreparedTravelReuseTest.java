package art.arcane.wormholes.network.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.view.WorldChangeTracker;
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
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamHandshake;
import art.arcane.optics.stream.ViewStreamMessage;

final class ClientPreparedTravelReuseTest {
    @Test
    void clientPauseAndRouteReplacementKeepReplyDebtBoundedWhileNativeArrivalCompletes() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        List<TravelMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 16);
        TravelMessage.TravelBegin initial = withCoordinates(begin(1), coordinates);
        server.begin(initial, 0L);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        List<TravelMessage> sent = new ArrayList<>();
        for (int tick = 1; tick <= 30; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
        }
        List<TravelMessage.TravelReuse> delayed = offers(sent);
        assertEquals(TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK, delayed.size());
        TravelMessage.TravelEnd firstEnd = assertInstanceOf(TravelMessage.TravelEnd.class, sent.getLast());
        assertEquals(coordinates.size(), sent.stream().filter(TravelMessage.TravelChunk.class::isInstance).count());
        assertTrue(server.ready(new TravelMessage.TravelReady(initial.token(), initial.generation(), firstEnd.contentRevision())));
        server.cancel();
        TravelMessage.TravelBegin returning = withCoordinates(begin(2), coordinates);
        server.begin(returning, 1_500L);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        sent.clear();
        server.tick(1_550L, 128 * 1024, sent::add);
        assertTrue(offers(sent).isEmpty());
        TravelMessage.TravelEnd returnEnd = assertInstanceOf(TravelMessage.TravelEnd.class, sent.getLast());
        assertTrue(server.ready(new TravelMessage.TravelReady(returning.token(), returning.generation(), returnEnd.contentRevision())));
        for (TravelMessage.TravelReuse probe : delayed) {
            assertFalse(server.cached(ack(probe, true)));
        }
        server.close();
    }

    @Test
    void cancelledProofSlotsRequireExactLateAcknowledgmentsBeforeAnotherRouteCanProbe() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        List<TravelMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 3);
        TravelMessage.TravelBegin initial = withCoordinates(begin(1), coordinates);
        server.begin(initial, 0L);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        List<TravelMessage> sent = new ArrayList<>();
        for (int tick = 1; tick <= 5; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
        }
        List<TravelMessage.TravelReuse> delayed = offers(sent);
        assertEquals(TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK, delayed.size());
        server.cancel();
        server.begin(withCoordinates(begin(2), coordinates), 250L);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        for (TravelMessage.TravelReuse probe : delayed) {
            byte[] wrongHash = probe.hash();
            wrongHash[0] ^= 1;
            List<TravelMessage.TravelCached> invalid = List.of(
                new TravelMessage.TravelCached(UUID.randomUUID(), probe.generation(), probe.chunkX(), probe.chunkZ(), probe.revision(), probe.hash(), true),
                new TravelMessage.TravelCached(probe.token(), probe.generation() + 1, probe.chunkX(), probe.chunkZ(), probe.revision(), probe.hash(), true),
                new TravelMessage.TravelCached(probe.token(), probe.generation(), probe.chunkX() + 100, probe.chunkZ(), probe.revision(), probe.hash(), true),
                new TravelMessage.TravelCached(probe.token(), probe.generation(), probe.chunkX(), probe.chunkZ() + 100, probe.revision(), probe.hash(), true),
                new TravelMessage.TravelCached(probe.token(), probe.generation(), probe.chunkX(), probe.chunkZ(), probe.revision() + 1, probe.hash(), true),
                new TravelMessage.TravelCached(probe.token(), probe.generation(), probe.chunkX(), probe.chunkZ(), probe.revision(), wrongHash, true));
            for (TravelMessage.TravelCached reply : invalid) {
                assertFalse(server.cached(reply));
            }
        }
        sent.clear();
        server.tick(300L, 128 * 1024, sent::add);
        assertTrue(offers(sent).isEmpty());
        for (TravelMessage.TravelReuse probe : delayed) {
            assertFalse(server.cached(ack(probe, true)));
        }
        server.tick(350L, 128 * 1024, sent::add);
        assertTrue(offers(sent).size() > 0);
        assertTrue(offers(sent).size() <= TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
        server.close();
    }

    @Test
    void rejectedTransportOffersDoNotReserveReplySlotsOrConsumeTheSuccessfulProbeWindow() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        List<TravelMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 3);
        server.begin(withCoordinates(begin(1), coordinates), 0L);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        for (int attempt = 0; attempt < TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK; attempt++) {
            server.tick(50L, 128 * 1024, message -> !(message instanceof TravelMessage.TravelReuse));
        }
        List<TravelMessage> sent = new ArrayList<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            server.tick(50L, 128 * 1024, sent::add);
        }
        assertEquals(TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK, offers(sent).size());
        server.close();
    }

    @Test
    void immediateAcknowledgmentsAndCatchUpTicksCannotRestartTheWallTimeProbeWindow() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        List<TravelMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 16);
        server.begin(withCoordinates(begin(1), coordinates), 0L);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, new byte[]{1}));
        }
        List<TravelMessage.TravelReuse> sent = new ArrayList<>();
        for (int tick = 0; tick < 100; tick++) {
            server.tick(50L, 128 * 1024, message -> {
                if (message instanceof TravelMessage.TravelReuse probe) {
                    sent.add(probe);
                    assertTrue(server.cached(ack(probe, true)));
                }
                return true;
            });
        }
        assertEquals(TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK, sent.size());
        server.tick(99L, 128 * 1024, message -> {
            assertFalse(message instanceof TravelMessage.TravelReuse);
            return true;
        });
        server.tick(100L, 128 * 1024, message -> {
            if (message instanceof TravelMessage.TravelReuse probe) {
                sent.add(probe);
                assertTrue(server.cached(ack(probe, true)));
            }
            return true;
        });
        assertTrue(sent.size() > TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
        assertTrue(sent.size() <= TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK * 2);
        server.close();
    }

    @Test
    void hotPrefixCannotStarveUncapturedManifestTailAcrossBoundedBatches() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                coordinates.add(new TravelMessage.TravelCoordinate(x, z));
            }
        }
        TravelMessage.TravelBegin begin = withCoordinates(begin(1), coordinates);
        server.begin(begin, 0L);
        server.reuseSelected(true);
        List<TravelMessage> sent = new ArrayList<>();
        for (int batch = 0; batch < 13; batch++) {
            for (int hot = 0; hot < 4; hot++) {
                server.invalidate(coordinates.get(hot));
            }
            for (int slot = 0; slot < 4; slot++) {
                TravelMessage.TravelCoordinate coordinate = server.nextCapture();
                assertTrue(coordinate != null);
                assertTrue(server.column(coordinate, server.nextRevision(coordinate), new byte[]{1, 2, 3}));
            }
        }
        for (int index = 4; index < coordinates.size(); index++) {
            assertFalse(server.needs(coordinates.get(index)));
        }
        TravelMessage.TravelCoordinate coordinate;
        while ((coordinate = server.nextCapture()) != null) {
            assertTrue(server.column(coordinate, server.nextRevision(coordinate), new byte[]{1, 2, 3}));
        }
        for (int tick = 1; tick <= 49; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
            for (TravelMessage.TravelReuse offer : offers(sent)) {
                assertTrue(server.cached(ack(offer, true)));
            }
        }
        TravelMessage.TravelEnd end = assertInstanceOf(TravelMessage.TravelEnd.class, sent.getLast());
        assertEquals(49, end.chunks().size());
        assertTrue(server.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        server.begin(withCoordinates(begin(2), coordinates), 100L);
        assertEquals(coordinates.getFirst(), server.nextCapture());
        server.close();
    }

    @Test
    void cacheCapabilityRequiresNativeAndPreparedParentsFromBothPeers() {
        long[] masks = new long[]{ViewStreamCapability.ALL,
            ViewStreamCapability.ALL & ~ViewStreamCapability.PREPARED_TRAVEL.mask(),
            ViewStreamCapability.ALL & ~ViewStreamCapability.MESH_RENDER.mask()};
        for (long serverCaps : masks) {
            for (long clientCaps : masks) {
                ViewStreamHandshake.Policy policy = new ViewStreamHandshake.Policy(true, 4325, serverCaps,
                    ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 100, 20, 8, false);
                ViewStreamHandshake handshake = new ViewStreamHandshake(policy, 0L, () -> 1, () -> 1L);
                ViewStreamMessage.Offer offer = handshake.offer(0);
                ViewStreamMessage.Hello hello = ViewStreamHandshake.clientHello(offer, 4325, clientCaps,
                    ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 256, 0L, "fabric");
                ViewStreamMessage.Accept accept = assertInstanceOf(ViewStreamMessage.Accept.class,
                    handshake.onHello(hello, 1, true).reply());
                boolean expected = ViewStreamCapability.PREPARED_TRAVEL.in(serverCaps & clientCaps)
                    && ViewStreamCapability.MESH_RENDER.in(serverCaps & clientCaps);
                assertEquals(expected, ViewStreamCapability.PREPARED_TRAVEL_CACHE.in(accept.caps()));
            }
        }
    }

    @Test
    void largeCapturedPayloadsDoNotEscapeWireBudgetOnCacheMiss() throws ViewStreamProtocolException {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
            assertTrue(server.column(coordinate, 1, new byte[64 * 1024]));
        }
        List<TravelMessage> sent = new ArrayList<>();
        for (int tick = 1; tick <= 9; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
        }
        List<TravelMessage.TravelReuse> proofs = offers(sent);
        assertEquals(TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK, proofs.size());
        int proofBytes = 0;
        for (TravelMessage.TravelReuse proof : proofs) {
            proofBytes += ClientViewExtensions.CODEC.encodeS2C(TravelExtension.INSTANCE.wrap(proof), 1, 0).length;
        }
        assertTrue(proofBytes <= 128 * 1024);
        assertFalse(sent.stream().anyMatch(TravelMessage.TravelChunk.class::isInstance));
        for (TravelMessage.TravelReuse proof : proofs) {
            assertTrue(server.cached(ack(proof, false)));
        }
        sent.clear();
        server.tick(500L, 128 * 1024, sent::add);
        int wireBytes = 0;
        for (TravelMessage message : sent) {
            wireBytes += ClientViewExtensions.CODEC.encodeS2C(TravelExtension.INSTANCE.wrap(message), 1, 0).length;
        }
        assertTrue(sent.stream().anyMatch(TravelMessage.TravelChunk.class::isInstance));
        assertTrue(wireBytes <= 128 * 1024);
        assertFalse(sent.stream().anyMatch(TravelMessage.TravelEnd.class::isInstance));
        server.close();
    }

    @Test
    void freshEndAndReadyRequireEveryExactCacheAcknowledgment() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
            assertTrue(server.column(coordinate, 1, new byte[]{1, 2, 3}));
        }
        List<TravelMessage> sent = new ArrayList<>();
        for (int tick = 1; tick <= 9; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
        }
        List<TravelMessage.TravelReuse> offers = offers(sent);
        assertEquals(TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK, offers.size());
        assertFalse(sent.stream().anyMatch(TravelMessage.TravelChunk.class::isInstance));
        assertFalse(sent.stream().anyMatch(TravelMessage.TravelEnd.class::isInstance));
        for (int index = 0; index < offers.size() - 1; index++) {
            assertTrue(server.cached(ack(offers.get(index), true)));
        }
        assertTrue(server.cached(ack(offers.getLast(), true)));
        server.tick(500L, 128 * 1024, sent::add);
        assertFalse(sent.stream().anyMatch(TravelMessage.TravelEnd.class::isInstance));
        TravelMessage.TravelReuse last = offers(sent).getLast();
        assertEquals(9, offers(sent).size());
        assertTrue(server.cached(ack(last, true)));
        server.tick(550L, 128 * 1024, sent::add);
        TravelMessage.TravelEnd end = assertInstanceOf(TravelMessage.TravelEnd.class, sent.getLast());
        assertTrue(server.commit(commit(begin, 12)).isEmpty());
        assertTrue(server.ready(new TravelMessage.TravelReady(begin.token(), begin.generation(), end.contentRevision())));
        assertTrue(server.commit(commit(begin, 12)).isPresent());
        assertTrue(server.preparing().isEmpty());
    }

    @Test
    void missesAndLostCacheAcknowledgmentsFallBackToAllNativeBytes() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        byte[] payload = new byte[TravelMessage.TRAVEL_FRAGMENT_BYTES + 17];
        payload[0] = 42;
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
            server.column(coordinate, 1, payload);
        }
        List<TravelMessage> sent = new ArrayList<>();
        server.tick(50L, 128 * 1024, sent::add);
        for (int tick = 2; tick <= 9; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
        }
        List<TravelMessage.TravelReuse> offers = offers(sent);
        assertEquals(TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK, offers.size());
        assertFalse(sent.stream().anyMatch(TravelMessage.TravelChunk.class::isInstance));
        assertFalse(sent.stream().anyMatch(TravelMessage.TravelEnd.class::isInstance));
        for (int index = 0; index < offers.size() - 1; index++) {
            assertTrue(server.cached(ack(offers.get(index), false)));
        }
        for (int tick = 0; tick < 32; tick++) {
            server.tick(1_500L + tick * 50L, 128 * 1024, sent::add);
        }
        assertInstanceOf(TravelMessage.TravelEnd.class, sent.getLast());
        assertEquals(18, sent.stream().filter(TravelMessage.TravelChunk.class::isInstance).count());
        TravelMessage.TravelChunk first = sent.stream().filter(TravelMessage.TravelChunk.class::isInstance)
            .map(TravelMessage.TravelChunk.class::cast).findFirst().orElseThrow();
        assertArrayEquals(Arrays.copyOf(payload, TravelMessage.TRAVEL_FRAGMENT_BYTES), first.payload());
        assertFalse(server.cached(ack(offers.getLast(), true)));
    }

    @Test
    void freshAndValidatedWarmPayloadsBothUseBoundedProbeBatches() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        WorldChangeTracker changes = new WorldChangeTracker();
        UUID world = UUID.randomUUID();
        TravelMessage.TravelBegin initial = begin(1);
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                coordinates.add(new TravelMessage.TravelCoordinate(x, z));
            }
        }
        TravelMessage.TravelBegin begin = withCoordinates(initial, coordinates);
        server.begin(begin, 0);
        server.watchWorld(changes, world);
        server.reuseSelected(true);
        byte[] bytes = new byte[32 * 1024];
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, bytes));
        }
        List<TravelMessage> sent = new ArrayList<>();
        server.tick(50L, 128 * 1024, sent::add);
        assertTrue(offers(sent).size() > 0);
        assertTrue(offers(sent).size() <= TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
        for (TravelMessage.TravelReuse offer : offers(sent)) {
            assertTrue(server.cached(ack(offer, true)));
        }
        for (int tick = 2; tick <= 49; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
            for (TravelMessage.TravelReuse offer : offers(sent)) {
                assertTrue(server.cached(ack(offer, true)));
            }
        }
        assertEquals(49, offers(sent).size());
        server.cancel();
        server.begin(withCoordinates(begin(2), coordinates), 2_500);
        server.watchWorld(changes, world);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, bytes.clone()));
        }
        sent.clear();
        server.tick(2550L, 128 * 1024, sent::add);
        assertTrue(offers(sent).size() > 0);
        assertTrue(offers(sent).size() <= TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
        for (TravelMessage.TravelReuse offer : offers(sent)) {
            assertTrue(server.cached(ack(offer, true)));
        }
        for (int tick = 52; tick <= 100; tick++) {
            server.tick(tick * 50L, 128 * 1024, sent::add);
            for (TravelMessage.TravelReuse offer : offers(sent)) {
                assertTrue(server.cached(ack(offer, true)));
            }
        }
        assertEquals(49, offers(sent).size());
        assertFalse(sent.stream().anyMatch(TravelMessage.TravelChunk.class::isInstance));
    }

    @Test
    void maximumArrivalHorizonPacesWarmRepliesAndStillCompletesCurrentProofBarrier() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        WorldChangeTracker changes = new WorldChangeTracker();
        UUID world = UUID.randomUUID();
        List<TravelMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(0, 0, 16);
        byte[] payload = new byte[]{1, 2, 3};
        TravelMessage.TravelBegin initial = withCoordinates(begin(1), coordinates);
        server.begin(initial, 0);
        server.watchWorld(changes, world);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : coordinates) {
            assertTrue(server.column(coordinate, 1, payload));
        }
        List<TravelMessage> sent = new ArrayList<>();
        int offered = 0;
        for (int tick = 1; tick <= coordinates.size() && offered < coordinates.size(); tick++) {
            sent.clear();
            server.tick(tick * 50L, 128 * 1024, sent::add);
            List<TravelMessage.TravelReuse> probes = offers(sent);
            assertTrue(probes.size() <= TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
            offered += probes.size();
            for (TravelMessage.TravelReuse probe : probes) {
                assertTrue(server.cached(ack(probe, true)));
            }
        }
        assertEquals(TravelMessage.MAX_TRAVEL_CHUNKS, offered);
        server.cancel();
        TravelMessage.TravelBegin returning = withCoordinates(begin(2), coordinates);
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
        TravelMessage.TravelEnd end = null;
        for (int tick = 1; tick <= coordinates.size() + 1 && end == null; tick++) {
            sent.clear();
            server.tick(20_000L + tick * 50L, 128 * 1024, sent::add);
            List<TravelMessage.TravelReuse> probes = offers(sent);
            assertTrue(probes.size() <= TravelMessage.MAX_TRAVEL_REUSE_PROBES_PER_TICK);
            offered += probes.size();
            for (TravelMessage.TravelReuse probe : probes) {
                assertTrue(server.cached(ack(probe, true)));
            }
            for (TravelMessage message : sent) {
                if (message instanceof TravelMessage.TravelEnd barrier) {
                    end = barrier;
                }
            }
        }
        assertEquals(TravelMessage.MAX_TRAVEL_CHUNKS, offered);
        assertTrue(end != null);
        assertEquals(TravelMessage.MAX_TRAVEL_CHUNKS, end.chunks().size());
        assertTrue(server.ready(new TravelMessage.TravelReady(returning.token(), returning.generation(), end.contentRevision())));
        server.close();
    }

    @Test
    void changedCurrentNativeBytesRejectEarlierRevisionAndDigest() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        TravelMessage.TravelCoordinate coordinate = begin.chunks().getFirst();
        server.column(coordinate, 1, new byte[]{1, 2, 3});
        List<TravelMessage> sent = new ArrayList<>();
        server.tick(50L, 128 * 1024, sent::add);
        TravelMessage.TravelReuse old = offers(sent).getFirst();
        server.invalidate(coordinate);
        assertFalse(server.cached(ack(old, true)));
        server.column(coordinate, 2, new byte[]{1, 2, 4});
        sent.clear();
        server.tick(100L, 128 * 1024, sent::add);
        TravelMessage.TravelReuse current = offers(sent).getFirst();
        assertFalse(Arrays.equals(old.hash(), current.hash()));
        assertFalse(server.cached(ack(old, true)));
        assertFalse(server.cached(new TravelMessage.TravelCached(current.token(), current.generation(), current.chunkX(),
            current.chunkZ(), current.revision(), old.hash(), true)));
        assertTrue(server.cached(ack(current, true)));
        assertTrue(server.cached(ack(current, true)));
        assertFalse(server.cached(ack(current, false)));
    }

    @Test
    void tokenGenerationCoordinatesAndUnnegotiatedPeersCannotClaimReuse() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        TravelMessage.TravelCoordinate coordinate = begin.chunks().getFirst();
        server.column(coordinate, 1, new byte[]{1});
        List<TravelMessage> sent = new ArrayList<>();
        server.tick(50L, 128 * 1024, sent::add);
        TravelMessage.TravelReuse old = offers(sent).getFirst();
        assertFalse(server.cached(new TravelMessage.TravelCached(UUID.randomUUID(), old.generation(), old.chunkX(), old.chunkZ(), 1, old.hash(), true)));
        assertFalse(server.cached(new TravelMessage.TravelCached(old.token(), old.generation() + 1, old.chunkX(), old.chunkZ(), 1, old.hash(), true)));
        assertFalse(server.cached(new TravelMessage.TravelCached(old.token(), old.generation(), 100, 100, 1, old.hash(), true)));
        server.begin(begin(2), 2);
        server.column(coordinate, 1, new byte[]{1});
        assertFalse(server.cached(ack(old, true)));
        sent.clear();
        server.tick(150L, 128 * 1024, sent::add);
        assertFalse(sent.stream().anyMatch(TravelMessage.TravelReuse.class::isInstance));
        assertTrue(sent.stream().anyMatch(TravelMessage.TravelChunk.class::isInstance));
    }

    @Test
    void probesRespectControlByteBudgetAndDuplicatesDoNotEmitAnotherBarrier() {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        TravelMessage.TravelBegin begin = begin(1);
        server.begin(begin, 0);
        server.reuseSelected(true);
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
            server.column(coordinate, 1, new byte[]{1});
        }
        List<TravelMessage> sent = new ArrayList<>();
        for (int tick = 1; tick <= 9; tick++) {
            int before = offers(sent).size();
            server.tick(tick * 50L, TravelMessage.TRAVEL_REUSE_BYTES, sent::add);
            assertEquals(before + 1, offers(sent).size());
            assertTrue(server.cached(ack(offers(sent).getLast(), true)));
        }
        for (TravelMessage.TravelReuse offer : offers(sent)) {
            assertTrue(server.cached(ack(offer, true)));
        }
        server.tick(500L, TravelMessage.TRAVEL_REUSE_BYTES, sent::add);
        assertEquals(1, sent.stream().filter(TravelMessage.TravelEnd.class::isInstance).count());
        for (TravelMessage.TravelReuse offer : offers(sent)) {
            assertTrue(server.cached(ack(offer, true)));
        }
        server.tick(550L, TravelMessage.TRAVEL_REUSE_BYTES, sent::add);
        assertEquals(1, sent.stream().filter(TravelMessage.TravelEnd.class::isInstance).count());
    }

    @Test
    void immutablePayloadStorageSurvivesTransactionClearButPartitionsWorldAndSession() throws ReflectiveOperationException {
        ClientPreparedTravelServer server = new ClientPreparedTravelServer();
        WorldChangeTracker changes = new WorldChangeTracker();
        UUID world = UUID.randomUUID();
        TravelMessage.TravelBegin begin = begin(1);
        TravelMessage.TravelCoordinate coordinate = begin.chunks().getFirst();
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
        WorldChangeTracker changes = new WorldChangeTracker();
        Object first = null;
        UUID world = null;
        for (int index = 0; index < 257; index++) {
            TravelMessage.TravelBegin begin = begin(index + 1);
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

    private static List<TravelMessage.TravelReuse> offers(List<TravelMessage> sent) {
        return sent.stream().filter(TravelMessage.TravelReuse.class::isInstance)
            .map(TravelMessage.TravelReuse.class::cast).toList();
    }

    private static TravelMessage.TravelCached ack(TravelMessage.TravelReuse offer, boolean available) {
        return new TravelMessage.TravelCached(offer.token(), offer.generation(), offer.chunkX(), offer.chunkZ(),
            offer.revision(), offer.hash(), available);
    }

    private static ClientPreparedTravelServer.Commit commit(TravelMessage.TravelBegin begin, long time) {
        return new ClientPreparedTravelServer.Commit(begin.sourcePortal(), begin.sourceWorld(), begin.world().dimension(),
            begin.arrival(), new Vec3d(0, 0, 0), time);
    }

    private static TravelMessage.TravelBegin withCoordinates(TravelMessage.TravelBegin begin,
                                                                 List<TravelMessage.TravelCoordinate> coordinates) {
        return new TravelMessage.TravelBegin(begin.token(), begin.generation(), begin.sourcePortal(), begin.sourceWorld(),
            begin.sourceGeometry(), begin.destinationToSource(), begin.world(), begin.arrival(), coordinates,
            begin.environment(), begin.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
    }

    private static TravelMessage.TravelBegin begin(long generation) {
        TravelMessage.TravelBegin fixture = ClientViewFixtures.travelBegin();
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                coordinates.add(new TravelMessage.TravelCoordinate(x, z));
            }
        }
        return new TravelMessage.TravelBegin(new UUID(0, generation), generation, fixture.sourcePortal(), fixture.sourceWorld(),
            fixture.sourceGeometry(), fixture.destinationToSource(), fixture.world(), new TravelMessage.TravelPose(0, 64, 0, 0, 0),
            coordinates, fixture.environment(), fixture.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
    }
}
