package art.arcane.wormholes.network.client;

import art.arcane.wormholes.network.replication.XxHash64;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;

public final class ClientMeshHash {
    private ClientMeshHash() {
    }

    public static long hash(ClientViewMessage.MeshSection section, long dictionary) throws ClientViewProtocolException {
        ClientViewMessage.MeshSection canonical = new ClientViewMessage.MeshSection(0, 1, section.sectionX(), section.sectionY(),
            section.sectionZ(), 1, section.backingState(), section.brick(), section.biomes());
        byte[] content = ClientViewCodec.encodeBody(canonical);
        return XxHash64.hash(content, 0, content.length, dictionary);
    }

    public static long resolved(ClientViewMessage.MeshSection section, long epoch, IntFunction<String> states) throws ClientViewProtocolException {
        Map<Integer, String> names = new HashMap<>();
        TreeMap<String, Integer> dictionary = new TreeMap<>();
        String backing = SessionPalette.AIR;
        dictionary.put(backing, 0);
        Brick original = section.brick();
        int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
        for (int cell = 0; cell < cells.length; cell++) {
            int id = original.paletteIdAt(cell);
            String name = names.get(id);
            if (name == null) {
                name = state(id, section.backingState(), states);
                names.put(id, name);
                dictionary.put(name, 0);
            }
        }
        int next = 0;
        ClientViewWriter output = new ClientViewWriter(1024);
        output.varint(dictionary.size());
        for (Map.Entry<String, Integer> entry : dictionary.entrySet()) {
            entry.setValue(next++);
            output.string(entry.getKey());
        }
        for (int cell = 0; cell < cells.length; cell++) {
            cells[cell] = dictionary.get(names.get(original.paletteIdAt(cell)));
        }
        Brick.BlockEntityCell[] entities = original.blockEntities().clone();
        Arrays.sort(entities, Comparator.comparingInt(Brick.BlockEntityCell::cellIndex));
        Brick brick = BrickCodec.pack(0, cells).withLight(original.blockLight(), original.skyLight()).withBlockEntities(entities);
        ClientViewMessage.MeshSection canonical = new ClientViewMessage.MeshSection(0, 1, section.sectionX(), section.sectionY(),
            section.sectionZ(), 1, dictionary.get(backing), brick, biomes(section.biomes()));
        output.bytes(ClientViewCodec.encodeBody(canonical));
        return XxHash64.hash(output.rawBuffer(), 0, output.size(), epoch);
    }

    private static String state(int id, int backing, IntFunction<String> states) throws ClientViewProtocolException {
        if (id == ClientViewProtocol.PALETTE_OCCLUDED || id == ClientViewProtocol.PALETTE_BACKING) {
            id = backing;
        }
        String value = id == ClientViewProtocol.PALETTE_AIR ? SessionPalette.AIR : states.apply(id);
        if (value == null) {
            throw new ClientViewProtocolException("Unknown cached section palette state " + id);
        }
        return SessionPalette.canonical(value);
    }

    private static SectionBiomes biomes(SectionBiomes original) {
        if (original.palette().isEmpty()) {
            return original;
        }
        TreeMap<String, Integer> dictionary = new TreeMap<>();
        for (int cell = 0; cell < SectionBiomes.CELLS; cell++) {
            dictionary.put(original.biome(cell), 0);
        }
        int next = 0;
        for (Map.Entry<String, Integer> entry : dictionary.entrySet()) {
            entry.setValue(next++);
        }
        byte[] indices = dictionary.size() == 1 ? new byte[0] : new byte[SectionBiomes.INDEX_BYTES];
        if (indices.length > 0) {
            for (int cell = 0; cell < SectionBiomes.CELLS; cell++) {
                int index = dictionary.get(original.biome(cell));
                indices[cell * 2] = (byte) index;
                indices[cell * 2 + 1] = (byte) (index >>> 8);
            }
        }
        return new SectionBiomes(List.copyOf(dictionary.keySet()), indices);
    }
}
