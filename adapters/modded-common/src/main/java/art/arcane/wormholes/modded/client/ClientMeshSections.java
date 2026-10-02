package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.plate.PlateBox;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class ClientMeshSections {
    private final ClientPalette palette;
    private final long budget;
    private final Int2ObjectOpenHashMap<View> views = new Int2ObjectOpenHashMap<>();
    private LongSupplier otherMemory = () -> 0L;
    private long bytes;

    public ClientMeshSections(ClientPalette palette, long budget) {
        this.palette = Objects.requireNonNull(palette);
        if (budget <= 0) {
            throw new IllegalArgumentException("Section memory budget must be positive");
        }
        this.budget = budget;
    }

    public void otherMemory(LongSupplier usage) {
        otherMemory = Objects.requireNonNull(usage);
    }

    public boolean begin(int portalKey, int generation, PlateBox bounds, int maxSections) throws ClientViewProtocolException {
        if (generation <= 0 || maxSections <= 0 || bounds.cells() <= 0) {
            throw new ClientViewProtocolException("Invalid mesh view bounds, generation or resident limit");
        }
        View previous = views.get(portalKey);
        if (previous != null && generation <= previous.generation) {
            if (generation == previous.generation && (!bounds.equals(previous.bounds) || maxSections != previous.maxSections)) {
                throw new ClientViewProtocolException("Mesh generation changed its bounds or resident limit");
            }
            return false;
        }
        remove(portalKey);
        views.put(portalKey, new View(generation, bounds, maxSections));
        return true;
    }

    public Result put(ClientViewMessage.MeshSection message) throws ClientViewProtocolException {
        int portalKey = message.portalKey();
        int generation = message.generation();
        int sectionX = message.sectionX();
        int sectionY = message.sectionY();
        int sectionZ = message.sectionZ();
        int revision = message.revision();
        Brick brick = message.brick();
        View view = views.get(portalKey);
        if (view == null || view.generation != generation) {
            return Result.STALE;
        }
        long key = sectionKey(sectionX, sectionY, sectionZ);
        if (revision <= 0 || brick.brickIndex() != 0 || !view.intersects(sectionX, sectionY, sectionZ)) {
            throw new ClientViewProtocolException("Mesh section outside its view or invalid revision/index");
        }
        Section previous = view.sections.get(key);
        if (previous != null && revision <= previous.revision) {
            return revision == previous.revision ? Result.DUPLICATE : Result.STALE;
        }
        if (previous == null && view.sections.size() >= view.maxSections) {
            return Result.REFUSED;
        }
        Section next = new Section(message, palette);
        long delta = next.bytes - (previous == null ? 0 : previous.bytes);
        if (delta > budget - bytes - otherMemory.getAsLong()) {
            return Result.REFUSED;
        }
        view.sections.put(key, next);
        view.contentRevision++;
        view.changed.add(key);
        view.bytes += delta;
        bytes += delta;
        return Result.APPLIED;
    }

    public boolean drop(int portalKey, int generation, int sectionX, int sectionY, int sectionZ) throws ClientViewProtocolException {
        View view = views.get(portalKey);
        if (view == null || view.generation != generation) {
            return false;
        }
        Section removed = view.sections.remove(sectionKey(sectionX, sectionY, sectionZ));
        if (removed == null) {
            return false;
        }
        view.contentRevision++;
        view.changed.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
        view.bytes -= removed.bytes;
        bytes -= removed.bytes;
        return true;
    }

    public void remove(int portalKey) {
        View removed = views.remove(portalKey);
        if (removed != null) {
            bytes -= removed.bytes;
        }
    }

    public void clear() {
        views.clear();
        bytes = 0;
    }

    public View view(int portalKey) {
        return views.get(portalKey);
    }

    public long bytes() {
        return bytes;
    }

    private static long sectionKey(int x, int y, int z) throws ClientViewProtocolException {
        long key = SectionPos.asLong(x, y, z);
        if (SectionPos.x(key) != x || SectionPos.y(key) != y || SectionPos.z(key) != z) {
            throw new ClientViewProtocolException("Mesh section coordinates exceed the world coordinate range");
        }
        return key;
    }

    public enum Result {
        APPLIED, DUPLICATE, STALE, REFUSED
    }

    public static final class View {
        private final int generation;
        private final PlateBox bounds;
        private final int maxSections;
        private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet changed = new LongOpenHashSet();
        private long bytes;
        private long contentRevision;

        private View(int generation, PlateBox bounds, int maxSections) {
            this.generation = generation;
            this.bounds = bounds;
            this.maxSections = maxSections;
        }

        public LongSet changed() {
            return changed;
        }

        public long contentRevision() {
            return contentRevision;
        }

        public int generation() {
            return generation;
        }

        public PlateBox bounds() {
            return bounds;
        }

        public LongSet sectionKeys() {
            return LongSets.unmodifiable(sections.keySet());
        }

        public Section section(long key) {
            return sections.get(key);
        }

        private boolean intersects(int x, int y, int z) {
            long blockX = (long) x * 16;
            long blockY = (long) y * 16;
            long blockZ = (long) z * 16;
            return blockX + 16 > bounds.minX() && blockX < (long) bounds.minX() + bounds.sizeX()
                && blockY + 16 > bounds.minY() && blockY < (long) bounds.minY() + bounds.sizeY()
                && blockZ + 16 > bounds.minZ() && blockZ < (long) bounds.minZ() + bounds.sizeZ();
        }
    }

    public static final class Section {
        private final int revision;
        private final int bitsPerIndex;
        private final long[] indices;
        private final BlockState[] states;
        private final byte[] blockLight;
        private final byte[] skyLight;
        private final Int2ObjectOpenHashMap<BlockEntitySample> blockEntities;
        private final long bytes;
        private final SectionBiomes biomes;

        private Section(ClientViewMessage.MeshSection message, ClientPalette palette) throws ClientViewProtocolException {
            Brick brick = message.brick();
            this.revision = message.revision();
            this.biomes = message.biomes();
            this.bitsPerIndex = brick.bitsPerIndex();
            this.indices = brick.packedIndices().clone();
            this.blockLight = brick.hasLight() ? compactLight(brick.blockLight()) : null;
            this.skyLight = brick.hasLight() ? compactLight(brick.skyLight()) : null;
            this.blockEntities = new Int2ObjectOpenHashMap<>(brick.blockEntities().length);
            this.states = new BlockState[brick.encoding() == Brick.Encoding.PALETTED ? brick.localPalette().length : 1];
            for (int index = 0; index < states.length; index++) {
                int id = brick.encoding() == Brick.Encoding.PALETTED ? brick.localPalette()[index] : brick.singlePaletteId();
                if (palette.sentinel(id)) {
                    id = message.backingState();
                }
                if (!palette.known(id)) {
                    throw new ClientViewProtocolException("Mesh section references unknown palette state " + id);
                }
                states[index] = palette.state(id);
            }
            if (bitsPerIndex != 0) {
                for (int cell = 0; cell < ClientViewProtocol.BRICK_CELLS; cell++) {
                    if (localIndex(cell) >= states.length) {
                        throw new ClientViewProtocolException("Mesh section references an invalid local palette index");
                    }
                }
            }
            long size = 128L + indices.length * 8L + states.length * 8L;
            if (blockLight != null) {
                size += blockLight.length + skyLight.length;
            }
            size += biomes.indices().length;
            for (String biome : biomes.palette()) {
                size += 48L + biome.length() * 2L;
            }
            for (Brick.BlockEntityCell cell : brick.blockEntities()) {
                if (cell.cellIndex() < 0 || cell.cellIndex() >= ClientViewProtocol.BRICK_CELLS || blockEntities.containsKey(cell.cellIndex())) {
                    throw new ClientViewProtocolException("Invalid or repeated mesh block entity cell");
                }
                try {
                    BlockEntitySample sample = BlockEntitySample.decode(cell.payload());
                    blockEntities.put(cell.cellIndex(), sample);
                    size += sample.bytes() + 48L;
                } catch (IOException failure) {
                    throw new ClientViewProtocolException("Invalid mesh block entity snapshot", failure);
                }
            }
            this.bytes = size;
        }

        public SectionBiomes biomes() {
            return biomes;
        }

        public int revision() {
            return revision;
        }

        public BlockState state(int cell) {
            return states[bitsPerIndex == 0 ? 0 : localIndex(cell)];
        }

        public boolean hasLight() {
            return blockLight != null;
        }

        public int light(boolean sky, int cell) {
            byte[] data = sky ? skyLight : blockLight;
            return data == null ? -1 : (data[data.length == 1 ? 0 : cell >>> 1] >>> ((cell & 1) * 4)) & 15;
        }

        public BlockEntitySample blockEntity(int cell) {
            return blockEntities.get(cell);
        }

        private static byte[] compactLight(byte[] data) {
            byte first = data[0];
            for (int index = 1; index < data.length; index++) {
                if (data[index] != first) {
                    return data.clone();
                }
            }
            return new byte[] {first};
        }

        private int localIndex(int cell) {
            int bit = cell * bitsPerIndex;
            return (int) ((indices[bit >>> 6] >>> (bit & 63)) & ((1 << bitsPerIndex) - 1));
        }
    }
}
