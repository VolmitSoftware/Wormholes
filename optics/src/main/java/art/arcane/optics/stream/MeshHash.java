package art.arcane.optics.stream;

import art.arcane.optics.internal.stream.XxHash64;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;

public final class MeshHash {
    private MeshHash() {
    }

    public static long hash(ViewStreamMessage.MeshSection section, long dictionary) throws ViewStreamProtocolException {
        ViewStreamMessage.MeshSection canonical = new ViewStreamMessage.MeshSection(0, 1, section.sectionX(), section.sectionY(),
            section.sectionZ(), 1, section.backingState(), section.brick(), section.biomes());
        byte[] content = ViewStreamCodec.projectionBody(canonical);
        return XxHash64.hash(content, 0, content.length, dictionary);
    }

    public static long resolved(ViewStreamMessage.MeshSection section, long epoch, IntFunction<String> states) throws ViewStreamProtocolException {
        TreeMap<String, Integer> dictionary = new TreeMap<>();
        String backing = SessionPalette.AIR;
        dictionary.put(backing, 0);
        Brick original = section.brick();
        ResolvedPalette palette = original.encoding() == Brick.Encoding.PALETTED
            ? palette(original, section.backingState(), states, dictionary) : null;
        String single = switch (original.encoding()) {
            case EMPTY -> backing;
            case SINGLE -> state(original.singlePaletteId(), section.backingState(), states);
            case PALETTED -> null;
        };
        if (single != null) {
            dictionary.put(single, 0);
        }
        int next = 0;
        ViewStreamWriter output = new ViewStreamWriter(1024);
        output.varint(dictionary.size());
        for (Map.Entry<String, Integer> entry : dictionary.entrySet()) {
            entry.setValue(next++);
            output.string(entry.getKey());
        }
        Brick.BlockEntityCell[] entities = original.blockEntities().clone();
        Arrays.sort(entities, Comparator.comparingInt(Brick.BlockEntityCell::cellIndex));
        Brick brick = (palette == null ? Brick.single(0, dictionary.get(single)) : pack(original, palette, dictionary))
            .withLight(original.blockLight(), original.skyLight()).withBlockEntities(entities);
        ViewStreamMessage.MeshSection canonical = new ViewStreamMessage.MeshSection(0, 1, section.sectionX(), section.sectionY(),
            section.sectionZ(), 1, dictionary.get(backing), brick, biomes(section.biomes()));
        output.bytes(ViewStreamCodec.projectionBody(canonical));
        return XxHash64.hash(output.rawBuffer(), 0, output.size(), epoch);
    }

    private static ResolvedPalette palette(Brick original, int backing, IntFunction<String> states,
                                           TreeMap<String, Integer> dictionary) throws ViewStreamProtocolException {
        int[] ids = original.localPalette();
        String[] names = new String[ids.length];
        Map<Integer, String> resolved = new HashMap<>();
        int used = 0;
        boolean ordered = true;
        boolean air = false;
        for (int cell = 0; cell < ViewStreamLimits.BRICK_CELLS; cell++) {
            int index = original.localIndexAt(cell);
            if (names[index] != null) {
                continue;
            }
            ordered &= index == used;
            used++;
            int id = ids[index];
            String name = resolved.get(id);
            if (name == null) {
                name = state(id, backing, states);
                resolved.put(id, name);
                dictionary.put(name, 0);
            }
            names[index] = name;
            air |= SessionPalette.AIR.equals(name);
        }
        return new ResolvedPalette(names, ordered && used == names.length
            && original.bitsPerIndex() == Brick.bitsFor(used) && dictionary.size() == used + (air ? 0 : 1));
    }

    private static Brick pack(Brick original, ResolvedPalette palette, TreeMap<String, Integer> dictionary) {
        String[] names = palette.names();
        int[] remap = new int[names.length];
        int single = -1;
        boolean uniform = true;
        for (int index = 0; index < names.length; index++) {
            if (names[index] != null) {
                remap[index] = dictionary.get(names[index]);
                if (single < 0) {
                    single = remap[index];
                } else if (single != remap[index]) {
                    uniform = false;
                }
            }
        }
        if (uniform) {
            return Brick.single(0, single);
        }
        if (palette.reuseIndices()) {
            return new Brick(0, Brick.Encoding.PALETTED, original.bitsPerIndex(), 0, ViewStreamLimits.PALETTE_AIR,
                remap, original.packedIndices(), null, null, null);
        }
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        for (int cell = 0; cell < cells.length; cell++) {
            cells[cell] = remap[original.localIndexAt(cell)];
        }
        return BrickCodec.pack(0, cells);
    }

    private static String state(int id, int backing, IntFunction<String> states) throws ViewStreamProtocolException {
        if (id == ViewStreamLimits.PALETTE_OCCLUDED || id == ViewStreamLimits.PALETTE_BACKING) {
            id = backing;
        }
        String value = id == ViewStreamLimits.PALETTE_AIR ? SessionPalette.AIR : states.apply(id);
        if (value == null) {
            throw new ViewStreamProtocolException("Unknown cached section palette state " + id);
        }
        return SessionPalette.canonical(value);
    }

    private static SectionBiomes biomes(SectionBiomes original) {
        if (original.palette().size() <= 1) {
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

    private record ResolvedPalette(String[] names, boolean reuseIndices) {
    }
}
