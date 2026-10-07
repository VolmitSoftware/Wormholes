package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateKey;

final class ViewStreamSessionStreamTest {
    private static final String[] DIRT = {"minecraft:stone", "minecraft:glass", "minecraft:gold_block", "minecraft:air",
        "minecraft:oak_leaves[persistent=true]", "minecraft:emerald_ore", "minecraft:obsidian"};

    private static EncodedPlate encoded(SessionHarness harness, ViewPlate<String> plate) {
        return harness.registry.encoderFor(BrickLightSource.NONE).encode(plate, BrickLightSource.NONE, true);
    }

    private static void assertHolds(SessionHarness harness, ViewPlate<String> plate) {
        EncodedPlate expected = encoded(harness, plate);
        for (Brick[] held : harness.client.plates.values()) {
            if (held.length == expected.brickCount() && List.of(held).equals(expected.bricks())) {
                return;
            }
        }
        throw new AssertionError("client does not hold the plate " + plate.key().portalId());
    }

    @Test
    void aSessionWithoutPlatesNeverOwnsPortalsOrStreamsThem() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(1L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        a.plate = a.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS & ~ViewStreamCapability.PLATES.mask());
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());

        harness.tick();
        harness.tick();

        assertFalse(harness.session.owns(a.id), "the vanilla projector keeps a portal the client never asked plates for");
        assertEquals(0, harness.sent(ViewStreamMessageType.PORTAL));
        assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertFalse(harness.events.contains("release " + a.id), "the vanilla projection must not be released");
        assertTrue(harness.access.plateRequested.isEmpty(), "no plate is built for a session that cannot take one");
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void streamSendsPaletteBeforeUsePortalBeforePlateAndEndClosesTheGroup() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(1L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        SessionPortal b = harness.access.add(new SessionPortal("b", 8));
        a.plate = a.build(world);
        b.plate = b.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());

        harness.tick();

        assertEquals(2, harness.client.portals.size());
        assertEquals(2, harness.client.plates.size());
        assertHolds(harness, a.plate);
        assertHolds(harness, b.plate);
        assertTrue(harness.session.owns(a.id));
        assertTrue(harness.session.owns(b.id));
        List<ViewStreamMessage> stream = harness.client.since(2);
        assertInstanceOf(ViewStreamMessage.Palette.class, stream.get(0));
        assertEquals(2, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertEquals(2, harness.sent(ViewStreamMessageType.PLATE_END));
        assertTrue(harness.client.open.isEmpty());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());

        int before = harness.client.received.size();
        harness.tick();
        harness.tick();
        assertEquals(before, harness.client.received.size(), "an unchanged view must not send anything");

        a.plate = a.build(world);
        harness.tick();
        assertEquals(before, harness.client.received.size(), "a rebuilt plate with identical content must not send anything");
    }

    @Test
    void withoutTheBrickCacheEveryBrickFollowsTheBeginInOneGroup() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 8));
        SessionWorld world = new SessionWorld(2L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        a.plate = a.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        assertFalse(ViewStreamCapability.BRICK_CACHE.in(harness.session.caps()));

        harness.tick();

        ViewStreamMessage.PlateBegin begin = (ViewStreamMessage.PlateBegin) harness.last(ViewStreamMessageType.PLATE_BEGIN);
        assertNotNull(begin);
        assertFalse(begin.hasHashes());
        assertEquals(begin.brickCount(), bricksIn(harness.client.received));
        assertEquals(0, harness.sent(ViewStreamMessageType.BRICK_MISS));
        assertHolds(harness, a.plate);
    }

    @Test
    void oversizedHashManifestsStreamEveryBrickWithoutGivingUpThePortal() throws ViewStreamProtocolException {
        int frameBytes = ViewStreamLimits.MIN_MAX_FRAME_BYTES;
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8, frameBytes));
        SessionWorld world = new SessionWorld(2L);
        SessionPortal portal = harness.access.add(new SessionPortal("large-manifest", 0));
        BlockBox box = new BlockBox(0, 0, 0, 1, 1024, 2048);
        portal.plate = PlateTestFixtures.empty(new ViewPlateKey(portal.id, world, false, 0, 0L), box);
        harness.handshake(SessionHarness.CLIENT_CAPS);

        harness.session.tick(++harness.serverTick);
        for (byte[] frame : harness.frames) {
            assertTrue(frame.length <= frameBytes, "every frame respects the negotiated limit");
        }
        harness.pump();

        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
        ViewStreamMessage.PlateBegin begin = assertInstanceOf(ViewStreamMessage.PlateBegin.class,
            harness.last(ViewStreamMessageType.PLATE_BEGIN));
        assertFalse(begin.hasHashes());
        assertEquals(box, begin.cells());
        assertEquals(8192, begin.brickCount());
        assertEquals(8192, bricksIn(harness.client.received));
        assertEquals(1, harness.sent(ViewStreamMessageType.PLATE_END));
        assertTrue(ViewStreamCapability.BRICK_CACHE.in(harness.session.caps()));
        assertHolds(harness, portal.plate);
        harness.tick();
        assertTrue(harness.session.owns(portal.id));
        assertEquals(0, harness.sent(ViewStreamMessageType.PORTAL_DROP));

        SessionPortal small = harness.access.add(new SessionPortal("small-manifest", 8));
        small.plate = small.build(world);
        harness.tick();
        ViewStreamMessage.PlateBegin cached = assertInstanceOf(ViewStreamMessage.PlateBegin.class,
            harness.last(ViewStreamMessageType.PLATE_BEGIN));
        assertTrue(cached.hasHashes());
        assertHolds(harness, small.plate);
    }

    @Test
    void patchesReproduceTheFullReencodeForRandomDirt() throws ViewStreamProtocolException {
        for (long seed = 1L; seed <= 6L; seed++) {
            SessionHarness harness = new SessionHarness(SessionHarness.options(seed % 2L == 0L, 8));
            SessionWorld world = new SessionWorld(seed);
            SessionPortal portal = harness.access.add(new SessionPortal("patched", 0));
            portal.plate = portal.build(world);
            harness.handshake(SessionHarness.CLIENT_CAPS);
            harness.tick();
            assertHolds(harness, portal.plate);
            Random random = new Random(seed * 31L);
            int patches = 0;
            for (int round = 0; round < 4; round++) {
                int edits = 1 + random.nextInt(round == 3 ? 400 : 40);
                for (int i = 0; i < edits; i++) {
                    world.set(186 + random.nextInt(30), 56 + random.nextInt(18), 176 + random.nextInt(26), DIRT[random.nextInt(DIRT.length)]);
                }
                ViewPlate<String> next = portal.build(world);
                assertEquals(portal.plate.box(), next.box());
                int patchesBefore = harness.sent(ViewStreamMessageType.PLATE_PATCH);
                int beginsBefore = harness.sent(ViewStreamMessageType.PLATE_BEGIN);
                portal.plate = next;
                harness.tick();
                assertEquals(beginsBefore, harness.sent(ViewStreamMessageType.PLATE_BEGIN), "dirt must patch, not restream");
                patches += harness.sent(ViewStreamMessageType.PLATE_PATCH) - patchesBefore;
                assertHolds(harness, next);
            }
            assertTrue(patches > 0, "seed " + seed + " produced no patch");
            assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
        }
    }

    @Test
    void theFirstPlateRequestOfAnAttendedPortalIsUrgentAndLaterRefreshesAreNot() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(9L);
        SessionPortal portal = harness.access.add(new SessionPortal("urgent", 0));
        harness.handshake(SessionHarness.CLIENT_CAPS);

        harness.tick();
        assertEquals(List.of(portal.id), harness.access.urgentRequests, "a plate that does not exist yet is requested urgently");
        harness.tick();
        assertEquals(List.of(portal.id, portal.id), harness.access.urgentRequests, "it stays urgent until a plate arrives");

        portal.plate = portal.build(world);
        harness.tick();
        assertHolds(harness, portal.plate);
        harness.tick();
        portal.plate = portal.build(world);
        harness.tick();
        assertEquals(List.of(portal.id, portal.id, portal.id), harness.access.urgentRequests, "refreshes after the first plate are budgeted");
    }

    @Test
    void aPatchSplitAcrossFramesReachesTheClientAsOneRevision() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8, ViewStreamLimits.MIN_MAX_FRAME_BYTES));
        NoisyLight light = new NoisyLight();
        harness.access.light = light;
        SessionWorld world = new SessionWorld(4L);
        SessionPortal portal = harness.access.add(new SessionPortal("wide", 0));
        portal.depthBlocks = 64.0D;
        portal.lateralBlocks = 24.0D;
        portal.plate = portal.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertHoldsCells(harness, portal.plate);
        int key = keyOf(harness, portal.plate);
        int revision = harness.client.plateRevisions.get(key);
        int patchesBefore = harness.sent(ViewStreamMessageType.PLATE_PATCH);

        light.seed++;
        portal.plate = portal.build(world);
        harness.tick();

        int pieces = harness.sent(ViewStreamMessageType.PLATE_PATCH) - patchesBefore;
        assertTrue(pieces > 1, "expected the relit plate to patch across several frames, got " + pieces);
        assertEquals(revision + 1, harness.client.plateRevisions.get(key).intValue(), "the pieces form one revision step");
        assertHoldsCells(harness, portal.plate);
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void aChangedPlateBoxRestreamsInsteadOfPatching() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(3L);
        SessionPortal portal = harness.access.add(new SessionPortal("moved", 0));
        portal.plate = portal.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        SessionPortal shifted = new SessionPortal("moved", 5);
        ViewPlate<String> moved = shifted.build(world);
        assertNotEquals(portal.plate.box(), moved.box());
        portal.plate = moved;
        harness.tick();
        assertEquals(2, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_PATCH));
        assertHolds(harness, moved);
    }

    @Test
    void sessionResetClearsTheClientAndRestreamsFromTheBrickCache() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(4L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        SessionPortal b = harness.access.add(new SessionPortal("b", 8));
        a.plate = a.build(world);
        b.plate = b.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        Set<Integer> firstKeys = new HashSet<Integer>(harness.client.portals.keySet());
        int mark = harness.client.received.size();

        harness.session.reset(ViewStreamMessage.ResetReason.TELEPORT);
        harness.pump();

        List<ViewStreamMessage> after = harness.client.since(mark);
        ViewStreamMessage.SessionReset reset = assertInstanceOf(ViewStreamMessage.SessionReset.class, after.get(0));
        assertEquals(ViewStreamMessage.ResetReason.TELEPORT, reset.reason());
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.session.owns(a.id));
        assertEquals(2, harness.client.portals.size());
        for (Integer key : harness.client.portals.keySet()) {
            assertFalse(firstKeys.contains(key), "reset must re-key portals");
        }
        assertHolds(harness, a.plate);
        assertHolds(harness, b.plate);
        assertEquals(0, bricksIn(after), "every brick is already in the client content cache");
    }

    @Test
    void dimensionAndRespawnResetsForgetTheDepartedPortalsInsteadOfRestreamingThem() throws ViewStreamProtocolException {
        for (ViewStreamMessage.ResetReason reason : List.of(ViewStreamMessage.ResetReason.DIMENSION, ViewStreamMessage.ResetReason.RESPAWN)) {
            SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
            SessionWorld world = new SessionWorld(9L);
            SessionPortal departed = harness.access.add(new SessionPortal("departed", 0));
            departed.plate = departed.build(world);
            harness.handshake(SessionHarness.CLIENT_CAPS);
            harness.tick();
            assertTrue(harness.session.owns(departed.id));
            int mark = harness.client.received.size();
            harness.access.interest.clear();
            SessionPortal arrival = harness.access.add(new SessionPortal("arrival", 16));
            arrival.plate = arrival.build(world);

            harness.session.reset(reason);
            harness.pump();

            List<ViewStreamMessage> after = harness.client.since(mark);
            assertEquals(1, after.size(), reason + " sent " + after);
            assertEquals(reason, assertInstanceOf(ViewStreamMessage.SessionReset.class, after.get(0)).reason());
            assertFalse(harness.session.owns(departed.id), reason.name());
            assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());

            harness.tick();

            assertTrue(harness.session.owns(arrival.id), reason.name());
            assertFalse(harness.session.owns(departed.id), reason.name());
            assertEquals(1, harness.client.portals.size(), reason.name());
            assertHolds(harness, arrival.plate);
            assertEquals(0, harness.sent(ViewStreamMessageType.PORTAL_DROP), reason.name());
            assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
        }
    }

    @Test
    void interestGraceKeepsAPortalThenDropsIt() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(5L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        a.plate = a.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        harness.access.interest.clear();
        for (int i = 0; i < ViewStreamOptions.DEFAULT_INTEREST_GRACE_TICKS; i++) {
            harness.tick();
            assertTrue(harness.session.owns(a.id), "grace tick " + i);
        }
        assertEquals(0, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        harness.tick();
        assertFalse(harness.session.owns(a.id));
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        assertTrue(harness.client.portals.isEmpty());
        assertTrue(harness.client.plates.isEmpty());
    }

    @Test
    void refusedPlatesAndUnrepresentableGeometryStayVanilla() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(6L);
        SessionPortal refused = harness.access.add(new SessionPortal("refused", 0));
        SessionPortal irregular = harness.access.add(new SessionPortal("irregular", 8));
        SessionPortal normal = harness.access.add(new SessionPortal("normal", 16));
        refused.refused = true;
        irregular.geometryAvailable = false;
        irregular.plate = irregular.build(world);
        normal.plate = normal.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertFalse(harness.session.owns(refused.id));
        assertFalse(harness.session.owns(irregular.id));
        assertTrue(harness.session.owns(normal.id));
        assertEquals(1, harness.client.portals.size());
        assertEquals(List.of("release " + normal.id), releases(harness));

        refused.refused = false;
        refused.plate = refused.build(world);
        harness.tick();
        assertTrue(harness.session.owns(refused.id));
        assertEquals(2, harness.client.portals.size());
        harness.tick();
        assertFalse(harness.session.owns(irregular.id), "a rejected geometry stays vanilla while the portal stays interesting");

        normal.plate = null;
        normal.refused = true;
        harness.tick();
        assertFalse(harness.session.owns(normal.id), "a plate refused later hands the portal back to the vanilla path");
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL_DROP));
    }

    @Test
    void aPlateTheClientRefusesHandsThePortalBackToTheVanillaPath() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(12L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        SessionPortal b = harness.access.add(new SessionPortal("b", 8));
        a.plate = a.build(world);
        b.plate = b.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertTrue(harness.session.owns(a.id));
        int key = keyAt(harness, 10);
        int revision = harness.client.plateRevisions.get(key);

        byte[] refusal = ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.PlateRefused(key, revision));
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(refusal));
        harness.tick();

        assertFalse(harness.session.owns(a.id), "a refused plate hands the portal back to the vanilla path");
        assertTrue(harness.session.owns(b.id));
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        assertFalse(harness.client.portals.containsKey(key));
        harness.tick();
        harness.tick();
        assertFalse(harness.session.owns(a.id), "the portal stays vanilla while the observer keeps watching it");
        assertEquals(List.of("release " + a.id, "release " + b.id), releases(harness));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void aRefusalOutsideTheViewStreamIsIgnored() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        byte[] refusal = ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.PlateRefused(1, 1));
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(refusal));
    }

    @Test
    void geometryChangesResendThePortalBeforeItsNewPlate() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 8));
        SessionWorld world = new SessionWorld(7L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        a.plate = a.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        ViewStreamMessage.Portal first = (ViewStreamMessage.Portal) harness.last(ViewStreamMessageType.PORTAL);
        a.geometryRevision++;
        a.frontSide = true;
        goldCube(world, 199, 67, 188);
        a.plate = a.build(world);
        int mark = harness.client.received.size();
        harness.tick();
        List<ViewStreamMessage> after = harness.client.since(mark);
        ViewStreamMessage.Portal second = assertInstanceOf(ViewStreamMessage.Portal.class, after.get(after.get(0) instanceof ViewStreamMessage.Palette ? 1 : 0));
        assertEquals(first.portalKey(), second.portalKey());
        assertEquals(first.geometryRevision() + 1, second.geometryRevision());
        assertInstanceOf(ViewStreamMessage.PlatePatch.class, after.get(after.size() - 1));
    }

    @Test
    void standbyPrestreamIsOffByDefault() throws ViewStreamProtocolException {
        assertFalse(SessionHarness.defaults().standbyPrestream());
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(8L);
        SessionPortal rtp = harness.access.add(new SessionPortal("rtp", 0));
        rtp.plate = rtp.build(world, 1L);
        rtp.standby = rtp.build(new SessionWorld(80L), 2L);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        for (int i = 0; i < 5; i++) {
            harness.tick();
        }
        assertEquals(0, harness.access.standbyCalls);
        assertEquals(1, harness.client.plates.size());
        assertEquals(1, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
    }

    @Test
    void standbyPrestreamWarmsTheBrickCacheWithoutAPortal() throws ViewStreamProtocolException {
        ViewStreamOptions base = SessionHarness.options(true, 8);
        ViewStreamOptions prestream = new ViewStreamOptions(true, true, base.helloGraceMillis(), base.maxFrameBytes(), base.ackWindowFrames(),
            true, base.destinationLight(), base.entityFrames(), base.zeroCopy(), true, base.viewStats(), base.clientMirror(), base.clientRecursion(),
            base.interestGraceTicks(), base.withheldCaps());
        SessionHarness harness = new SessionHarness(prestream);
        harness.client.allowUnannouncedPlates = true;
        SessionPortal rtp = harness.access.add(new SessionPortal("rtp", 0));
        rtp.plate = rtp.build(new SessionWorld(9L), 1L);
        SessionWorld standbyWorld = new SessionWorld(90L);
        goldCube(standbyWorld, 199, 67, 188);
        goldCube(standbyWorld, 195, 66, 184);
        ViewPlate<String> next = rtp.build(standbyWorld, 2L);
        rtp.standby = next;
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertEquals(1, harness.client.portals.size());
        assertEquals(2, harness.client.plates.size());
        assertEquals(1, harness.client.standbyKeys.size());
        assertHolds(harness, next);

        int mark = harness.client.received.size();
        rtp.plate = next;
        rtp.standby = null;
        harness.tick();
        assertEquals(0, bricksIn(harness.client.since(mark)), "the promoted standby plate is served from the client brick cache");
        assertEquals(3, harness.sent(ViewStreamMessageType.PLATE_BEGIN), "the reroll restreams with hashes instead of patching");

        harness.registry.configure(SessionHarness.options(true, 8));
        harness.tick();
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        assertEquals(1, harness.client.plates.size());
    }

    @Test
    void zeroCopyHandsPlatesOverByReference() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8), Runnable::run, 0x5EEDL);
        List<ViewPlate<String>> published = new ArrayList<ViewPlate<String>>();
        harness.handoffs = (key, revision, plate, light) -> {
            published.add(plate);
            return 1000L + published.size();
        };
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        a.plate = a.build(new SessionWorld(10L));
        harness.handshake(SessionHarness.CLIENT_CAPS | ViewStreamCapability.ZERO_COPY.mask(), 0x5EEDL);
        assertTrue(ViewStreamCapability.ZERO_COPY.in(harness.session.caps()));
        long encodes = harness.registry.encodes();
        harness.tick();
        assertEquals(List.of(a.plate), published);
        ViewStreamMessage.PlateHandle handle = (ViewStreamMessage.PlateHandle) harness.last(ViewStreamMessageType.PLATE_HANDLE);
        assertEquals(1001L, handle.handle());
        assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertEquals(encodes, harness.registry.encodes(), "zero copy must not encode the plate");
    }

    @Test
    void entityFramesStartFullAndRequestAFullFrameAfterADrop() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 1));
        List<String> calls = new ArrayList<String>();
        harness.entities = (observer, portal, key, tick, full, hideObserver) -> {
            calls.add(key + (full ? " full" : " delta"));
            return new ViewStreamMessage.EntityFrame(key, (int) tick, List.of(), List.of(), true);
        };
        SessionWorld world = new SessionWorld(11L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        SessionPortal b = harness.access.add(new SessionPortal("b", 8));
        a.plate = a.build(world);
        b.plate = b.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertEquals(List.of("2 full", "1 full"), calls);
        assertEquals(1, harness.client.portals.size(), "the one-frame window holds the second portal back");
        assertEquals(1, harness.sent(ViewStreamMessageType.ENTITY_FRAME));
        calls.clear();
        harness.tick();
        assertEquals(List.of("2 full", "1 delta"), calls, "the dropped frame for the unannounced portal is requested in full again");
        harness.ack();
        assertEquals(2, harness.client.portals.size());
    }

    @Test
    void sceneEffectsDroppedBeforeThePortalIsAnnouncedAreRequestedInFullAgain() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 1));
        List<String> calls = new ArrayList<String>();
        harness.scene = new ViewStreamScene<String>() {
            @Override
            public ViewStreamMessage.Extension effects(String observer, UUID portal, int portalKey, long tick, boolean full) {
                calls.add(portalKey + (full ? " fx full" : " fx"));
                return full ? TestEffects.scene(portalKey, List.of()) : null;
            }

            @Override
            public ViewStreamMessage.Atmosphere atmosphere(String observer, UUID portal, int portalKey, long tick, boolean full) {
                return full ? new ViewStreamMessage.Atmosphere(portalKey, tick, 0.0F, 0.0F, ViewStreamMessage.Atmosphere.FLAG_TIME) : null;
            }
        };
        SessionWorld world = new SessionWorld(12L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        SessionPortal b = harness.access.add(new SessionPortal("b", 8));
        a.plate = a.build(world);
        b.plate = b.build(world);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertEquals(List.of("2 fx full", "1 fx full"), calls);
        assertEquals(1, harness.sent(TestEffects.ID));
        assertEquals(1, harness.sent(ViewStreamMessageType.ATMOSPHERE));
        calls.clear();
        harness.tick();
        assertEquals(List.of("2 fx full", "1 fx"), calls, "the portal whose effects were dropped asks for them again");
        harness.ack();
        assertEquals(2, harness.client.portals.size());
    }

    private static int keyAt(SessionHarness harness, int originX) {
        for (Map.Entry<Integer, ApertureDescriptor> entry : harness.client.portals.entrySet()) {
            if (entry.getValue().originX() == originX) {
                return entry.getKey();
            }
        }
        throw new AssertionError("no client portal at x " + originX);
    }

    private static List<String> releases(SessionHarness harness) {
        List<String> releases = new ArrayList<String>();
        for (String event : harness.events) {
            if (event.startsWith("release ")) {
                releases.add(event);
            }
        }
        return releases;
    }

    static int bricksIn(List<ViewStreamMessage> messages) {
        int count = 0;
        for (ViewStreamMessage message : messages) {
            if (message instanceof ViewStreamMessage.PlateBricks part) {
                count += part.bricks().size();
            }
        }
        return count;
    }

    private static int keyOf(SessionHarness harness, ViewPlate<String> plate) {
        EncodedPlate expected = encoded(harness, plate);
        for (Map.Entry<Integer, Brick[]> entry : harness.client.plates.entrySet()) {
            if (entry.getValue().length == expected.brickCount()) {
                return entry.getKey();
            }
        }
        throw new AssertionError("client does not hold a plate of " + expected.brickCount() + " bricks");
    }

    private static void assertHoldsCells(SessionHarness harness, ViewPlate<String> plate) {
        EncodedPlate expected = encoded(harness, plate);
        for (Brick[] held : harness.client.plates.values()) {
            if (held.length != expected.brickCount()) {
                continue;
            }
            for (int i = 0; i < held.length; i++) {
                assertEquals(expected.brick(i).stripped(), held[i].stripped(), "brick " + i);
            }
            return;
        }
        throw new AssertionError("client does not hold the plate " + plate.key().portalId());
    }

    private static final class NoisyLight implements BrickLightSource {
        long seed = 1L;

        @Override
        public boolean fill(int sectionX, int sectionY, int sectionZ, byte[] blockNibbles, byte[] skyNibbles) {
            Random random = new Random(seed * 1_000_003L + sectionX * 31L + sectionY * 131L + sectionZ * 1_031L);
            random.nextBytes(blockNibbles);
            random.nextBytes(skyNibbles);
            return true;
        }
    }

    static void goldCube(SessionWorld world, int x, int y, int z) {
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    world.set(x + dx, y + dy, z + dz, "minecraft:gold_block");
                }
            }
        }
    }
}
