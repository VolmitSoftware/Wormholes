package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.BrickCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.PlateHandoff;
import art.arcane.wormholes.render.client.ClientViewSweep;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class ClientPlateStore {
    private final ClientPalette palette;
    private final long budgetBytes;
    private final Int2ObjectOpenHashMap<ClientPlate> plates;
    private final Int2ObjectOpenHashMap<PendingPlate> pending;
    private final Long2ObjectLinkedOpenHashMap<Brick> brickCache;
    private LongSupplier otherMemory = () -> 0L;
    private long plateBytes;
    private long pendingBytes;
    private long cacheBytes;
    private int refusedPlates;
    private int cacheHits;
    private int cacheMisses;
    private int staleMessages;
    private ClientViewMessage.PlateRefused refusal;

    public ClientPlateStore(ClientPalette palette, long budgetBytes) {
        this.palette = Objects.requireNonNull(palette, "palette");
        this.budgetBytes = Math.max(0L, budgetBytes);
        this.plates = new Int2ObjectOpenHashMap<>();
        this.pending = new Int2ObjectOpenHashMap<>();
        this.brickCache = new Long2ObjectLinkedOpenHashMap<>(1024);
    }

    public void otherMemory(LongSupplier usage) {
        otherMemory = Objects.requireNonNull(usage);
    }

    public ClientViewMessage.BrickMiss.Plate begin(ClientViewMessage.PlateBegin begin) throws ClientViewProtocolException {
        if (begin.brickCount() != begin.sections().brickCount()) {
            throw new ClientViewProtocolException("plate begin of " + begin.brickCount() + " bricks for " + begin.sections().brickCount() + " sections");
        }
        discard(begin.portalKey());
        PendingPlate plate = new PendingPlate(begin);
        if (begin.cells().cells() > ClientViewSweep.MAX_BOUNDS_CELLS
            || !reserve(plate, ClientPlate.sweepBytes(begin.cells()) + (long) begin.brickCount() * ClientPlate.BRICK_OVERHEAD_BYTES)) {
            refuse(begin.portalKey(), begin.plateRevision());
            return null;
        }
        pending.put(begin.portalKey(), plate);
        if (!begin.hasHashes()) {
            return null;
        }
        long[] hashes = begin.brickHashes();
        boolean[] missed = new boolean[hashes.length];
        for (int index = 0; index < hashes.length; index++) {
            Brick cached = brickCache.getAndMoveToLast(hashes[index]);
            if (cached == null) {
                missed[index] = true;
                cacheMisses++;
                continue;
            }
            plate.bricks[index] = cached.withIndex(index);
            plate.received[index] = true;
            cacheHits++;
        }
        return new ClientViewMessage.BrickMiss.Plate(begin.portalKey(), begin.plateRevision(),
            ClientViewMessage.BrickMiss.Plate.bitsetFor(hashes.length, missed));
    }

    public int bricks(ClientViewMessage.PlateBricks message) throws ClientViewProtocolException {
        PendingPlate plate = pending.get(message.portalKey());
        if (plate == null || plate.revision != message.plateRevision()) {
            staleMessages++;
            return 0;
        }
        List<Brick> bricks = message.bricks();
        for (int index = 0; index < bricks.size(); index++) {
            Brick brick = bricks.get(index);
            int brickIndex = brick.brickIndex();
            if (brickIndex < 0 || brickIndex >= plate.bricks.length) {
                throw new ClientViewProtocolException("brick " + brickIndex + " outside a plate of " + plate.bricks.length);
            }
            Brick stored = brick.isEmpty() ? null : brick;
            long size = stored == null ? 0L : ClientPlate.brickBytes(stored) - ClientPlate.BRICK_OVERHEAD_BYTES;
            if (!reserve(plate, size)) {
                discard(plate.portalKey);
                refuse(plate.portalKey, plate.revision);
                return index;
            }
            plate.bricks[brickIndex] = stored;
            plate.received[brickIndex] = true;
        }
        return bricks.size();
    }

    public ClientPlate end(ClientViewMessage.PlateEnd message) {
        PendingPlate plate = pending.get(message.portalKey());
        if (plate == null || plate.revision != message.plateRevision()) {
            staleMessages++;
            return null;
        }
        discard(message.portalKey());
        ClientPlate built = ClientPlate.fromBricks(plate.portalKey, plate.revision, plate.begin.sections(), plate.begin.cells(),
            plate.begin.backingState(), plate.bricks);
        if (!commit(built)) {
            return null;
        }
        if (plate.begin.hasHashes()) {
            remember(plate.begin.brickHashes(), plate.bricks);
        }
        return built;
    }

    public ClientPlate patch(ClientViewMessage.PlatePatch message, IntArrayList touchedBricks) throws ClientViewProtocolException {
        ClientPlate current = plates.get(message.portalKey());
        if (current == null || current.zeroCopy() || current.revision() != message.fromRevision()) {
            staleMessages++;
            return null;
        }
        Brick[] bricks = current.bricksCopy();
        List<ClientViewMessage.PatchOp> ops = message.ops();
        for (int index = 0; index < ops.size(); index++) {
            ClientViewMessage.PatchOp op = ops.get(index);
            int brickIndex = op.brickIndex();
            if (brickIndex < 0 || brickIndex >= bricks.length) {
                throw new ClientViewProtocolException("patch brick " + brickIndex + " outside a plate of " + bricks.length);
            }
            switch (op) {
                case ClientViewMessage.FullOp full -> bricks[brickIndex] = full.brick().isEmpty() ? null : full.brick();
                case ClientViewMessage.SparseOp sparse -> bricks[brickIndex] = sparse(bricks[brickIndex], brickIndex, sparse);
                case ClientViewMessage.ClearOp clear -> bricks[brickIndex] = null;
            }
            touchedBricks.add(brickIndex);
        }
        ClientPlate built = ClientPlate.fromBricks(current.portalKey(), message.toRevision(), current.sections(), current.cells(),
            current.backingState(), bricks);
        return commit(built) ? built : null;
    }

    public ClientPlate handle(ClientViewMessage.PlateHandle message, PlateHandoff<BlockState> handoff) {
        if (handoff == null || handoff.portalKey() != message.portalKey() || handoff.plateRevision() != message.plateRevision()) {
            staleMessages++;
            return null;
        }
        discard(message.portalKey());
        if (handoff.plate().box().cells() > ClientViewSweep.MAX_BOUNDS_CELLS) {
            refuse(message.portalKey(), message.plateRevision());
            return null;
        }
        ClientPlate built;
        try {
            built = ClientPlate.fromHandoff(handoff, palette);
        } catch (IllegalArgumentException unrepresentable) {
            refuse(message.portalKey(), message.plateRevision());
            return null;
        }
        return commit(built) ? built : null;
    }

    public ClientPlate plate(int portalKey) {
        return plates.get(portalKey);
    }

    public boolean pending(int portalKey) {
        return pending.containsKey(portalKey);
    }

    public ClientPlate drop(int portalKey) {
        discard(portalKey);
        ClientPlate removed = plates.remove(portalKey);
        if (removed != null) {
            plateBytes -= removed.bytes();
        }
        return removed;
    }

    public void clear() {
        plates.clear();
        pending.clear();
        brickCache.clear();
        plateBytes = 0L;
        pendingBytes = 0L;
        cacheBytes = 0L;
        refusal = null;
    }

    public ClientViewMessage.PlateRefused takeRefusal() {
        ClientViewMessage.PlateRefused taken = refusal;
        refusal = null;
        return taken;
    }

    public int size() {
        return plates.size();
    }

    public long plateBytes() {
        return plateBytes;
    }

    public long pendingBytes() {
        return pendingBytes;
    }

    public long cacheBytes() {
        return cacheBytes;
    }

    public long bytes() {
        return plateBytes + pendingBytes + cacheBytes;
    }

    public long budgetBytes() {
        return budgetBytes;
    }

    public int plateMb() {
        return (int) Math.min(65535L, (bytes() + (1024L * 1024L) - 1L) / (1024L * 1024L));
    }

    public int refusedPlates() {
        return refusedPlates;
    }

    public int cacheHits() {
        return cacheHits;
    }

    public int cacheMisses() {
        return cacheMisses;
    }

    public int staleMessages() {
        return staleMessages;
    }

    public int cachedBricks() {
        return brickCache.size();
    }

    private boolean reserve(PendingPlate plate, long size) {
        ClientPlate resident = plates.get(plate.portalKey);
        long projected = plateBytes - (resident == null ? 0L : resident.bytes()) + pendingBytes + size;
        trimCache(projected);
        if (projected + cacheBytes > budgetBytes - otherMemory.getAsLong()) {
            return false;
        }
        plate.bytes += size;
        pendingBytes += size;
        return true;
    }

    private void discard(int portalKey) {
        PendingPlate removed = pending.remove(portalKey);
        if (removed != null) {
            pendingBytes -= removed.bytes;
        }
    }

    private void refuse(int portalKey, int revision) {
        refusedPlates++;
        refusal = new ClientViewMessage.PlateRefused(portalKey, revision);
    }

    private boolean commit(ClientPlate built) {
        ClientPlate previous = plates.get(built.portalKey());
        long previousBytes = previous == null ? 0L : previous.bytes();
        long projected = plateBytes - previousBytes + built.bytes();
        trimCache(projected + pendingBytes);
        if (projected + pendingBytes + cacheBytes > budgetBytes - otherMemory.getAsLong()) {
            refuse(built.portalKey(), built.revision());
            return false;
        }
        plates.put(built.portalKey(), built);
        plateBytes = projected;
        return true;
    }

    private void remember(long[] hashes, Brick[] bricks) {
        for (int index = 0; index < hashes.length; index++) {
            Brick brick = bricks[index];
            if (brick == null || brickCache.containsKey(hashes[index])) {
                if (brick != null) {
                    brickCache.getAndMoveToLast(hashes[index]);
                }
                continue;
            }
            long size = ClientPlate.brickBytes(brick);
            long reserved = plateBytes + pendingBytes + size;
            if (reserved + cacheBytes > budgetBytes - otherMemory.getAsLong()) {
                trimCache(reserved);
                if (reserved + cacheBytes > budgetBytes - otherMemory.getAsLong()) {
                    continue;
                }
            }
            brickCache.putAndMoveToLast(hashes[index], brick.stripped().withLight(brick.blockLight(), brick.skyLight())
                .withBlockEntities(brick.blockEntities()));
            cacheBytes += size;
        }
    }

    private void trimCache(long reservedBytes) {
        while (!brickCache.isEmpty() && reservedBytes + cacheBytes > budgetBytes - otherMemory.getAsLong()) {
            Brick evicted = brickCache.removeFirst();
            cacheBytes -= ClientPlate.brickBytes(evicted);
        }
        if (brickCache.isEmpty()) {
            cacheBytes = 0L;
        }
    }

    private static Brick sparse(Brick current, int brickIndex, ClientViewMessage.SparseOp op) throws ClientViewProtocolException {
        int[] cells = current == null ? new int[ClientViewProtocol.BRICK_CELLS] : BrickCodec.unpack(current);
        int[] cellIndices = op.cellIndices();
        int[] paletteIds = op.paletteIds();
        for (int index = 0; index < cellIndices.length; index++) {
            int cellIndex = cellIndices[index];
            if (cellIndex < 0 || cellIndex >= ClientViewProtocol.BRICK_CELLS) {
                throw new ClientViewProtocolException("sparse patch cell " + cellIndex + " outside the brick");
            }
            cells[cellIndex] = paletteIds[index];
        }
        Brick packed = BrickCodec.pack(brickIndex, cells);
        if (packed.isEmpty()) {
            return null;
        }
        if (current == null) {
            return packed;
        }
        return packed.withLight(current.blockLight(), current.skyLight()).withBlockEntities(current.blockEntities());
    }

    private static final class PendingPlate {
        private final ClientViewMessage.PlateBegin begin;
        private final int portalKey;
        private final int revision;
        private final Brick[] bricks;
        private final boolean[] received;
        private long bytes;

        private PendingPlate(ClientViewMessage.PlateBegin begin) {
            this.begin = begin;
            this.portalKey = begin.portalKey();
            this.revision = begin.plateRevision();
            this.bricks = new Brick[begin.brickCount()];
            this.received = new boolean[begin.brickCount()];
        }
    }
}
