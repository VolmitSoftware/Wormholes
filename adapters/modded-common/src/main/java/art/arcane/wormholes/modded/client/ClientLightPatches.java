package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.render.ProjectionCellKey;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntSupplier;

public final class ClientLightPatches {
    public static final int NO_LIGHT = -1;
    private static final int WORDS = ClientViewProtocol.BRICK_CELLS / 64;
    private static final int ALL_BORDERS = ClientViewSurface.BORDER_WEST | ClientViewSurface.BORDER_EAST
        | ClientViewSurface.BORDER_DOWN | ClientViewSurface.BORDER_UP | ClientViewSurface.BORDER_NORTH | ClientViewSurface.BORDER_SOUTH;
    private static final long[] BOUNDARY_CELLS = boundaryCells();
    private static volatile Binding binding;

    private final ClientViewSurface surface;
    private final Long2ObjectOpenHashMap<Section> sections;
    private volatile Long2ObjectMap<Section> published;
    private boolean dirty;
    private int skyDarken;
    private long refreshes;

    public ClientLightPatches(ClientViewSurface surface) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.sections = new Long2ObjectOpenHashMap<>(256);
        this.published = Long2ObjectMaps.emptyMap();
        this.skyDarken = surface.skyDarken();
    }

    public static int skyWithLocalDarken(int destinationSky, int localDarken) {
        return Math.min(15, destinationSky + Math.max(0, localDarken));
    }

    public static int blockWithLocalDarken(int destinationSky, int destinationBlock, int localDarken) {
        int target = Math.max(destinationBlock, destinationSky);
        return target > 15 - Math.max(0, localDarken) ? target : destinationBlock;
    }

    public static void bind(Object blockEngine, Object skyEngine, IntSupplier localSkyDarken, ClientLightPatches patches) {
        binding = new Binding(Objects.requireNonNull(blockEngine, "blockEngine"), Objects.requireNonNull(skyEngine, "skyEngine"),
            Objects.requireNonNull(localSkyDarken, "localSkyDarken"), Objects.requireNonNull(patches, "patches"));
    }

    public static void unbind(ClientLightPatches patches) {
        Binding active = binding;
        if (active != null && active.patches == patches) {
            binding = null;
        }
    }

    public static int patched(Object engine, int x, int y, int z) {
        Binding active = binding;
        if (active == null) {
            return NO_LIGHT;
        }
        if (engine == active.skyEngine) {
            return active.patches.value(true, x, y, z, active.localSkyDarken.getAsInt());
        }
        if (engine == active.blockEngine) {
            return active.patches.value(false, x, y, z, active.localSkyDarken.getAsInt());
        }
        return NO_LIGHT;
    }

    public static BlockState realState(Object engine, int x, int y, int z) {
        Binding active = binding;
        if (active == null || (engine != active.skyEngine && engine != active.blockEngine)) {
            return null;
        }
        ProjectionOverlay overlay = ProjectionOverlay.active();
        if (overlay == null) {
            return null;
        }
        ProjectionOverlay.Entry entry = overlay.get(ProjectionCellKey.pack(x, y, z));
        return entry == null || entry.pending() ? null : entry.shadow();
    }

    public int value(boolean sky, int x, int y, int z, int localDarken) {
        Section section = published.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        if (section == null) {
            return NO_LIGHT;
        }
        int cell = ClientViewProtocol.brickCellIndex(x, y, z);
        if ((section.mask[cell >>> 6] & (1L << (cell & 63))) == 0L) {
            return NO_LIGHT;
        }
        int packed = section.target[cell] & 0xFF;
        int destinationSky = packed >>> 4;
        return sky ? skyWithLocalDarken(destinationSky, localDarken) : blockWithLocalDarken(destinationSky, packed & 0x0F, localDarken);
    }

    public void refresh(int sectionX, int sectionY, int sectionZ, LongArrayList cellKeys, IntArrayList cellPortals, CellLight source) {
        long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
        long[] mask = new long[WORDS];
        byte[] target = new byte[ClientViewProtocol.BRICK_CELLS];
        IntOpenHashSet portals = new IntOpenHashSet(2);
        boolean any = false;
        int borders = 0;
        for (int index = 0; index < cellKeys.size(); index++) {
            long cell = cellKeys.getLong(index);
            int y = ProjectionCellKey.unpackY(cell);
            if (y >> 4 != sectionY) {
                continue;
            }
            int portalKey = cellPortals.getInt(index);
            int packed = source.light(cell, portalKey);
            if (packed < 0) {
                continue;
            }
            int cellIndex = ClientViewProtocol.brickCellIndex(ProjectionCellKey.unpackX(cell), y, ProjectionCellKey.unpackZ(cell));
            mask[cellIndex >>> 6] |= 1L << (cellIndex & 63);
            target[cellIndex] = (byte) (packed & 0xFF);
            if (borders != ALL_BORDERS) {
                borders |= cellBorders(cellIndex);
            }
            portals.add(portalKey);
            any = true;
        }
        Section previous = sections.get(key);
        if (!any) {
            if (previous != null) {
                sections.remove(key);
                changed(sectionX, sectionY, sectionZ, previous.borders);
            }
            return;
        }
        Section next = new Section(mask, target, portals, borders);
        if (previous != null && previous.sameLight(next)) {
            previous.portals = portals;
            return;
        }
        sections.put(key, next);
        changed(sectionX, sectionY, sectionZ, changedBorders(previous, next));
    }

    public LongOpenHashSet sectionsOf(int portalKey) {
        LongOpenHashSet out = new LongOpenHashSet();
        ObjectIterator<Long2ObjectMap.Entry<Section>> iterator = sections.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<Section> entry = iterator.next();
            if (entry.getValue().portals.contains(portalKey)) {
                out.add(entry.getLongKey());
            }
        }
        return out;
    }

    public void tick() {
        int darken = surface.skyDarken();
        if (darken != skyDarken) {
            skyDarken = darken;
            ObjectIterator<Long2ObjectMap.Entry<Section>> iterator = sections.long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                Long2ObjectMap.Entry<Section> entry = iterator.next();
                long key = entry.getLongKey();
                surface.lightChanged(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), entry.getValue().borders);
            }
        }
        if (dirty) {
            publish();
        }
    }

    public void clear() {
        ObjectIterator<Long2ObjectMap.Entry<Section>> iterator = sections.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<Section> entry = iterator.next();
            long key = entry.getLongKey();
            surface.lightChanged(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), entry.getValue().borders);
        }
        sections.clear();
        publish();
    }

    public void discard() {
        sections.clear();
        publish();
    }

    public int size() {
        return sections.size();
    }

    public long refreshes() {
        return refreshes;
    }

    public int maskedCells(int sectionX, int sectionY, int sectionZ) {
        Section section = sections.get(SectionPos.asLong(sectionX, sectionY, sectionZ));
        if (section == null) {
            return 0;
        }
        int count = 0;
        for (long word : section.mask) {
            count += Long.bitCount(word);
        }
        return count;
    }

    private void changed(int sectionX, int sectionY, int sectionZ, int borders) {
        dirty = true;
        refreshes++;
        surface.lightChanged(sectionX, sectionY, sectionZ, borders);
    }

    private static int changedBorders(Section previous, Section next) {
        if (previous == null) {
            return next.borders;
        }
        int borders = 0;
        for (int word = 0; word < WORDS; word++) {
            long cells = (previous.mask[word] | next.mask[word]) & BOUNDARY_CELLS[word];
            long maskChanges = previous.mask[word] ^ next.mask[word];
            while (cells != 0L) {
                int bit = Long.numberOfTrailingZeros(cells);
                cells &= cells - 1L;
                int cell = (word << 6) + bit;
                if ((maskChanges & (1L << bit)) == 0L && previous.target[cell] == next.target[cell]) {
                    continue;
                }
                borders |= cellBorders(cell);
                if (borders == ALL_BORDERS) {
                    return borders;
                }
            }
        }
        return borders;
    }

    private static long[] boundaryCells() {
        long[] cells = new long[WORDS];
        for (int cell = 0; cell < ClientViewProtocol.BRICK_CELLS; cell++) {
            if (cellBorders(cell) != 0) {
                cells[cell >>> 6] |= 1L << (cell & 63);
            }
        }
        return cells;
    }

    private static int cellBorders(int cell) {
        int x = cell & 15;
        int y = cell >>> 8;
        int z = (cell >>> 4) & 15;
        return (x == 0 ? ClientViewSurface.BORDER_WEST : 0) | (x == 15 ? ClientViewSurface.BORDER_EAST : 0)
            | (y == 0 ? ClientViewSurface.BORDER_DOWN : 0) | (y == 15 ? ClientViewSurface.BORDER_UP : 0)
            | (z == 0 ? ClientViewSurface.BORDER_NORTH : 0) | (z == 15 ? ClientViewSurface.BORDER_SOUTH : 0);
    }

    private void publish() {
        published = sections.isEmpty() ? Long2ObjectMaps.emptyMap() : new Long2ObjectOpenHashMap<>(sections);
        dirty = false;
    }

    public interface CellLight {
        int light(long cellKey, int portalKey);
    }

    private static final class Section {
        private final long[] mask;
        private final byte[] target;
        private final int borders;
        private IntOpenHashSet portals;

        private Section(long[] mask, byte[] target, IntOpenHashSet portals, int borders) {
            this.mask = mask;
            this.target = target;
            this.portals = portals;
            this.borders = borders;
        }

        private boolean sameLight(Section other) {
            return Arrays.equals(mask, other.mask) && Arrays.equals(target, other.target);
        }
    }

    private record Binding(Object blockEngine, Object skyEngine, IntSupplier localSkyDarken, ClientLightPatches patches) {
    }
}
