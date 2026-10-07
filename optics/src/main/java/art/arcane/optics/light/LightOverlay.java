package art.arcane.optics.light;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.function.Supplier;
import art.arcane.optics.spi.OpticsMetrics;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.claim.WorldOutput;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.view.WorldChangeTracker;

public final class LightOverlay<O, B, V extends ContentView<?, ?>> {
    private static final int SECTION_NIBBLE_BYTES = 2048;
    private static final long BASELINE_MAX_AGE_MILLIS = 2000L;

    private final Long2ObjectOpenHashMap<IntOpenHashSet> sentChunkSections = new Long2ObjectOpenHashMap<IntOpenHashSet>(8);
    private final Long2ObjectOpenHashMap<IntOpenHashSet> chunkToSections = new Long2ObjectOpenHashMap<IntOpenHashSet>(8);
    private final Long2ObjectOpenHashMap<IntOpenHashSet> pendingChunkSections = new Long2ObjectOpenHashMap<IntOpenHashSet>(8);
    private final Long2ObjectOpenHashMap<SectionBaseline[]> baselineCache = new Long2ObjectOpenHashMap<SectionBaseline[]>(8);
    private final Long2ObjectOpenHashMap<SectionClaims> sectionClaims = new Long2ObjectOpenHashMap<SectionClaims>(16);
    private final Long2ObjectOpenHashMap<IntOpenHashSet> currentChunkSections = new Long2ObjectOpenHashMap<IntOpenHashSet>(8);
    private final WorldOutput<O> output;
    private final Supplier<WorldChangeTracker> changes;
    private final OpticsMetrics metrics;
    private V indexedLocalView;
    private int indexedMinHeight;
    private int indexedMaxHeight;
    private boolean indexedSourceLighting;

    public LightOverlay(WorldOutput<O> output, Supplier<WorldChangeTracker> changes, OpticsMetrics metrics) {
        this.output = output;
        this.changes = changes;
        this.metrics = metrics;
    }

    public void apply(O observer,
                      V localView,
                      Long2ObjectMap<BlockClaim<B, V>> projectedClaims,
                      LongSet dirtyLocalKeys) {
        apply(observer, localView, projectedClaims, dirtyLocalKeys, true);
    }

    public void apply(O observer,
               V localView,
               Long2ObjectMap<BlockClaim<B, V>> projectedClaims,
               LongSet dirtyLocalKeys,
               boolean sourceLightingEnabled) {
        if (observer == null || !output.online(observer)) {
            return;
        }
        Long2ObjectOpenHashMap<IntOpenHashSet> currentSections = updateCurrentSections(
            localView, projectedClaims, dirtyLocalKeys, sourceLightingEnabled);
        revertStaleSections(observer, localView, currentSections);
        prunePendingSections(currentSections);

        if (dirtyLocalKeys == null) {
            mergePendingSections(currentSections);
        } else if (!dirtyLocalKeys.isEmpty()) {
            chunkToSections.clear();
            collectDirtySections(localView, dirtyLocalKeys, chunkToSections);
            retainCurrentSections(chunkToSections, currentSections);
            mergePendingSections(chunkToSections);
        }
        if (pendingChunkSections.isEmpty()) {
            return;
        }

        int remainingSections = lightingSectionBudget();
        Iterator<Long2ObjectMap.Entry<IntOpenHashSet>> iterator = pendingChunkSections.long2ObjectEntrySet().iterator();
        while (iterator.hasNext() && remainingSections > 0) {
            Long2ObjectMap.Entry<IntOpenHashSet> entry = iterator.next();
            long chunkKey = entry.getLongKey();
            IntOpenHashSet current = currentSections.get(chunkKey);
            if (current == null || current.isEmpty()) {
                iterator.remove();
                continue;
            }
            int chunkX = CellKeys.chunkX(chunkKey);
            int chunkZ = CellKeys.chunkZ(chunkKey);
            IntOpenHashSet selected = selectSections(entry.getValue(), remainingSections);
            if (selected.isEmpty()) {
                continue;
            }
            if (!sendChunkLight(observer, localView, chunkX, chunkZ, selected)) {
                continue;
            }
            recordSentSections(chunkKey, selected);
            removeSections(entry.getValue(), selected);
            remainingSections -= selected.size();
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    private Long2ObjectOpenHashMap<IntOpenHashSet> updateCurrentSections(
        V localView,
        Long2ObjectMap<BlockClaim<B, V>> projectedClaims,
        LongSet dirtyLocalKeys,
        boolean sourceLightingEnabled
    ) {
        int minHeight = localView.getMinHeight();
        int maxHeight = localView.getMaxHeight();
        if (dirtyLocalKeys != null && indexedLocalView == localView
            && indexedMinHeight == minHeight && indexedMaxHeight == maxHeight
            && indexedSourceLighting == sourceLightingEnabled) {
            LongIterator dirty = dirtyLocalKeys.iterator();
            while (dirty.hasNext()) {
                long key = dirty.nextLong();
                updateSectionClaim(key, projectedClaims.get(key), sourceLightingEnabled, minHeight, maxHeight);
            }
            return currentChunkSections;
        }
        indexedLocalView = null;
        for (SectionClaims claims : sectionClaims.values()) {
            claims.clear();
        }
        currentChunkSections.clear();
        ObjectIterator<Long2ObjectMap.Entry<BlockClaim<B, V>>> iterator = Long2ObjectMaps.fastIterator(projectedClaims);
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<BlockClaim<B, V>> entry = iterator.next();
            updateSectionClaim(entry.getLongKey(), entry.getValue(), sourceLightingEnabled, minHeight, maxHeight);
        }
        sectionClaims.values().removeIf(claims -> claims.size == 0);
        indexedMinHeight = minHeight;
        indexedMaxHeight = maxHeight;
        indexedSourceLighting = sourceLightingEnabled;
        indexedLocalView = localView;
        return currentChunkSections;
    }

    private void updateSectionClaim(long key, BlockClaim<B, V> claim, boolean sourceLightingEnabled,
                                    int minHeight, int maxHeight) {
        int worldY = CellKeys.unpackY(key);
        if (worldY < minHeight || worldY >= maxHeight) {
            return;
        }
        int worldX = CellKeys.unpackX(key);
        int worldZ = CellKeys.unpackZ(key);
        long chunkKey = CellKeys.chunkKey(worldX >> 4, worldZ >> 4);
        long sectionKey = CellKeys.sectionKey(worldX >> 4, worldY >> 4, worldZ >> 4);
        SectionClaims claims = sectionClaims.get(sectionKey);
        int nibbleIndex = ((worldY & 0xF) << 8) | ((worldZ & 0xF) << 4) | (worldX & 0xF);
        if (claim == null || !claim.requiresLightOverlay(sourceLightingEnabled)) {
            if (claims == null || !claims.remove(nibbleIndex) || claims.size > 0) {
                return;
            }
            sectionClaims.remove(sectionKey);
            IntOpenHashSet sections = currentChunkSections.get(chunkKey);
            if (sections != null) {
                sections.remove(worldY >> 4);
                if (sections.isEmpty()) {
                    currentChunkSections.remove(chunkKey);
                }
            }
            return;
        }
        if (claims == null) {
            claims = new SectionClaims();
            sectionClaims.put(sectionKey, claims);
        }
        if (claims.size == 0) {
            IntOpenHashSet sections = currentChunkSections.get(chunkKey);
            if (sections == null) {
                sections = new IntOpenHashSet(4);
                currentChunkSections.put(chunkKey, sections);
            }
            sections.add(worldY >> 4);
        }
        claims.put(nibbleIndex, claim);
    }

    private void collectDirtySections(V localView, LongSet dirtyKeys, Long2ObjectOpenHashMap<IntOpenHashSet> chunkToSections) {
        LongIterator iterator = dirtyKeys.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            int worldX = CellKeys.unpackX(packed);
            int worldY = CellKeys.unpackY(packed);
            int worldZ = CellKeys.unpackZ(packed);
            if (!isWorldYInsideWorld(localView, worldY)) {
                continue;
            }
            long chunkKey = CellKeys.chunkKey(worldX >> 4, worldZ >> 4);
            IntOpenHashSet set = chunkToSections.get(chunkKey);
            if (set == null) {
                set = new IntOpenHashSet(4);
                chunkToSections.put(chunkKey, set);
            }
            set.add(worldY >> 4);
        }
    }

    private void revertStaleSections(
        O observer,
        V localView,
        Long2ObjectOpenHashMap<IntOpenHashSet> currentSections
    ) {
        Iterator<Long2ObjectMap.Entry<IntOpenHashSet>> staleIt = sentChunkSections.long2ObjectEntrySet().iterator();
        while (staleIt.hasNext()) {
            Long2ObjectMap.Entry<IntOpenHashSet> entry = staleIt.next();
            long key = entry.getLongKey();
            IntOpenHashSet stale = new IntOpenHashSet(entry.getValue());
            IntOpenHashSet current = currentSections.get(key);
            if (current != null) {
                stale.removeAll(current);
            }
            if (stale.isEmpty()) {
                continue;
            }
            int chunkX = CellKeys.chunkX(key);
            int chunkZ = CellKeys.chunkZ(key);
            if (!output.chunkSent(observer, chunkX, chunkZ)) {
                entry.getValue().removeAll(stale);
            } else if (!sendLocalChunkLight(observer, localView, chunkX, chunkZ, stale)) {
                continue;
            } else {
                entry.getValue().removeAll(stale);
            }
            if (entry.getValue().isEmpty()) {
                baselineCache.remove(key);
                staleIt.remove();
            }
        }
    }

    public void revert(O observer, V localView) {
        pendingChunkSections.clear();
        sectionClaims.clear();
        currentChunkSections.clear();
        indexedLocalView = null;
        if (sentChunkSections.isEmpty()) {
            baselineCache.clear();
            return;
        }
        if (observer == null || !output.online(observer)) {
            return;
        }
        if (localView == null) {
            return;
        }

        Iterator<Long2ObjectMap.Entry<IntOpenHashSet>> iterator = sentChunkSections.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<IntOpenHashSet> entry = iterator.next();
            long key = entry.getLongKey();
            int chunkX = CellKeys.chunkX(key);
            int chunkZ = CellKeys.chunkZ(key);
            if (!output.chunkSent(observer, chunkX, chunkZ)
                || sendLocalChunkLight(observer, localView, chunkX, chunkZ, entry.getValue())) {
                baselineCache.remove(key);
                iterator.remove();
            }
        }
        if (sentChunkSections.isEmpty()) {
            baselineCache.clear();
        }
    }

    public void discard() {
        sentChunkSections.clear();
        pendingChunkSections.clear();
        chunkToSections.clear();
        baselineCache.clear();
        sectionClaims.clear();
        currentChunkSections.clear();
        indexedLocalView = null;
    }

    public void discardChunk(int chunkX, int chunkZ) {
        long chunkKey = CellKeys.chunkKey(chunkX, chunkZ);
        sentChunkSections.remove(chunkKey);
        pendingChunkSections.remove(chunkKey);
        chunkToSections.remove(chunkKey);
        baselineCache.remove(chunkKey);
    }

    public void discardUnsentChunks(O observer) {
        Iterator<Long2ObjectMap.Entry<IntOpenHashSet>> iterator = sentChunkSections.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<IntOpenHashSet> entry = iterator.next();
            long chunkKey = entry.getLongKey();
            int chunkX = CellKeys.chunkX(chunkKey);
            int chunkZ = CellKeys.chunkZ(chunkKey);
            if (output.chunkSent(observer, chunkX, chunkZ)) {
                continue;
            }
            baselineCache.remove(chunkKey);
            iterator.remove();
        }
    }

    public boolean isIdle() {
        return sentChunkSections.isEmpty() && pendingChunkSections.isEmpty();
    }

    public boolean hasPendingUpdates() {
        return !pendingChunkSections.isEmpty();
    }

    private void mergePendingSections(Long2ObjectOpenHashMap<IntOpenHashSet> sections) {
        for (Long2ObjectMap.Entry<IntOpenHashSet> entry : sections.long2ObjectEntrySet()) {
            IntOpenHashSet pending = pendingChunkSections.get(entry.getLongKey());
            if (pending == null) {
                pending = new IntOpenHashSet(entry.getValue().size());
                pendingChunkSections.put(entry.getLongKey(), pending);
            }
            pending.addAll(entry.getValue());
        }
    }

    private static void retainCurrentSections(
        Long2ObjectOpenHashMap<IntOpenHashSet> sections,
        Long2ObjectOpenHashMap<IntOpenHashSet> currentSections
    ) {
        Iterator<Long2ObjectMap.Entry<IntOpenHashSet>> iterator = sections.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<IntOpenHashSet> entry = iterator.next();
            IntOpenHashSet current = currentSections.get(entry.getLongKey());
            if (current == null) {
                iterator.remove();
                continue;
            }
            entry.getValue().retainAll(current);
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    private void prunePendingSections(Long2ObjectOpenHashMap<IntOpenHashSet> currentSections) {
        retainCurrentSections(pendingChunkSections, currentSections);
    }

    private int lightingSectionBudget() {
        return output.lightSectionBudget();
    }

    public static int lightingSectionBudget(boolean adaptiveLighting, int configuredMaxSections) {
        if (!adaptiveLighting) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, configuredMaxSections);
    }

    public static IntOpenHashSet selectSections(IntOpenHashSet sections, int maxSections) {
        IntOpenHashSet selected = new IntOpenHashSet(Math.min(sections.size(), maxSections));
        IntIterator iterator = sections.iterator();
        while (iterator.hasNext() && selected.size() < maxSections) {
            selected.add(iterator.nextInt());
        }
        return selected;
    }

    public static void removeSections(IntOpenHashSet sections, IntOpenHashSet selected) {
        IntIterator iterator = selected.iterator();
        while (iterator.hasNext()) {
            sections.remove(iterator.nextInt());
        }
    }

    private void recordSentSections(long chunkKey, IntSet sections) {
        IntOpenHashSet sent = sentChunkSections.get(chunkKey);
        if (sent == null) {
            sent = new IntOpenHashSet(sections.size());
            sentChunkSections.put(chunkKey, sent);
        }
        sent.addAll(sections);
    }

    private boolean sendChunkLight(O observer,
                                V localView,
                                int chunkX,
                                int chunkZ,
                                IntSet dirtySections) {
        if (!output.chunkSent(observer, chunkX, chunkZ)) {
            return false;
        }
        int minSec = localView.getMinHeight() >> 4;
        int maxSec = (localView.getMaxHeight() - 1) >> 4;
        int localSkyDarken = localView.getSkyDarken();
        int maskBitCount = (maxSec - minSec + 1) + 2;
        int sectionCount = countValidSections(dirtySections, minSec, maxSec);
        if (sectionCount == 0) {
            return true;
        }

        BitSet blockMask = new BitSet(maskBitCount);
        BitSet skyMask = new BitSet(maskBitCount);
        BitSet emptyBlockMask = new BitSet(maskBitCount);
        BitSet emptySkyMask = new BitSet(maskBitCount);

        byte[][] skyArrays = new byte[sectionCount][];
        byte[][] blockArrays = new byte[sectionCount][];

        int[] orderedSections = sortedValidSections(dirtySections, minSec, maxSec);
        int arrIdx = 0;
        for (int section : orderedSections) {
            int maskIndex = (section - minSec) + 1;

            SectionBaseline baseline = localBaseline(localView, chunkX, chunkZ, section, minSec, maxSec);
            if (baseline == null) {
                return false;
            }
            byte[] skyArr = baseline.sky.clone();
            byte[] blockArr = baseline.block.clone();
            overlayProjectedLight(sectionClaims.get(CellKeys.pack(chunkX, section, chunkZ)),
                localSkyDarken, skyArr, blockArr);

            skyArrays[arrIdx] = skyArr;
            blockArrays[arrIdx] = blockArr;
            blockMask.set(maskIndex);
            skyMask.set(maskIndex);
            arrIdx++;
        }

        output.light(observer, new ChunkLight(chunkX, chunkZ, blockMask, skyMask,
            emptyBlockMask, emptySkyMask, skyArrays, blockArrays));
        metrics.packet();
        return true;
    }

    private void overlayProjectedLight(SectionClaims projectedClaims,
                                      int localSkyDarken,
                                      byte[] skyArr,
                                      byte[] blockArr) {
        if (projectedClaims == null) {
            return;
        }
        for (int index = 0; index < projectedClaims.size; index++) {
            BlockClaim<B, V> claim = projectedClaims.claims[index];
            int nibbleIdx = projectedClaims.nibbleIndices[index];
            if (claim.isFullBright()) {
                writeLightNibble(skyArr, blockArr, nibbleIdx, 15, 15);
                continue;
            }
            long remoteKey = claim.getLightRemoteKey();
            V sourceView = claim.getLightView();
            int rx = CellKeys.unpackX(remoteKey);
            int ry = CellKeys.unpackY(remoteKey);
            int rz = CellKeys.unpackZ(remoteKey);
            if (ry < sourceView.getMinHeight() || ry > sourceView.getMaxHeight() - 1) {
                continue;
            }
            int packedLight = sourceView.getLight(rx, ry, rz);
            if (packedLight == ContentView.LIGHT_UNAVAILABLE) {
                continue;
            }
            int rawSky = ContentView.unpackSkyLight(packedLight);
            int rawBlock = ContentView.unpackBlockLight(packedLight);
            int sourceDarken = sourceView.getSkyDarken();
            int sourceSkyBrightness = Math.max(0, rawSky - sourceDarken);
            int sky = Math.min(15, sourceSkyBrightness + localSkyDarken);
            int target = Math.max(rawBlock, sourceSkyBrightness);
            int block = target > 15 - localSkyDarken ? target : rawBlock;
            writeLightNibble(skyArr, blockArr, nibbleIdx, sky, block);
        }
    }

    public static void writeLightNibble(byte[] skyArr, byte[] blockArr, int nibbleIdx, int sky, int block) {
        int byteIdx = nibbleIdx >> 1;
        if ((nibbleIdx & 1) == 0) {
            skyArr[byteIdx] = (byte) ((skyArr[byteIdx] & 0xF0) | (sky & 0x0F));
            blockArr[byteIdx] = (byte) ((blockArr[byteIdx] & 0xF0) | (block & 0x0F));
        } else {
            skyArr[byteIdx] = (byte) ((skyArr[byteIdx] & 0x0F) | ((sky & 0x0F) << 4));
            blockArr[byteIdx] = (byte) ((blockArr[byteIdx] & 0x0F) | ((block & 0x0F) << 4));
        }
    }

    private SectionBaseline localBaseline(V localView, int chunkX, int chunkZ, int section, int minSection, int maxSection) {
        long chunkKey = CellKeys.chunkKey(chunkX, chunkZ);
        int sectionCount = maxSection - minSection + 1;
        SectionBaseline[] sections = baselineCache.get(chunkKey);
        if (sections == null || sections.length != sectionCount) {
            sections = new SectionBaseline[sectionCount];
            baselineCache.put(chunkKey, sections);
        }
        int index = section - minSection;
        SectionBaseline cached = sections[index];
        long now = System.currentTimeMillis();
        WorldChangeTracker tracker = changes.get();
        if (cached != null
            && tracker != null
            && now - cached.readMillis <= BASELINE_MAX_AGE_MILLIS
            && localView.worldId() != null
            && !tracker.dirtySince(localView.worldId(), chunkX - 1, chunkZ - 1, chunkX + 1, chunkZ + 1, cached.trackerVersion)) {
            return cached;
        }

        long trackerVersion = tracker == null ? Long.MIN_VALUE : tracker.currentVersion();
        byte[] skyArr = new byte[SECTION_NIBBLE_BYTES];
        byte[] blockArr = new byte[SECTION_NIBBLE_BYTES];
        int sectionMinY = section << 4;
        for (int ly = 0; ly < 16; ly++) {
            int y = sectionMinY + ly;
            for (int lz = 0; lz < 16; lz++) {
                int z = (chunkZ << 4) + lz;
                for (int lx = 0; lx < 16; lx++) {
                    int x = (chunkX << 4) + lx;
                    int packedLight = localView.getLight(x, y, z);
                    if (packedLight == ContentView.LIGHT_UNAVAILABLE) {
                        return null;
                    }
                    writeLightNibble(skyArr, blockArr, (ly << 8) | (lz << 4) | lx,
                        ContentView.unpackSkyLight(packedLight), ContentView.unpackBlockLight(packedLight));
                }
            }
        }
        SectionBaseline fresh = new SectionBaseline(skyArr, blockArr, trackerVersion, now);
        sections[index] = fresh;
        return fresh;
    }

    public static int countValidSections(IntSet dirtySections, int minSection, int maxSection) {
        int count = 0;
        IntIterator iterator = dirtySections.iterator();
        while (iterator.hasNext()) {
            int section = iterator.nextInt();
            if (isSectionInsideWorld(section, minSection, maxSection)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isSectionInsideWorld(int section, int minSection, int maxSection) {
        return section >= minSection && section <= maxSection;
    }

    private boolean sendLocalChunkLight(O observer, V localView, int chunkX, int chunkZ, IntSet sections) {
        if (!output.chunkSent(observer, chunkX, chunkZ)) {
            return true;
        }
        int minSec = localView.getMinHeight() >> 4;
        int maxSec = (localView.getMaxHeight() - 1) >> 4;
        int maskBitCount = (maxSec - minSec + 1) + 2;
        int sectionCount = countValidSections(sections, minSec, maxSec);
        if (sectionCount == 0) {
            return true;
        }

        BitSet blockMask = new BitSet(maskBitCount);
        BitSet skyMask = new BitSet(maskBitCount);
        BitSet emptyBlockMask = new BitSet(maskBitCount);
        BitSet emptySkyMask = new BitSet(maskBitCount);

        byte[][] skyArrays = new byte[sectionCount][];
        byte[][] blockArrays = new byte[sectionCount][];

        int[] orderedSections = sortedValidSections(sections, minSec, maxSec);
        int arrIdx = 0;
        for (int section : orderedSections) {
            int sectionMinY = section << 4;
            int maskIndex = (section - minSec) + 1;

            byte[] skyArr = new byte[SECTION_NIBBLE_BYTES];
            byte[] blockArr = new byte[SECTION_NIBBLE_BYTES];

            for (int ly = 0; ly < 16; ly++) {
                int y = sectionMinY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int z = (chunkZ << 4) + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int x = (chunkX << 4) + lx;
                        int packedLight = localView.getLight(x, y, z);
                        if (packedLight == ContentView.LIGHT_UNAVAILABLE) {
                            return false;
                        }
                        writeLightNibble(skyArr, blockArr, (ly << 8) | (lz << 4) | lx,
                            ContentView.unpackSkyLight(packedLight), ContentView.unpackBlockLight(packedLight));
                    }
                }
            }

            skyArrays[arrIdx] = skyArr;
            blockArrays[arrIdx] = blockArr;
            blockMask.set(maskIndex);
            skyMask.set(maskIndex);
            arrIdx++;
        }

        output.light(observer, new ChunkLight(chunkX, chunkZ, blockMask, skyMask,
            emptyBlockMask, emptySkyMask, skyArrays, blockArrays));
        metrics.packet();
        return true;
    }

    public static int[] sortedValidSections(IntSet sections, int minSection, int maxSection) {
        int[] ordered = new int[countValidSections(sections, minSection, maxSection)];
        int index = 0;
        IntIterator iterator = sections.iterator();
        while (iterator.hasNext()) {
            int section = iterator.nextInt();
            if (isSectionInsideWorld(section, minSection, maxSection)) {
                ordered[index++] = section;
            }
        }
        Arrays.sort(ordered);
        return ordered;
    }

    private boolean isWorldYInsideWorld(V view, int y) {
        return y >= view.getMinHeight() && y < view.getMaxHeight();
    }

    private final class SectionClaims {
        private final short[] slots = new short[4096];
        private int[] nibbleIndices = new int[16];
        @SuppressWarnings("unchecked")
        private BlockClaim<B, V>[] claims = (BlockClaim<B, V>[]) new BlockClaim<?, ?>[16];
        private int size;

        private void put(int nibbleIndex, BlockClaim<B, V> claim) {
            int slot = slots[nibbleIndex] - 1;
            if (slot >= 0) {
                claims[slot] = claim;
                return;
            }
            if (size == claims.length) {
                int capacity = size << 1;
                nibbleIndices = Arrays.copyOf(nibbleIndices, capacity);
                claims = Arrays.copyOf(claims, capacity);
            }
            nibbleIndices[size] = nibbleIndex;
            claims[size] = claim;
            size++;
            slots[nibbleIndex] = (short) size;
        }

        private boolean remove(int nibbleIndex) {
            int slot = slots[nibbleIndex] - 1;
            if (slot < 0) {
                return false;
            }
            int last = --size;
            if (slot != last) {
                int movedNibble = nibbleIndices[last];
                nibbleIndices[slot] = movedNibble;
                claims[slot] = claims[last];
                slots[movedNibble] = (short) (slot + 1);
            }
            claims[last] = null;
            slots[nibbleIndex] = 0;
            return true;
        }

        private void clear() {
            for (int index = 0; index < size; index++) {
                slots[nibbleIndices[index]] = 0;
            }
            Arrays.fill(claims, 0, size, null);
            size = 0;
        }
    }

    private static final class SectionBaseline {
        private final byte[] sky;
        private final byte[] block;
        private final long trackerVersion;
        private final long readMillis;

        private SectionBaseline(byte[] sky, byte[] block, long trackerVersion, long readMillis) {
            this.sky = sky;
            this.block = block;
            this.trackerVersion = trackerVersion;
            this.readMillis = readMillis;
        }
    }

    public record ChunkLight(int chunkX, int chunkZ, BitSet blockMask, BitSet skyMask,
                             BitSet emptyBlockMask, BitSet emptySkyMask, byte[][] skyArrays, byte[][] blockArrays) {
    }
}
