package art.arcane.wormholes.network.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.replication.XxHash64;

final class BrickCodecTest {
    @Test
    void senderRejectsBrickBodiesThatFitAFrameButExceedTheDecoderLimit() {
        int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
        for (int cell = 0; cell < cells.length; cell++) {
            cells[cell] = cell + 3;
        }
        byte[] light = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        new Random(42L).nextBytes(light);
        Brick.BlockEntityCell[] entities = new Brick.BlockEntityCell[512];
        for (int cell = 0; cell < entities.length; cell++) {
            entities[cell] = new Brick.BlockEntityCell(cell, new byte[64]);
        }
        Brick brick = BrickCodec.pack(0, cells).withLight(light, light).withBlockEntities(entities);
        int bodyBytes = BrickCodec.encodedSize(brick);
        assertTrue(bodyBytes > ClientViewProtocol.MAX_BRICK_BYTES);
        assertTrue(bodyBytes + 32 < ClientViewProtocol.MIN_MAX_FRAME_BYTES);
        ClientViewMessage.MeshSection section = new ClientViewMessage.MeshSection(1, 1, 0, 0, 0, 1, 3, brick, SectionBiomes.NONE);
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeS2C(section, 0, 0));
    }

    @Test
    void uniformBricksCollapseToEmptyOrSingle() {
        int[] air = new int[ClientViewProtocol.BRICK_CELLS];
        Brick empty = BrickCodec.pack(3, air);
        assertEquals(Brick.Encoding.EMPTY, empty.encoding());
        assertEquals(0, empty.bitsPerIndex());
        int[] stone = new int[ClientViewProtocol.BRICK_CELLS];
        Arrays.fill(stone, 9);
        Brick single = BrickCodec.pack(4, stone);
        assertEquals(Brick.Encoding.SINGLE, single.encoding());
        assertEquals(9, single.singlePaletteId());
        assertArrayEquals(stone, BrickCodec.unpack(single));
    }

    @Test
    void everyBitWidthRoundTripsThroughBytes() throws ClientViewProtocolException {
        int[] paletteSizes = {2, 3, 4, 5, 16, 17, 200, 256, 257, 1200};
        for (int size : paletteSizes) {
            int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = 3 + (i * 31 + i / 7) % size;
            }
            Brick brick = BrickCodec.pack(11, cells);
            assertEquals(Brick.bitsFor(size), brick.bitsPerIndex(), "palette " + size);
            assertEquals(size, brick.localPalette().length, "palette " + size);
            assertArrayEquals(cells, BrickCodec.unpack(brick), "palette " + size);
            ClientViewWriter out = new ClientViewWriter();
            BrickCodec.write(out, brick);
            byte[] bytes = out.toByteArray();
            assertEquals(BrickCodec.encodedSize(brick) + 2, bytes.length, "palette " + size);
            Brick decoded = BrickCodec.read(new ClientViewReader(bytes));
            assertEquals(brick, decoded, "palette " + size);
            assertArrayEquals(cells, BrickCodec.unpack(decoded), "palette " + size);
        }
    }

    @Test
    void packedIndicesUseVanillaCellOrderAndLowBitsFirst() {
        int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
        cells[ClientViewProtocol.brickCellIndex(1, 0, 0)] = 7;
        Brick brick = BrickCodec.pack(0, cells);
        assertEquals(1, brick.bitsPerIndex());
        assertEquals(0, brick.localPalette()[0]);
        assertEquals(7, brick.localPalette()[1]);
        assertEquals(2L, brick.packedIndices()[0]);
        assertEquals(1, ClientViewProtocol.brickCellIndex(1, 0, 0));
        assertEquals(256, ClientViewProtocol.brickCellIndex(0, 1, 0));
        assertEquals(16, ClientViewProtocol.brickCellIndex(0, 0, 1));
    }

    @Test
    void bodyHashIgnoresTheBrickIndexAndDependsOnTheSalt() throws ClientViewProtocolException {
        Brick a = ClientViewFixtures.palettedBrick(2);
        Brick b = a.withIndex(140);
        byte[] bodyA = BrickCodec.body(a);
        byte[] bodyB = BrickCodec.body(b);
        assertArrayEquals(bodyA, bodyB);
        assertEquals(XxHash64.hash(bodyA, 0, bodyA.length, 1L), XxHash64.hash(bodyB, 0, bodyB.length, 1L));
        assertFalse(XxHash64.hash(bodyA, 0, bodyA.length, 1L) == XxHash64.hash(bodyA, 0, bodyA.length, 2L));
    }

    @Test
    void lightAndBlockEntitiesRoundTrip() throws ClientViewProtocolException {
        Brick lit = ClientViewFixtures.litBrick(5);
        assertTrue(lit.hasLight());
        assertTrue(lit.hasBlockEntities());
        ClientViewWriter out = new ClientViewWriter();
        BrickCodec.write(out, lit);
        Brick decoded = BrickCodec.read(new ClientViewReader(out.toByteArray()));
        assertEquals(lit, decoded);
        assertEquals(15, BrickLightSource.nibble(decoded.skyLight(), ClientViewProtocol.brickCellIndex(0, 8, 0)));
        assertEquals(0, BrickLightSource.nibble(decoded.skyLight(), ClientViewProtocol.brickCellIndex(0, 7, 0)));
        assertEquals(1, decoded.blockEntities().length);
        assertEquals(ClientViewProtocol.brickCellIndex(3, 8, 3), decoded.blockEntities()[0].cellIndex());
    }

    @Test
    void decoderRejectsIndicesOutsideTheLocalPalette() throws ClientViewProtocolException {
        int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
        cells[0] = 4;
        cells[1] = 5;
        Brick brick = BrickCodec.pack(0, cells);
        assertEquals(2, brick.bitsPerIndex());
        ClientViewWriter out = new ClientViewWriter();
        BrickCodec.write(out, brick);
        byte[] bytes = out.toByteArray();
        int packedStart = 2 + 3 + 1 + 3;
        bytes[packedStart] = (byte) 0xFF;
        assertThrows(ClientViewProtocolException.class, () -> BrickCodec.read(new ClientViewReader(bytes)));
    }

    @Test
    void lightLayersPickTheSmallestOfUniformRunsAndRaw() throws ClientViewProtocolException {
        byte[] uniform = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        Arrays.fill(uniform, (byte) 0xFF);
        byte[] runs = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        for (int cell = 0; cell < ClientViewProtocol.BRICK_CELLS; cell++) {
            BrickLightSource.setNibble(runs, cell, ClientViewProtocol.brickCellY(cell) >= 8 ? 15 : 0);
        }
        byte[] raw = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        new Random(11L).nextBytes(raw);
        assertLightLayer(uniform, BrickCodec.LIGHT_UNIFORM, 2);
        assertLightLayer(runs, BrickCodec.LIGHT_RUNS, 8);
        assertLightLayer(raw, BrickCodec.LIGHT_RAW, 1 + ClientViewProtocol.LIGHT_NIBBLE_BYTES);
    }

    @Test
    void theEncodedSizeMatchesTheBodyForEveryLightMode() throws ClientViewProtocolException {
        byte[] uniform = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] raw = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        new Random(12L).nextBytes(raw);
        Brick paletted = ClientViewFixtures.palettedBrick(1);
        for (Brick brick : new Brick[] {Brick.empty(0).withLight(uniform, uniform), paletted.withLight(uniform, raw),
            paletted.withLight(raw, raw), ClientViewFixtures.litBrick(2), ClientViewFixtures.uniformlyLitBrick(3)}) {
            byte[] body = BrickCodec.body(brick);
            assertEquals(body.length, BrickCodec.encodedSize(brick), brick.toString());
            assertEquals(brick, BrickCodec.readBody(new ClientViewReader(body), brick.brickIndex()));
        }
    }

    @Test
    void malformedLightLayersAreRejected() throws ClientViewProtocolException {
        ClientViewWriter shortRuns = new ClientViewWriter();
        shortRuns.u8(BrickCodec.LIGHT_RUNS);
        shortRuns.varint(2);
        shortRuns.u8(15);
        shortRuns.varint(4000);
        shortRuns.u8(0);
        shortRuns.varint(95);
        assertThrows(ClientViewProtocolException.class, () -> BrickCodec.readLightLayer(new ClientViewReader(shortRuns.toByteArray())));
        ClientViewWriter overflow = new ClientViewWriter();
        overflow.u8(BrickCodec.LIGHT_RUNS);
        overflow.varint(1);
        overflow.u8(3);
        overflow.varint(4097);
        assertThrows(ClientViewProtocolException.class, () -> BrickCodec.readLightLayer(new ClientViewReader(overflow.toByteArray())));
        ClientViewWriter bright = new ClientViewWriter();
        bright.u8(BrickCodec.LIGHT_UNIFORM);
        bright.u8(16);
        assertThrows(ClientViewProtocolException.class, () -> BrickCodec.readLightLayer(new ClientViewReader(bright.toByteArray())));
        ClientViewWriter unknown = new ClientViewWriter();
        unknown.u8(3);
        assertThrows(ClientViewProtocolException.class, () -> BrickCodec.readLightLayer(new ClientViewReader(unknown.toByteArray())));
    }

    private static void assertLightLayer(byte[] nibbles, int mode, int size) throws ClientViewProtocolException {
        ClientViewWriter out = new ClientViewWriter();
        BrickCodec.writeLightLayer(out, nibbles);
        byte[] encoded = out.toByteArray();
        assertEquals(mode, encoded[0]);
        assertEquals(size, encoded.length);
        assertEquals(size, BrickCodec.lightLayerSize(nibbles));
        assertArrayEquals(nibbles, BrickCodec.readLightLayer(new ClientViewReader(encoded)));
    }

    @Test
    void randomBricksSurviveByteRoundTrips() throws ClientViewProtocolException {
        Random random = new Random(7L);
        for (int round = 0; round < 200; round++) {
            int distinct = 1 + random.nextInt(random.nextInt(8) == 0 ? 600 : 20);
            int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = random.nextInt(distinct) == 0 ? 0 : 3 + random.nextInt(distinct);
            }
            Brick brick = BrickCodec.pack(round, cells);
            ClientViewWriter out = new ClientViewWriter();
            BrickCodec.write(out, brick);
            Brick decoded = BrickCodec.read(new ClientViewReader(out.toByteArray()));
            assertArrayEquals(cells, BrickCodec.unpack(decoded), "round " + round);
        }
    }
}
