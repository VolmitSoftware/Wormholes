package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamProtocolException;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientViewAckTest extends MinecraftTestBase {
    private static final int SECOND_KEY = ClientViewHarness.PORTAL_KEY + 1;

    @After
    public void deactivate() {
        ProjectionOverlay overlay = ProjectionOverlay.active();
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
    }

    @Test
    public void everyClosedGroupOfATickCollapsesIntoOneCumulativeAck() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ViewStreamMessage.Portal(ClientViewHarness.PORTAL_KEY, 2, ClientViewHarness.geometry()), ViewStreamLimits.FLAG_LAST);
        harness.receive(new ViewStreamMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 6000L, 0.0F, 0.0F, ViewStreamMessage.Atmosphere.FLAG_TIME),
            ViewStreamLimits.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        List<ViewStreamMessage.Ack> acks = harness.acks();
        assertEquals(1, acks.size());
        assertEquals(harness.lastSeq, acks.get(0).seq());
        assertEquals(harness.tick.clientTick(), acks.get(0).clientTick());
        assertTrue(acks.get(0).appliedCells() > 0);
        assertEquals(1L, harness.stats.acksSent());
    }

    @Test
    public void framesWithoutTheLastFlagAreNeverAcknowledged() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.receive(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(ClientViewHarness.STONE_ID, "minecraft:stone"))), 0);
        harness.receive(new ViewStreamMessage.Portal(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.geometry()), 0);
        streamPlate(harness, ClientViewHarness.PORTAL_KEY, 0);
        for (int tick = 0; tick < 5; tick++) {
            harness.receive(new ViewStreamMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 6000L + tick, 0.0F, 0.0F,
                ViewStreamMessage.Atmosphere.FLAG_TIME), 0);
            harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        }
        assertTrue(harness.tick.overlay().size() > 0);
        assertEquals(0, harness.acks().size());
    }

    @Test
    public void appliedCellsAccumulateUntilTheNextAck() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.receive(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(ClientViewHarness.STONE_ID, "minecraft:stone"))), 0);
        harness.receive(new ViewStreamMessage.Portal(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.geometry()), 0);
        streamPlate(harness, ClientViewHarness.PORTAL_KEY, 0);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        int applied = harness.tick.overlay().size();
        assertTrue(applied > 0);
        assertEquals(0, harness.acks().size());
        harness.receive(new ViewStreamMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 6000L, 0.0F, 0.0F, ViewStreamMessage.Atmosphere.FLAG_TIME),
            ViewStreamLimits.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(1, harness.acks().size());
        assertEquals(applied, harness.acks().get(0).appliedCells());
        harness.receive(new ViewStreamMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 6001L, 0.0F, 0.0F, ViewStreamMessage.Atmosphere.FLAG_TIME),
            ViewStreamLimits.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(2, harness.acks().size());
        assertEquals(0, harness.acks().get(1).appliedCells());
    }

    @Test
    public void everyManifestOfATickIsAnsweredByOneBrickMissMessage() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.receive(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(ClientViewHarness.STONE_ID, "minecraft:stone"))), 0);
        harness.receive(new ViewStreamMessage.Portal(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.geometry()), 0);
        harness.receive(new ViewStreamMessage.Portal(SECOND_KEY, 1, ClientViewHarness.geometry()), 0);
        harness.receive(new ViewStreamMessage.PlateBegin(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.SECTIONS, ClientViewHarness.PLATE,
            ClientViewHarness.STONE_ID, ClientViewHarness.SECTIONS.brickCount(), hashes(0x5000L)), 0);
        harness.receive(new ViewStreamMessage.PlateBegin(SECOND_KEY, 1, ClientViewHarness.SECTIONS, ClientViewHarness.PLATE,
            ClientViewHarness.STONE_ID, ClientViewHarness.SECTIONS.brickCount(), hashes(0x6000L)), 0);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        List<ViewStreamMessage.BrickMiss> misses = misses(harness);
        assertEquals(1, misses.size());
        assertEquals(2, misses.get(0).plates().size());
        assertEquals(ClientViewHarness.PORTAL_KEY, misses.get(0).plates().get(0).portalKey());
        assertEquals(SECOND_KEY, misses.get(0).plates().get(1).portalKey());
        for (ViewStreamMessage.BrickMiss.Plate plate : misses.get(0).plates()) {
            for (int index = 0; index < ClientViewHarness.SECTIONS.brickCount(); index++) {
                assertTrue(plate.missed(index));
            }
        }
        assertEquals(1L, harness.stats.brickMissesSent());
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(1, misses(harness).size());
    }

    @Test
    public void aNewOfferDropsTheAckAndBrickMissOwedToThePreviousServer() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ViewStreamMessage.Offer(ViewStreamLimits.WIRE_VERSION, 1, ViewStreamCapability.ALL,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 0L), ViewStreamLimits.FLAG_LAST);
        harness.receive(new ViewStreamMessage.Accept(2, ViewStreamCapability.ALL, 20, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 9L, 8),
            ViewStreamLimits.FLAG_LAST);
        harness.seq = 0;
        harness.receive(new ViewStreamMessage.Portal(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.geometry()), ViewStreamLimits.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        List<ViewStreamMessage.Ack> acks = harness.acks();
        assertEquals(1, acks.size());
        assertEquals(1, acks.get(0).seq());
        assertEquals(0, misses(harness).size());
    }

    @Test
    public void brickMissBatchesSplitAtTheInboundByteCap() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        int plates = 12;
        for (int key = 1; key <= plates; key++) {
            harness.tick.brickMiss(new ViewStreamMessage.BrickMiss.Plate(key, 1, new long[ViewStreamLimits.MAX_BRICK_MISS_WORDS / 2]));
        }
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        List<ViewStreamMessage.BrickMiss> misses = misses(harness);
        int carried = 0;
        for (ViewStreamMessage.BrickMiss miss : misses) {
            int bytes = ViewStreamLimits.C2S_HEADER_BYTES + 1;
            for (ViewStreamMessage.BrickMiss.Plate plate : miss.plates()) {
                bytes += plate.wireBytes();
            }
            assertTrue(bytes + " bytes in one BRICK_MISS", bytes <= ViewStreamLimits.MAX_C2S_BYTES);
            carried += miss.plates().size();
        }
        assertEquals(plates, carried);
        assertTrue(misses.size() > 1);
    }

    private static void streamPlate(ClientViewHarness harness, int key, int endFlags) throws ViewStreamProtocolException {
        Brick[] bricks = new Brick[ClientViewHarness.SECTIONS.brickCount()];
        for (int index = 0; index < bricks.length; index++) {
            bricks[index] = ClientViewHarness.plateBrick(index);
        }
        harness.receive(new ViewStreamMessage.PlateBegin(key, 1, ClientViewHarness.SECTIONS, ClientViewHarness.PLATE, ClientViewHarness.STONE_ID,
            bricks.length, hashes(0x5000L)), 0);
        harness.receive(new ViewStreamMessage.PlateBricks(key, 1, Arrays.asList(bricks)), 0);
        harness.receive(new ViewStreamMessage.PlateEnd(key, 1), endFlags);
    }

    private static long[] hashes(long base) {
        long[] hashes = new long[ClientViewHarness.SECTIONS.brickCount()];
        for (int index = 0; index < hashes.length; index++) {
            hashes[index] = base + index;
        }
        return hashes;
    }

    private static List<ViewStreamMessage.BrickMiss> misses(ClientViewHarness harness) {
        List<ViewStreamMessage.BrickMiss> misses = new ArrayList<>();
        for (ViewStreamMessage message : harness.sent) {
            if (message instanceof ViewStreamMessage.BrickMiss miss) {
                misses.add(miss);
            }
        }
        return misses;
    }
}
