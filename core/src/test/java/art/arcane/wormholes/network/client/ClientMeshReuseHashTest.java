package art.arcane.wormholes.network.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ClientMeshReuseHashTest {
    @Test
    void resolvedHashIgnoresUnusedBackingButIncludesResolvedSentinelCells() throws ClientViewProtocolException {
        IntFunction<String> names = Map.of(7, "minecraft:stone", 8, "minecraft:dirt", 9, "minecraft:stone")::get;
        ClientViewMessage.MeshSection previous = section(Brick.single(0, 7), SectionBiomes.NONE);
        ClientViewMessage.MeshSection changedBacking = new ClientViewMessage.MeshSection(1, 1, 2, -3, 4, 1, 8,
            previous.brick(), previous.biomes());
        assertEquals(ClientMeshHash.resolved(previous, 71, names), ClientMeshHash.resolved(changedBacking, 71, names));
        for (int sentinel : List.of(ClientViewProtocol.PALETTE_BACKING, ClientViewProtocol.PALETTE_OCCLUDED)) {
            ClientViewMessage.MeshSection stone = section(Brick.single(0, sentinel), SectionBiomes.NONE);
            ClientViewMessage.MeshSection dirt = new ClientViewMessage.MeshSection(1, 1, 2, -3, 4, 1, 8,
                stone.brick(), stone.biomes());
            assertNotEquals(ClientMeshHash.resolved(stone, 71, names), ClientMeshHash.resolved(dirt, 71, names));
        }
    }

    @Test
    void resolvedContentSurvivesPaletteIdsPackingOrderAndTransportChanges() throws ClientViewProtocolException {
        int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
        cells[0] = 7;
        cells[1] = 8;
        Brick first = BrickCodec.pack(0, cells);
        int[] nextCells = cells.clone();
        nextCells[0] = 81;
        nextCells[1] = 32;
        IntFunction<String> firstNames = Map.of(7, "oak_log[axis=y]", 8, "minecraft:stone", 9, "minecraft:stone")::get;
        IntFunction<String> nextNames = Map.of(81, "minecraft:oak_log[axis=y]", 32, "stone", 95, "stone")::get;
        ClientViewMessage.MeshSection previous = section(first, SectionBiomes.NONE);
        ClientViewMessage.MeshSection next = new ClientViewMessage.MeshSection(99, 5, 2, -3, 4, 19, 95,
            BrickCodec.pack(0, nextCells), SectionBiomes.NONE);
        assertEquals(ClientMeshHash.resolved(previous, 71, firstNames), ClientMeshHash.resolved(next, 71, nextNames));
        assertArrayEquals(cells, unpack(first));
        assertArrayEquals(nextCells, unpack(next.brick()));
    }

    @Test
    void resolvedContentIncludesRealStatesAndConnectionEpoch() throws ClientViewProtocolException {
        ClientViewMessage.MeshSection section = section(Brick.single(0, 7), SectionBiomes.NONE);
        IntFunction<String> names = Map.of(7, "minecraft:grass_block[snowy=false]", 9, "minecraft:stone")::get;
        IntFunction<String> changed = Map.of(7, "minecraft:grass_block[snowy=true]", 9, "minecraft:stone")::get;
        long hash = ClientMeshHash.resolved(section, 71, names);
        assertNotEquals(hash, ClientMeshHash.resolved(section, 71, changed));
        assertNotEquals(hash, ClientMeshHash.resolved(section, 72, names));
        assertThrows(ClientViewProtocolException.class, () -> ClientMeshHash.resolved(section, 71, id -> null));
    }

    @Test
    void resolvedBlockEntityAndBiomeOrderDoesNotMutateCachedSource() throws ClientViewProtocolException {
        Brick.BlockEntityCell firstEntity = new Brick.BlockEntityCell(12, new byte[] {1, 2, 3});
        Brick.BlockEntityCell secondEntity = new Brick.BlockEntityCell(4, new byte[] {4, 5, 6});
        Brick.BlockEntityCell[] entities = {firstEntity, secondEntity};
        Brick first = Brick.single(0, 7).withBlockEntities(entities);
        Brick next = Brick.single(0, 81).withBlockEntities(new Brick.BlockEntityCell[] {secondEntity, firstEntity});
        byte[] firstIndices = new byte[SectionBiomes.INDEX_BYTES];
        byte[] nextIndices = new byte[SectionBiomes.INDEX_BYTES];
        for (int cell = 0; cell < SectionBiomes.CELLS; cell++) {
            firstIndices[cell * 2] = (byte) (cell % 2);
            nextIndices[cell * 2] = (byte) (1 - cell % 2);
        }
        SectionBiomes firstBiomes = new SectionBiomes(List.of("minecraft:plains", "minecraft:forest"), firstIndices);
        SectionBiomes nextBiomes = new SectionBiomes(List.of("minecraft:forest", "minecraft:plains"), nextIndices);
        IntFunction<String> names = Map.of(7, "minecraft:stone", 81, "minecraft:stone", 9, "minecraft:stone")::get;
        assertEquals(ClientMeshHash.resolved(section(first, firstBiomes), 71, names),
            ClientMeshHash.resolved(section(next, nextBiomes), 71, names));
        assertArrayEquals(new Brick.BlockEntityCell[] {firstEntity, secondEntity}, first.blockEntities());
        assertArrayEquals(firstIndices, firstBiomes.indices());
    }

    @Test
    void transportIdentityDoesNotInvalidateIdenticalSectionContent() throws ClientViewProtocolException {
        ClientViewMessage.MeshSection first = section(Brick.single(0, 7), SectionBiomes.NONE);
        ClientViewMessage.MeshSection next = new ClientViewMessage.MeshSection(99, 52, first.sectionX(), first.sectionY(),
            first.sectionZ(), 108, first.backingState(), first.brick(), first.biomes());
        assertEquals(ClientMeshHash.hash(first, 31), ClientMeshHash.hash(next, 31));
    }

    @Test
    void coordinatesBackingAndDictionaryIdentityCannotReuseAnotherSection() throws ClientViewProtocolException {
        ClientViewMessage.MeshSection first = section(Brick.single(0, 7), SectionBiomes.NONE);
        long hash = ClientMeshHash.hash(first, 31);
        assertNotEquals(hash, ClientMeshHash.hash(first, 32));
        assertNotEquals(hash, ClientMeshHash.hash(new ClientViewMessage.MeshSection(1, 1, 2, -3, 4, 1, 8,
            first.brick(), first.biomes()), 31));
        assertNotEquals(hash, ClientMeshHash.hash(new ClientViewMessage.MeshSection(1, 1, 3, -3, 4, 1, 9,
            first.brick(), first.biomes()), 31));
        assertNotEquals(hash, ClientMeshHash.hash(new ClientViewMessage.MeshSection(1, 1, 2, -2, 4, 1, 9,
            first.brick(), first.biomes()), 31));
        assertNotEquals(hash, ClientMeshHash.hash(new ClientViewMessage.MeshSection(1, 1, 2, -3, 5, 1, 9,
            first.brick(), first.biomes()), 31));
    }

    @Test
    void blockLightSkyLightAndBlockEntityUpdatesInvalidateClaims() throws ClientViewProtocolException {
        byte[] block = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        Brick first = Brick.single(0, 7).withLight(block, sky)
            .withBlockEntities(new Brick.BlockEntityCell[] {new Brick.BlockEntityCell(12, new byte[] {1, 2, 3})});
        long hash = ClientMeshHash.hash(section(first, SectionBiomes.NONE), 31);
        assertNotEquals(hash, ClientMeshHash.hash(section(Brick.single(0, 8).withLight(block, sky)
            .withBlockEntities(first.blockEntities()), SectionBiomes.NONE), 31));
        byte[] changedBlock = block.clone();
        changedBlock[17] = 3;
        assertNotEquals(hash, ClientMeshHash.hash(section(first.withLight(changedBlock, sky), SectionBiomes.NONE), 31));
        byte[] changedSky = sky.clone();
        changedSky[19] = 4;
        assertNotEquals(hash, ClientMeshHash.hash(section(first.withLight(block, changedSky), SectionBiomes.NONE), 31));
        assertNotEquals(hash, ClientMeshHash.hash(section(first.withBlockEntities(new Brick.BlockEntityCell[] {
            new Brick.BlockEntityCell(12, new byte[] {1, 2, 4})}), SectionBiomes.NONE), 31));
        assertNotEquals(hash, ClientMeshHash.hash(section(first.withBlockEntities(new Brick.BlockEntityCell[] {
            new Brick.BlockEntityCell(13, new byte[] {1, 2, 3})}), SectionBiomes.NONE), 31));
        assertNotEquals(hash, ClientMeshHash.hash(section(first.withBlockEntities(null), SectionBiomes.NONE), 31));
    }

    @Test
    void packedBlocksAndBiomePaletteOrCellChangesInvalidateClaims() throws ClientViewProtocolException {
        int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
        cells[12] = 7;
        Brick first = BrickCodec.pack(0, cells);
        byte[] biomeCells = new byte[SectionBiomes.INDEX_BYTES];
        SectionBiomes biomes = new SectionBiomes(List.of("minecraft:plains", "minecraft:desert"), biomeCells);
        long hash = ClientMeshHash.hash(section(first, biomes), 31);
        cells[13] = 7;
        assertNotEquals(hash, ClientMeshHash.hash(section(BrickCodec.pack(0, cells), biomes), 31));
        byte[] changedBiomes = biomeCells.clone();
        changedBiomes[22] = 1;
        assertNotEquals(hash, ClientMeshHash.hash(section(first,
            new SectionBiomes(biomes.palette(), changedBiomes)), 31));
        assertNotEquals(hash, ClientMeshHash.hash(section(first,
            new SectionBiomes(List.of("minecraft:plains", "minecraft:forest"), biomeCells)), 31));
        assertNotEquals(hash, ClientMeshHash.hash(section(first, SectionBiomes.NONE), 31));
    }

    private static ClientViewMessage.MeshSection section(Brick brick, SectionBiomes biomes) {
        return new ClientViewMessage.MeshSection(1, 1, 2, -3, 4, 1, 9, brick, biomes);
    }

    private static int[] unpack(Brick brick) {
        int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
        for (int cell = 0; cell < cells.length; cell++) {
            cells[cell] = brick.paletteIdAt(cell);
        }
        return cells;
    }
}
