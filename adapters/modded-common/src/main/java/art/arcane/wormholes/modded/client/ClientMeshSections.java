package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.Brick;
import art.arcane.wormholes.network.client.ClientMeshHash;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.frame.OpticTransform;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.SectionPos;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public final class ClientMeshSections {
    private final ClientPalette palette;
    private final long budget;
    private final Int2ObjectOpenHashMap<View> views = new Int2ObjectOpenHashMap<>();
    private LongSupplier otherMemory = () -> 0L;
    private long bytes;
    private int revision;
    private final ObjectLinkedOpenHashSet<HistoryEntry> history = new ObjectLinkedOpenHashSet<>(128);
    private final Map<Identity, History> historyByIdentity = new HashMap<>();
    private long historyBytes;
    private long epoch;
    private boolean epochKnown;

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

    public boolean begin(int portalKey, int generation, BlockBox bounds, int maxSections) throws ViewStreamProtocolException {
        if (generation <= 0 || maxSections <= 0 || bounds.cells() <= 0) {
            throw new ViewStreamProtocolException("Invalid mesh view bounds, generation or resident limit");
        }
        View previous = views.get(portalKey);
        if (previous != null && generation <= previous.generation) {
            if (generation == previous.generation && (!bounds.equals(previous.bounds) || maxSections != previous.maxSections)) {
                throw new ViewStreamProtocolException("Mesh generation changed its bounds or resident limit");
            }
            return false;
        }
        remove(portalKey);
        views.put(portalKey, new View(generation, bounds, maxSections));
        return true;
    }

    public boolean retainLocal(int portalKey, int generation, BlockBox bounds, int maxSections) throws ViewStreamProtocolException {
        View view = views.get(portalKey);
        if (view == null || generation <= view.generation || view.localSections.isEmpty() && view.sections.isEmpty()) {
            return begin(portalKey, generation, bounds, maxSections);
        }
        if (generation <= 0 || maxSections <= 0 || bounds.cells() <= 0) {
            throw new ViewStreamProtocolException("Invalid retained mesh view");
        }
        view.wireRevisions.clear();
        view.claimed.clear();
        view.needsClaims = true;
        view.generation = generation;
        view.bounds = bounds;
        view.maxSections = maxSections;
        return true;
    }

    public Result put(ClientViewMessage.MeshSection message) throws ViewStreamProtocolException {
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
            throw new ViewStreamProtocolException("Mesh section outside its view or invalid revision/index");
        }
        Section previous = view.sections.get(key);
        int currentRevision = view.wireRevisions.get(key);
        if (currentRevision != 0 && revision <= currentRevision) {
            return revision == currentRevision ? Result.DUPLICATE : Result.STALE;
        }
        Section next = new Section(message, palette, ++this.revision, epoch);
        if (previous != null && previous.hash == next.hash && epochKnown) {
            view.wireRevisions.put(key, revision);
            remember(view, key, previous);
            return Result.DUPLICATE;
        }
        long delta = next.bytes - (previous == null ? 0 : previous.bytes);
        if (delta > budget - bytes - historyBytes - otherMemory.getAsLong()) {
            return Result.REFUSED;
        }
        if (!admitAuthoritative(view, key)) {
            return Result.REFUSED;
        }
        view.sections.put(key, next);
        view.wireRevisions.put(key, revision);
        view.keys.add(key);
        if (!view.localSections.containsKey(key)) {
            view.contentRevision++;
            view.changed.add(key);
        }
        view.bytes += delta;
        bytes += delta;
        remember(view, key, next);
        return Result.APPLIED;
    }

    public boolean drop(int portalKey, int generation, int sectionX, int sectionY, int sectionZ) throws ViewStreamProtocolException {
        View view = views.get(portalKey);
        if (view == null || view.generation != generation) {
            return false;
        }
        long key = sectionKey(sectionX, sectionY, sectionZ);
        view.wireRevisions.remove(key);
        view.claimed.remove(key);
        Section removed = view.sections.remove(key);
        if (removed == null) {
            return false;
        }
        if (!view.localSections.containsKey(key)) {
            view.keys.remove(key);
            view.contentRevision++;
            view.changed.add(key);
        }
        view.bytes -= removed.bytes;
        bytes -= removed.bytes;
        return true;
    }

    public Section localSection(ClientViewMessage.MeshSection message) throws ViewStreamProtocolException {
        return new Section(message, palette, ++revision, epoch);
    }

    public boolean local(int portalKey, long key, Section section) {
        View view = views.get(portalKey);
        if (view == null || section != null && !view.intersects(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))) {
            return false;
        }
        Section previous = view.localSections.get(key);
        if (previous == section) {
            return true;
        }
        Section visible = view.section(key);
        if (section != null && epochKnown && visible != null && visible.hash == section.hash) {
            section = visible;
        }
        if (previous == section) {
            return true;
        }
        if (section == null) {
            if (view.identity == null) {
                view.localSections.remove(key);
                if (!view.sections.containsKey(key)) {
                    view.keys.remove(key);
                }
                view.changed.add(key);
                view.contentRevision++;
                view.bytes -= previous.bytes;
                bytes -= previous.bytes;
                return true;
            }
            Section replaced = view.sections.put(key, previous);
            view.localSections.remove(key);
            view.wireRevisions.remove(key);
            view.claimed.remove(key);
            long delta = -(replaced == null ? 0 : replaced.bytes);
            view.bytes += delta;
            bytes += delta;
            remember(view, key, previous);
            return true;
        }
        long delta = section.bytes - (previous == null ? 0 : previous.bytes);
        trimPreviews(view, delta, key);
        if (delta > budget - bytes - historyBytes - otherMemory.getAsLong()
            || !view.keys.contains(key) && view.keys.size() >= view.maxSections) {
            return false;
        }
        view.localSections.put(key, section);
        view.keys.add(key);
        if (visible != section) {
            view.changed.add(key);
            view.contentRevision++;
        }
        view.bytes += delta;
        bytes += delta;
        remember(view, key, section);
        return true;
    }

    private boolean admitAuthoritative(View view, long key) {
        if (view.keys.contains(key) || view.keys.size() < view.maxSections) {
            return true;
        }
        int required = view.keys.size() - view.maxSections + 1;
        long[] candidates = new long[required];
        int count = 0;
        for (int pass = 0; pass < 2 && count < required; pass++) {
            for (long existing : view.keys) {
                if (view.wireRevisions.get(existing) != 0
                    || view.localSections.containsKey(existing) != (pass == 1)) {
                    continue;
                }
                candidates[count++] = existing;
                if (count == required) {
                    break;
                }
            }
        }
        if (count != required) {
            return false;
        }
        for (long candidate : candidates) {
            Section wire = view.sections.remove(candidate);
            Section local = view.localSections.remove(candidate);
            long removedBytes = (wire == null ? 0L : wire.bytes) + (local == null ? 0L : local.bytes);
            view.claimed.remove(candidate);
            view.keys.remove(candidate);
            view.changed.add(candidate);
            view.contentRevision++;
            view.bytes -= removedBytes;
            bytes -= removedBytes;
        }
        return true;
    }

    private void trimPreviews(View view, long required, long protectedKey) {
        boolean adding = !view.keys.contains(protectedKey);
        while (required > budget - bytes - historyBytes - otherMemory.getAsLong()
            || adding && view.keys.size() >= view.maxSections) {
            Long candidate = null;
            for (long key : view.sections.keySet()) {
                if (key == protectedKey || view.wireRevisions.get(key) != 0 || view.localSections.containsKey(key)) {
                    continue;
                }
                candidate = key;
                if (!view.intersects(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))) {
                    break;
                }
            }
            if (candidate == null) {
                return;
            }
            Section removed = view.sections.remove(candidate.longValue());
            view.claimed.remove(candidate.longValue());
            view.keys.remove(candidate.longValue());
            view.changed.add(candidate.longValue());
            view.contentRevision++;
            view.bytes -= removed.bytes;
            bytes -= removed.bytes;
        }
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
        return bytes + historyBytes;
    }

    public void epoch(long value) {
        if (epochKnown && epoch != value) {
            history.clear();
            historyByIdentity.clear();
            historyBytes = 0;
            clear();
        }
        epoch = value;
        epochKnown = true;
    }

    List<ClientViewMessage.MeshClaim> bind(int portalKey, Identity identity) {
        epoch(identity.epoch);
        View view = views.get(portalKey);
        if (view == null) {
            return List.of();
        }
        boolean sameIdentity = identity.equals(view.identity);
        if (sameIdentity && !view.needsClaims) {
            return List.of();
        }
        if (view.identity != null && !sameIdentity) {
            for (long key : view.sections.keySet()) {
                Section removed = view.sections.get(key);
                view.bytes -= removed.bytes;
                bytes -= removed.bytes;
                if (!view.localSections.containsKey(key)) {
                    view.keys.remove(key);
                }
                view.changed.add(key);
            }
            for (long key : view.localSections.keySet()) {
                Section removed = view.localSections.get(key);
                view.bytes -= removed.bytes;
                bytes -= removed.bytes;
                view.keys.remove(key);
                view.changed.add(key);
            }
            view.localSections.clear();
            view.sections.clear();
            view.wireRevisions.clear();
            view.claimed.clear();
            view.contentRevision++;
        }
        History context = historyByIdentity.get(sameIdentity ? view.identity : identity);
        view.identity = context == null ? identity : context.identity;
        view.needsClaims = false;
        List<ClientViewMessage.MeshClaim> claims = new ArrayList<>();
        if (context != null) {
            for (HistoryEntry entry : context.sections.values()) {
                long key = entry.key;
                if (!view.intersects(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))
                    || view.sections.containsKey(key) || !view.keys.contains(key) && view.keys.size() >= view.maxSections) {
                    continue;
                }
                Section section = entry.section;
                if (section.bytes > budget - bytes - historyBytes - otherMemory.getAsLong()) {
                    break;
                }
                view.sections.put(key, section);
                view.keys.add(key);
                if (!view.localSections.containsKey(key)) {
                    view.changed.add(key);
                    view.contentRevision++;
                }
                view.bytes += section.bytes;
                bytes += section.bytes;
                view.claimed.put(key, Long.valueOf(section.hash));
                claims.add(new ClientViewMessage.MeshClaim(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), section.hash));
            }
        }
        for (long key : view.sections.keySet()) {
            Section section = view.sections.get(key);
            if (view.wireRevisions.get(key) == 0 && !view.claimed.containsKey(key)
                && view.intersects(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))) {
                view.claimed.put(key, Long.valueOf(section.hash));
                claims.add(new ClientViewMessage.MeshClaim(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), section.hash));
            }
            remember(view, key, section);
        }
        return claims;
    }

    public boolean canPreview(int portalKey, long key) {
        return canPreview(views.get(portalKey), key);
    }

    public ClientViewMessage.MeshClaim preview(int portalKey, long key, Section section) {
        View view = views.get(portalKey);
        if (!canPreview(view, key)
            || section.bytes > budget - bytes - historyBytes - otherMemory.getAsLong()) {
            return null;
        }
        view.sections.put(key, section);
        view.keys.add(key);
        view.changed.add(key);
        view.contentRevision++;
        view.bytes += section.bytes;
        bytes += section.bytes;
        view.claimed.put(key, Long.valueOf(section.hash));
        remember(view, key, section);
        return new ClientViewMessage.MeshClaim(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), section.hash);
    }

    public Result reuse(ClientViewMessage.MeshReuse message) throws ViewStreamProtocolException {
        View view = views.get(message.portalKey());
        if (view == null || view.generation != message.generation()) {
            return Result.STALE;
        }
        long key = sectionKey(message.sectionX(), message.sectionY(), message.sectionZ());
        Section section = view.sections.get(key);
        if (section == null || !view.claimed.containsKey(key) || view.claimed.get(key) != message.hash() || section.hash != message.hash()) {
            return Result.STALE;
        }
        int current = view.wireRevisions.get(key);
        if (message.revision() <= current) {
            return message.revision() == current ? Result.DUPLICATE : Result.STALE;
        }
        view.wireRevisions.put(key, message.revision());
        return Result.DUPLICATE;
    }

    private static boolean canPreview(View view, long key) {
        return view != null && view.identity != null && !view.sections.containsKey(key)
            && view.intersects(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))
            && (view.keys.contains(key) || view.keys.size() < view.maxSections);
    }

    private void remember(View view, long key, Section section) {
        if (view.identity == null || !epochKnown) {
            return;
        }
        History context = historyByIdentity.get(view.identity);
        if (context == null) {
            context = new History(view.identity);
            historyByIdentity.put(context.identity, context);
        }
        view.identity = context.identity;
        HistoryEntry entry = context.sections.get(key);
        if (entry == null) {
            entry = new HistoryEntry(context, key, section);
            historyBytes += section.bytes;
        } else {
            historyBytes += section.bytes - entry.section.bytes;
            entry.section = section;
        }
        context.sections.putAndMoveToLast(key, entry);
        history.addAndMoveToLast(entry);
        long limit = Math.min(Math.min(128 * 1024 * 1024L, budget / 3), Math.max(0, budget - bytes - otherMemory.getAsLong()));
        while (historyBytes > limit && !history.isEmpty()) {
            HistoryEntry removed = history.removeFirst();
            historyBytes -= removed.section.bytes;
            History owner = removed.context;
            owner.sections.remove(removed.key);
            if (owner.sections.isEmpty()) {
                historyByIdentity.remove(owner.identity);
            }
        }
    }

    static final class Identity {
        private final String world;
        private final OpticTransform transform;
        private final long epoch;
        private final long targetIdentity;
        private final int hash;

        Identity(ProjectionEnvironment environment, long epoch, long targetIdentity) {
            world = environment.world().dimensionKey();
            transform = environment.transform();
            this.epoch = epoch;
            this.targetIdentity = targetIdentity;
            int value = world.hashCode();
            value = 31 * value + transform.hashCode();
            value = 31 * value + Long.hashCode(epoch);
            hash = 31 * value + Long.hashCode(targetIdentity);
        }

        boolean matchesEnvironment(ProjectionEnvironment environment) {
            return environment != null && world.equals(environment.world().dimensionKey()) && transform.equals(environment.transform());
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object value) {
            return this == value || value instanceof Identity other && hash == other.hash && epoch == other.epoch
                && targetIdentity == other.targetIdentity && world.equals(other.world) && transform.equals(other.transform);
        }
    }

    private static final class History {
        private final Identity identity;
        private final Long2ObjectLinkedOpenHashMap<HistoryEntry> sections = new Long2ObjectLinkedOpenHashMap<>();

        private History(Identity identity) {
            this.identity = identity;
        }
    }

    private static final class HistoryEntry {
        private final History context;
        private final long key;
        private Section section;

        private HistoryEntry(History context, long key, Section section) {
            this.context = context;
            this.key = key;
            this.section = section;
        }
    }

    private static long sectionKey(int x, int y, int z) throws ViewStreamProtocolException {
        long key = SectionPos.asLong(x, y, z);
        if (SectionPos.x(key) != x || SectionPos.y(key) != y || SectionPos.z(key) != z) {
            throw new ViewStreamProtocolException("Mesh section coordinates exceed the world coordinate range");
        }
        return key;
    }

    public enum Result {
        APPLIED, DUPLICATE, STALE, REFUSED
    }

    public static final class View {
        private int generation;
        private BlockBox bounds;
        private int maxSections;
        private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<Section> localSections = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet keys = new LongOpenHashSet();
        private final LongOpenHashSet changed = new LongOpenHashSet();
        private long bytes;
        private long contentRevision;
        private Identity identity;
        private boolean needsClaims;
        private final Long2IntOpenHashMap wireRevisions = new Long2IntOpenHashMap();
        private final Long2ObjectOpenHashMap<Long> claimed = new Long2ObjectOpenHashMap<>();

        private View(int generation, BlockBox bounds, int maxSections) {
            this.generation = generation;
            this.bounds = bounds;
            this.maxSections = maxSections;
        }

        public LongSet changed() {
            return changed;
        }

        Identity identity() {
            return identity;
        }

        public long contentRevision() {
            return contentRevision;
        }

        public int generation() {
            return generation;
        }

        public BlockBox bounds() {
            return bounds;
        }

        public LongSet sectionKeys() {
            return LongSets.unmodifiable(keys);
        }

        public Section section(long key) {
            Section local = localSections.get(key);
            return local == null ? sections.get(key) : local;
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
        private final long hash;
        private final int bitsPerIndex;
        private final long[] indices;
        private final BlockState[] states;
        private final byte[] blockLight;
        private final byte[] skyLight;
        private final Int2ObjectOpenHashMap<BlockEntitySample> blockEntities;
        private final long bytes;
        private final SectionBiomes biomes;

        private Section(ClientViewMessage.MeshSection message, ClientPalette palette, int revision, long epoch) throws ViewStreamProtocolException {
            Brick brick = message.brick();
            this.revision = revision;
            this.hash = ClientMeshHash.resolved(message, epoch, id -> BlockStateParser.serialize(palette.state(id)));
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
                    throw new ViewStreamProtocolException("Mesh section references unknown palette state " + id);
                }
                states[index] = palette.state(id);
            }
            if (bitsPerIndex != 0) {
                for (int cell = 0; cell < ViewStreamLimits.BRICK_CELLS; cell++) {
                    if (localIndex(cell) >= states.length) {
                        throw new ViewStreamProtocolException("Mesh section references an invalid local palette index");
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
                if (cell.cellIndex() < 0 || cell.cellIndex() >= ViewStreamLimits.BRICK_CELLS || blockEntities.containsKey(cell.cellIndex())) {
                    throw new ViewStreamProtocolException("Invalid or repeated mesh block entity cell");
                }
                try {
                    BlockEntitySample sample = BlockEntitySample.decode(cell.payload());
                    blockEntities.put(cell.cellIndex(), sample);
                    size += sample.bytes() + 48L;
                } catch (IOException failure) {
                    throw new ViewStreamProtocolException("Invalid mesh block entity snapshot", failure);
                }
            }
            this.bytes = size;
        }

        public long bytes() {
            return bytes;
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

        public boolean hasEntityBlocks() {
            for (BlockState state : states) {
                if (state.getBlock() instanceof EntityBlock) {
                    return true;
                }
            }
            return false;
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
