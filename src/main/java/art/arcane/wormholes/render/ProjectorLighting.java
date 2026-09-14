package art.arcane.wormholes.render;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
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

import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.service.WormholesTelemetry;

public final class ProjectorLighting {
    private static final int SECTION_NIBBLE_BYTES = 2048;
    private static final long BASELINE_MAX_AGE_MILLIS = 2000L;

    private final Long2ObjectOpenHashMap<IntOpenHashSet> sentChunkSections = new Long2ObjectOpenHashMap<IntOpenHashSet>(8);
    private final Long2ObjectOpenHashMap<IntOpenHashSet> chunkToSections = new Long2ObjectOpenHashMap<IntOpenHashSet>(8);
    private final Long2ObjectOpenHashMap<IntOpenHashSet> pendingChunkSections = new Long2ObjectOpenHashMap<IntOpenHashSet>(8);
    private final Long2ObjectOpenHashMap<SectionBaseline[]> baselineCache = new Long2ObjectOpenHashMap<SectionBaseline[]>(8);
    private final Long2ObjectOpenHashMap<SectionClaims> sectionClaims = new Long2ObjectOpenHashMap<SectionClaims>(16);
    private final Long2ObjectOpenHashMap<IntOpenHashSet> currentChunkSections = new Long2ObjectOpenHashMap<IntOpenHashSet>(8);
    private final ProjectionChunkVisibility chunkVisibility;
    private final LightPacketSender packetSender;
    private ProjectionWorldView indexedLocalView;
    private int indexedMinHeight;
    private int indexedMaxHeight;
    private boolean indexedSourceLighting;

    public ProjectorLighting() {
        this(WormholesPlatform::isChunkSent, ProjectorLighting::sendPacket);
    }

    ProjectorLighting(ProjectionChunkVisibility chunkVisibility) {
        this(chunkVisibility, ProjectorLighting::sendPacket);
    }

    ProjectorLighting(ProjectionChunkVisibility chunkVisibility, LightPacketSender packetSender) {
        this.chunkVisibility = chunkVisibility;
        this.packetSender = packetSender;
    }

    public void apply(Player observer,
                      ProjectionWorldView localView,
                      Long2ObjectMap<ProjectedBlockClaim> projectedClaims,
                      LongSet dirtyLocalKeys) {
        apply(observer, localView, projectedClaims, dirtyLocalKeys, true);
    }

    void apply(Player observer,
               ProjectionWorldView localView,
               Long2ObjectMap<ProjectedBlockClaim> projectedClaims,
               LongSet dirtyLocalKeys,
               boolean sourceLightingEnabled) {
        if (observer == null || !observer.isOnline()) {
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
            int chunkX = (int) (chunkKey >> 32);
            int chunkZ = (int) chunkKey;
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
        ProjectionWorldView localView,
        Long2ObjectMap<ProjectedBlockClaim> projectedClaims,
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
        ObjectIterator<Long2ObjectMap.Entry<ProjectedBlockClaim>> iterator = Long2ObjectMaps.fastIterator(projectedClaims);
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<ProjectedBlockClaim> entry = iterator.next();
            updateSectionClaim(entry.getLongKey(), entry.getValue(), sourceLightingEnabled, minHeight, maxHeight);
        }
        sectionClaims.values().removeIf(claims -> claims.size == 0);
        indexedMinHeight = minHeight;
        indexedMaxHeight = maxHeight;
        indexedSourceLighting = sourceLightingEnabled;
        indexedLocalView = localView;
        return currentChunkSections;
    }

    private void updateSectionClaim(long key, ProjectedBlockClaim claim, boolean sourceLightingEnabled,
                                    int minHeight, int maxHeight) {
        int worldY = ProjectionCellKey.unpackY(key);
        if (worldY < minHeight || worldY >= maxHeight) {
            return;
        }
        int worldX = ProjectionCellKey.unpackX(key);
        int worldZ = ProjectionCellKey.unpackZ(key);
        long chunkKey = internalChunkKey(worldX >> 4, worldZ >> 4);
        long sectionKey = ProjectionCellKey.pack(worldX >> 4, worldY >> 4, worldZ >> 4);
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

    private void collectDirtySections(ProjectionWorldView localView, LongSet dirtyKeys, Long2ObjectOpenHashMap<IntOpenHashSet> chunkToSections) {
        LongIterator iterator = dirtyKeys.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            int worldX = ProjectionCellKey.unpackX(packed);
            int worldY = ProjectionCellKey.unpackY(packed);
            int worldZ = ProjectionCellKey.unpackZ(packed);
            if (!isWorldYInsideWorld(localView, worldY)) {
                continue;
            }
            long chunkKey = (((long) (worldX >> 4)) << 32) | (((long) (worldZ >> 4)) & 0xFFFFFFFFL);
            IntOpenHashSet set = chunkToSections.get(chunkKey);
            if (set == null) {
                set = new IntOpenHashSet(4);
                chunkToSections.put(chunkKey, set);
            }
            set.add(worldY >> 4);
        }
    }

    private void revertStaleSections(
        Player observer,
        ProjectionWorldView localView,
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
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            if (!chunkVisibility.isChunkSent(observer, chunkX, chunkZ)) {
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

    public void revert(Player observer, ProjectionWorldView localView) {
        pendingChunkSections.clear();
        sectionClaims.clear();
        currentChunkSections.clear();
        indexedLocalView = null;
        if (sentChunkSections.isEmpty()) {
            baselineCache.clear();
            return;
        }
        if (observer == null || !observer.isOnline()) {
            return;
        }
        if (localView == null) {
            return;
        }

        Iterator<Long2ObjectMap.Entry<IntOpenHashSet>> iterator = sentChunkSections.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<IntOpenHashSet> entry = iterator.next();
            long key = entry.getLongKey();
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            if (!chunkVisibility.isChunkSent(observer, chunkX, chunkZ)
                || sendLocalChunkLight(observer, localView, chunkX, chunkZ, entry.getValue())) {
                baselineCache.remove(key);
                iterator.remove();
            }
        }
        if (sentChunkSections.isEmpty()) {
            baselineCache.clear();
        }
    }

    void discard() {
        sentChunkSections.clear();
        pendingChunkSections.clear();
        chunkToSections.clear();
        baselineCache.clear();
        sectionClaims.clear();
        currentChunkSections.clear();
        indexedLocalView = null;
    }

    void discardChunk(int chunkX, int chunkZ) {
        long chunkKey = internalChunkKey(chunkX, chunkZ);
        sentChunkSections.remove(chunkKey);
        pendingChunkSections.remove(chunkKey);
        chunkToSections.remove(chunkKey);
        baselineCache.remove(chunkKey);
    }

    void discardUnsentChunks(Player observer) {
        Iterator<Long2ObjectMap.Entry<IntOpenHashSet>> iterator = sentChunkSections.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<IntOpenHashSet> entry = iterator.next();
            long chunkKey = entry.getLongKey();
            int chunkX = (int) (chunkKey >> 32);
            int chunkZ = (int) chunkKey;
            if (chunkVisibility.isChunkSent(observer, chunkX, chunkZ)) {
                continue;
            }
            baselineCache.remove(chunkKey);
            iterator.remove();
        }
    }

    boolean isIdle() {
        return sentChunkSections.isEmpty() && pendingChunkSections.isEmpty();
    }

    boolean hasPendingUpdates() {
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

    private static int lightingSectionBudget() {
        return lightingSectionBudget(Settings.ADAPTIVE_LIGHTING, Settings.LIGHTING_MAX_SECTIONS_PER_PASS);
    }

    static int lightingSectionBudget(boolean adaptiveLighting, int configuredMaxSections) {
        if (!adaptiveLighting) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, configuredMaxSections);
    }

    static IntOpenHashSet selectSections(IntOpenHashSet sections, int maxSections) {
        IntOpenHashSet selected = new IntOpenHashSet(Math.min(sections.size(), maxSections));
        IntIterator iterator = sections.iterator();
        while (iterator.hasNext() && selected.size() < maxSections) {
            selected.add(iterator.nextInt());
        }
        return selected;
    }

    static void removeSections(IntOpenHashSet sections, IntOpenHashSet selected) {
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

    private boolean sendChunkLight(Player observer,
                                ProjectionWorldView localView,
                                int chunkX,
                                int chunkZ,
                                IntSet dirtySections) {
        if (!chunkVisibility.isChunkSent(observer, chunkX, chunkZ)) {
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
            overlayProjectedLight(sectionClaims.get(ProjectionCellKey.pack(chunkX, section, chunkZ)),
                localSkyDarken, skyArr, blockArr);

            skyArrays[arrIdx] = skyArr;
            blockArrays[arrIdx] = blockArr;
            blockMask.set(maskIndex);
            skyMask.set(maskIndex);
            arrIdx++;
        }

        LightData data = new LightData(true, blockMask, skyMask, emptyBlockMask, emptySkyMask,
            sectionCount, sectionCount, skyArrays, blockArrays);
        packetSender.send(observer, chunkX, chunkZ, data);
        WormholesTelemetry.countPacket();
        return true;
    }

    private static void overlayProjectedLight(SectionClaims projectedClaims,
                                      int localSkyDarken,
                                      byte[] skyArr,
                                      byte[] blockArr) {
        if (projectedClaims == null) {
            return;
        }
        for (int index = 0; index < projectedClaims.size; index++) {
            ProjectedBlockClaim claim = projectedClaims.claims[index];
            int nibbleIdx = projectedClaims.nibbleIndices[index];
            if (claim.isFullBright()) {
                writeLightNibble(skyArr, blockArr, nibbleIdx, 15, 15);
                continue;
            }
            long remoteKey = claim.getLightRemoteKey();
            ProjectionWorldView sourceView = claim.getLightView();
            int rx = ProjectionCellKey.unpackX(remoteKey);
            int ry = ProjectionCellKey.unpackY(remoteKey);
            int rz = ProjectionCellKey.unpackZ(remoteKey);
            if (ry < sourceView.getMinHeight() || ry > sourceView.getMaxHeight() - 1) {
                continue;
            }
            int packedLight = sourceView.getLight(rx, ry, rz);
            if (packedLight == ProjectionWorldView.LIGHT_UNAVAILABLE) {
                continue;
            }
            int rawSky = ProjectionWorldView.unpackSkyLight(packedLight);
            int rawBlock = ProjectionWorldView.unpackBlockLight(packedLight);
            int sourceDarken = sourceView.getSkyDarken();
            int sourceSkyBrightness = Math.max(0, rawSky - sourceDarken);
            int sky = Math.min(15, sourceSkyBrightness + localSkyDarken);
            int target = Math.max(rawBlock, sourceSkyBrightness);
            int block = target > 15 - localSkyDarken ? target : rawBlock;
            writeLightNibble(skyArr, blockArr, nibbleIdx, sky, block);
        }
    }

    static void writeLightNibble(byte[] skyArr, byte[] blockArr, int nibbleIdx, int sky, int block) {
        int byteIdx = nibbleIdx >> 1;
        if ((nibbleIdx & 1) == 0) {
            skyArr[byteIdx] = (byte) ((skyArr[byteIdx] & 0xF0) | (sky & 0x0F));
            blockArr[byteIdx] = (byte) ((blockArr[byteIdx] & 0xF0) | (block & 0x0F));
        } else {
            skyArr[byteIdx] = (byte) ((skyArr[byteIdx] & 0x0F) | ((sky & 0x0F) << 4));
            blockArr[byteIdx] = (byte) ((blockArr[byteIdx] & 0x0F) | ((block & 0x0F) << 4));
        }
    }

    private SectionBaseline localBaseline(ProjectionWorldView localView, int chunkX, int chunkZ, int section, int minSection, int maxSection) {
        long chunkKey = (((long) chunkX) << 32) | (((long) chunkZ) & 0xFFFFFFFFL);
        int sectionCount = maxSection - minSection + 1;
        SectionBaseline[] sections = baselineCache.get(chunkKey);
        if (sections == null || sections.length != sectionCount) {
            sections = new SectionBaseline[sectionCount];
            baselineCache.put(chunkKey, sections);
        }
        int index = section - minSection;
        SectionBaseline cached = sections[index];
        long now = System.currentTimeMillis();
        ProjectionWorldChangeTracker tracker = Wormholes.projectionChangeTracker;
        if (cached != null
            && tracker != null
            && now - cached.readMillis <= BASELINE_MAX_AGE_MILLIS
            && localView.getWorld() != null
            && !tracker.dirtySince(localView.getWorld().getUID(), chunkX - 1, chunkZ - 1, chunkX + 1, chunkZ + 1, cached.trackerVersion)) {
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
                    if (packedLight == ProjectionWorldView.LIGHT_UNAVAILABLE) {
                        return null;
                    }
                    writeLightNibble(skyArr, blockArr, (ly << 8) | (lz << 4) | lx,
                        ProjectionWorldView.unpackSkyLight(packedLight), ProjectionWorldView.unpackBlockLight(packedLight));
                }
            }
        }
        SectionBaseline fresh = new SectionBaseline(skyArr, blockArr, trackerVersion, now);
        sections[index] = fresh;
        return fresh;
    }

    static int countValidSections(IntSet dirtySections, int minSection, int maxSection) {
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

    static boolean isSectionInsideWorld(int section, int minSection, int maxSection) {
        return section >= minSection && section <= maxSection;
    }

    private boolean sendLocalChunkLight(Player observer, ProjectionWorldView localView, int chunkX, int chunkZ, IntSet sections) {
        if (!chunkVisibility.isChunkSent(observer, chunkX, chunkZ)) {
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
                        if (packedLight == ProjectionWorldView.LIGHT_UNAVAILABLE) {
                            return false;
                        }
                        writeLightNibble(skyArr, blockArr, (ly << 8) | (lz << 4) | lx,
                            ProjectionWorldView.unpackSkyLight(packedLight), ProjectionWorldView.unpackBlockLight(packedLight));
                    }
                }
            }

            skyArrays[arrIdx] = skyArr;
            blockArrays[arrIdx] = blockArr;
            blockMask.set(maskIndex);
            skyMask.set(maskIndex);
            arrIdx++;
        }

        LightData data = new LightData(true, blockMask, skyMask, emptyBlockMask, emptySkyMask,
            sectionCount, sectionCount, skyArrays, blockArrays);
        packetSender.send(observer, chunkX, chunkZ, data);
        WormholesTelemetry.countPacket();
        return true;
    }

    static int[] sortedValidSections(IntSet sections, int minSection, int maxSection) {
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

    private static long internalChunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (((long) chunkZ) & 0xFFFFFFFFL);
    }

    private static void sendPacket(Player observer, int chunkX, int chunkZ, LightData data) {
        WrapperPlayServerUpdateLight packet = new WrapperPlayServerUpdateLight(chunkX, chunkZ, data);
        PacketEvents.getAPI().getPlayerManager().sendPacket(observer, packet);
    }

    private static boolean isWorldYInsideWorld(ProjectionWorldView view, int y) {
        return y >= view.getMinHeight() && y < view.getMaxHeight();
    }

    private static final class SectionClaims {
        private final short[] slots = new short[4096];
        private int[] nibbleIndices = new int[16];
        private ProjectedBlockClaim[] claims = new ProjectedBlockClaim[16];
        private int size;

        private void put(int nibbleIndex, ProjectedBlockClaim claim) {
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

    @FunctionalInterface
    interface LightPacketSender {
        void send(Player observer, int chunkX, int chunkZ, LightData data);
    }
}
