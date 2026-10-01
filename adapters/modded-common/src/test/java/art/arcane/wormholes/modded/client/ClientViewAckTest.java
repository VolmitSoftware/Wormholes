package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientViewAckTest {
    private static final int SECOND_KEY = ClientViewHarness.PORTAL_KEY + 1;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @After
    public void deactivate() {
        ProjectionOverlay overlay = ProjectionOverlay.active();
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
    }

    @Test
    public void everyClosedGroupOfATickCollapsesIntoOneCumulativeAck() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ClientViewMessage.Portal(ClientViewHarness.PORTAL_KEY, 2, ClientViewHarness.geometry()), ClientViewProtocol.FLAG_LAST);
        harness.receive(new ClientViewMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 6000L, 0.0F, 0.0F, ClientViewMessage.Atmosphere.FLAG_TIME),
            ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        List<ClientViewMessage.Ack> acks = harness.acks();
        assertEquals(1, acks.size());
        assertEquals(harness.lastSeq, acks.get(0).seq());
        assertEquals(harness.tick.clientTick(), acks.get(0).clientTick());
        assertTrue(acks.get(0).appliedCells() > 0);
        assertEquals(1L, harness.stats.acksSent());
    }

    @Test
    public void framesWithoutTheLastFlagAreNeverAcknowledged() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.receive(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(ClientViewHarness.STONE_ID, "minecraft:stone"))), 0);
        harness.receive(new ClientViewMessage.Portal(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.geometry()), 0);
        streamPlate(harness, ClientViewHarness.PORTAL_KEY, 0);
        for (int tick = 0; tick < 5; tick++) {
            harness.receive(new ClientViewMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 6000L + tick, 0.0F, 0.0F,
                ClientViewMessage.Atmosphere.FLAG_TIME), 0);
            harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        }
        assertTrue(harness.tick.overlay().size() > 0);
        assertEquals(0, harness.acks().size());
    }

    @Test
    public void appliedCellsAccumulateUntilTheNextAck() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.receive(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(ClientViewHarness.STONE_ID, "minecraft:stone"))), 0);
        harness.receive(new ClientViewMessage.Portal(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.geometry()), 0);
        streamPlate(harness, ClientViewHarness.PORTAL_KEY, 0);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        int applied = harness.tick.overlay().size();
        assertTrue(applied > 0);
        assertEquals(0, harness.acks().size());
        harness.receive(new ClientViewMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 6000L, 0.0F, 0.0F, ClientViewMessage.Atmosphere.FLAG_TIME),
            ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(1, harness.acks().size());
        assertEquals(applied, harness.acks().get(0).appliedCells());
        harness.receive(new ClientViewMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 6001L, 0.0F, 0.0F, ClientViewMessage.Atmosphere.FLAG_TIME),
            ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(2, harness.acks().size());
        assertEquals(0, harness.acks().get(1).appliedCells());
    }

    @Test
    public void everyManifestOfATickIsAnsweredByOneBrickMissMessage() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.receive(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(ClientViewHarness.STONE_ID, "minecraft:stone"))), 0);
        harness.receive(new ClientViewMessage.Portal(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.geometry()), 0);
        harness.receive(new ClientViewMessage.Portal(SECOND_KEY, 1, ClientViewHarness.geometry()), 0);
        harness.receive(new ClientViewMessage.PlateBegin(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.SECTIONS, ClientViewHarness.PLATE,
            ClientViewHarness.STONE_ID, ClientViewHarness.SECTIONS.brickCount(), hashes(0x5000L)), 0);
        harness.receive(new ClientViewMessage.PlateBegin(SECOND_KEY, 1, ClientViewHarness.SECTIONS, ClientViewHarness.PLATE,
            ClientViewHarness.STONE_ID, ClientViewHarness.SECTIONS.brickCount(), hashes(0x6000L)), 0);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        List<ClientViewMessage.BrickMiss> misses = misses(harness);
        assertEquals(1, misses.size());
        assertEquals(2, misses.get(0).plates().size());
        assertEquals(ClientViewHarness.PORTAL_KEY, misses.get(0).plates().get(0).portalKey());
        assertEquals(SECOND_KEY, misses.get(0).plates().get(1).portalKey());
        for (ClientViewMessage.BrickMiss.Plate plate : misses.get(0).plates()) {
            for (int index = 0; index < ClientViewHarness.SECTIONS.brickCount(); index++) {
                assertTrue(plate.missed(index));
            }
        }
        assertEquals(1L, harness.stats.brickMissesSent());
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        assertEquals(1, misses(harness).size());
    }

    @Test
    public void aNewOfferDropsTheAckAndBrickMissOwedToThePreviousServer() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.receive(new ClientViewMessage.Offer(ClientViewProtocol.WIRE_VERSION, 1, ClientViewCapability.ALL,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 0L), ClientViewProtocol.FLAG_LAST);
        harness.receive(new ClientViewMessage.Accept(2, ClientViewCapability.ALL, 20, ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 9L, 8),
            ClientViewProtocol.FLAG_LAST);
        harness.seq = 0;
        harness.receive(new ClientViewMessage.Portal(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.geometry()), ClientViewProtocol.FLAG_LAST);
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        List<ClientViewMessage.Ack> acks = harness.acks();
        assertEquals(1, acks.size());
        assertEquals(1, acks.get(0).seq());
        assertEquals(0, misses(harness).size());
    }

    @Test
    public void brickMissBatchesSplitAtTheInboundByteCap() throws ClientViewProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        int plates = 12;
        for (int key = 1; key <= plates; key++) {
            harness.tick.brickMiss(new ClientViewMessage.BrickMiss.Plate(key, 1, new long[ClientViewProtocol.MAX_BRICK_MISS_WORDS / 2]));
        }
        harness.tick(ClientViewHarness.EYE_X, ClientViewHarness.EYE_Y, ClientViewHarness.EYE_Z);
        List<ClientViewMessage.BrickMiss> misses = misses(harness);
        int carried = 0;
        for (ClientViewMessage.BrickMiss miss : misses) {
            int bytes = ClientViewProtocol.C2S_HEADER_BYTES + 1;
            for (ClientViewMessage.BrickMiss.Plate plate : miss.plates()) {
                bytes += plate.wireBytes();
            }
            assertTrue(bytes + " bytes in one BRICK_MISS", bytes <= ClientViewProtocol.MAX_C2S_BYTES);
            carried += miss.plates().size();
        }
        assertEquals(plates, carried);
        assertTrue(misses.size() > 1);
    }

    private static void streamPlate(ClientViewHarness harness, int key, int endFlags) throws ClientViewProtocolException {
        Brick[] bricks = new Brick[ClientViewHarness.SECTIONS.brickCount()];
        for (int index = 0; index < bricks.length; index++) {
            bricks[index] = ClientViewHarness.plateBrick(index);
        }
        harness.receive(new ClientViewMessage.PlateBegin(key, 1, ClientViewHarness.SECTIONS, ClientViewHarness.PLATE, ClientViewHarness.STONE_ID,
            bricks.length, hashes(0x5000L)), 0);
        harness.receive(new ClientViewMessage.PlateBricks(key, 1, Arrays.asList(bricks)), 0);
        harness.receive(new ClientViewMessage.PlateEnd(key, 1), endFlags);
    }

    private static long[] hashes(long base) {
        long[] hashes = new long[ClientViewHarness.SECTIONS.brickCount()];
        for (int index = 0; index < hashes.length; index++) {
            hashes[index] = base + index;
        }
        return hashes;
    }

    private static List<ClientViewMessage.BrickMiss> misses(ClientViewHarness harness) {
        List<ClientViewMessage.BrickMiss> misses = new ArrayList<>();
        for (ClientViewMessage message : harness.sent) {
            if (message instanceof ClientViewMessage.BrickMiss miss) {
                misses.add(miss);
            }
        }
        return misses;
    }
}
