package art.arcane.wormholes.render;

import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.light.LightOverlay;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.util.Vector2i;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.PaletteType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Set;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.entity.Player;

public final class ProjectionClientChunkTracker extends PacketListenerAbstract implements ProjectionChunkVisibility {
    private final ConcurrentHashMap<UUID, PlayerChunks> sentChunks;
    private final AtomicLong lifecycleClock;
    private final ConcurrentHashMap<UUID, ProjectionBlockUpdateRepair> blockRepairs;
    private final ConcurrentHashMap<UUID, PacketOrder> packetOrders = new ConcurrentHashMap<UUID, PacketOrder>();

    public ProjectionClientChunkTracker() {
        super(PacketListenerPriority.MONITOR);
        this.sentChunks = new ConcurrentHashMap<UUID, PlayerChunks>();
        this.lifecycleClock = new AtomicLong();
        this.blockRepairs = new ConcurrentHashMap<UUID, ProjectionBlockUpdateRepair>();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        UUID playerId = event.getUser().getUUID();
        if (playerId == null) {
            return;
        }
        Consumer<Boolean> completion = ProjectionPacketOrigin.consumeCompletion(event.getByteBuf());
        boolean own = completion != null;
        if (completion != null) {
            afterWrite(event, playerId, completion);
        }
        if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE
            || event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            trackBlockUpdates(event, playerId, own);
            return;
        }
        if (event.isCancelled() && !own) {
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            int readerIndex = ByteBufHelper.readerIndex(event.getByteBuf());
            int chunkX;
            int chunkZ;
            int[][] biomes = null;
            LightOverlay.ChunkLight light = null;
            ProjectionBlockUpdateRepair repair = blockRepairs.get(playerId);
            try {
                chunkX = ByteBufHelper.readInt(event.getByteBuf());
                chunkZ = ByteBufHelper.readInt(event.getByteBuf());
                if (repair != null && (repair.tracksBiomes() || repair.tracksLight())) {
                    ByteBufHelper.readerIndex(event.getByteBuf(), readerIndex);
                    WrapperPlayServerChunkData update = new WrapperPlayServerChunkData(event);
                    if (update.getLightData() != null) {
                        light = localLight(chunkX, chunkZ, update.getLightData());
                    }
                    BaseChunk[] sections = update.getColumn().getChunks();
                    biomes = new int[sections.length][];
                    for (int index = 0; index < sections.length; index++) {
                        if (!(sections[index] instanceof Chunk_v1_18 section)) {
                            biomes = null;
                            break;
                        }
                        biomes[index] = biomeCells(section.getBiomeData());
                    }
                }
            } finally {
                ByteBufHelper.readerIndex(event.getByteBuf(), readerIndex);
            }
            long key = chunkKey(chunkX, chunkZ);
            int[][] baseline = biomes;
            LightOverlay.ChunkLight localLight = light;
            afterWrite(event, playerId, success -> {
                if (success) {
                    markSent(playerId, key);
                    if (blockRepairs.get(playerId) == repair && repair != null) {
                        repair.overwriteChunk(key, ProjectionBlockUpdateRepair.ALL_FIDELITY | ProjectionBlockUpdateRepair.REPLACED,
                            baseline, localLight);
                    }
                }
            });
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
            int readerIndex = ByteBufHelper.readerIndex(event.getByteBuf());
            int chunkX;
            int chunkZ;
            try {
                if (event.getServerVersion().toClientVersion().isNewerThanOrEquals(ClientVersion.V_1_20_2)) {
                    long packed = ByteBufHelper.readLong(event.getByteBuf());
                    chunkX = (int) packed;
                    chunkZ = (int) (packed >> 32);
                } else {
                    chunkX = ByteBufHelper.readInt(event.getByteBuf());
                    chunkZ = ByteBufHelper.readInt(event.getByteBuf());
                }
            } finally {
                ByteBufHelper.readerIndex(event.getByteBuf(), readerIndex);
            }
            long key = chunkKey(chunkX, chunkZ);
            afterWrite(event, playerId, success -> {
                if (success) {
                    markUnsent(playerId, key);
                }
            });
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME
            || event.getPacketType() == PacketType.Play.Server.RESPAWN) {
            afterWrite(event, playerId, success -> {
                if (success) {
                    reset(playerId);
                }
            });
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.DISCONNECT) {
            afterWrite(event, playerId, success -> {
                if (success) {
                    forget(playerId);
                }
            });
            return;
        }
        trackFidelity(event, playerId, own, ProjectionPacketOrigin.managed(completion));
    }

    @Override
    public boolean tracksWriteCompletion() {
        return true;
    }

    @Override
    public boolean isChunkSent(Player observer, int chunkX, int chunkZ) {
        if (observer == null || !observer.isOnline()) {
            return false;
        }
        if (WormholesPlatform.supportsSentChunkQuery()) {
            return WormholesPlatform.isChunkSent(observer, chunkX, chunkZ);
        }
        PlayerChunks chunks = sentChunks.get(observer.getUniqueId());
        return chunks != null && chunks.sent.contains(chunkKey(chunkX, chunkZ));
    }

    @Override
    public long revision(Player observer) {
        if (observer == null) {
            return Long.MIN_VALUE;
        }
        return revision(observer.getUniqueId());
    }

    @Override
    public long chunkRevision(Player observer, int chunkX, int chunkZ) {
        if (observer == null) {
            return Long.MIN_VALUE;
        }
        return chunkRevision(observer.getUniqueId(), chunkX, chunkZ);
    }

    public void forget(UUID playerId) {
        if (playerId != null) {
            ProjectionPacketOrigin.forget(playerId);
            packetOrders.remove(playerId);
            sentChunks.remove(playerId);
            ProjectionBlockUpdateRepair repair = blockRepairs.remove(playerId);
            if (repair != null) {
                repair.close();
            }
        }
    }

    public void clear() {
        ProjectionPacketOrigin.clear();
        packetOrders.clear();
        sentChunks.clear();
        for (ProjectionBlockUpdateRepair repair : blockRepairs.values()) {
            repair.close();
        }
        blockRepairs.clear();
    }

    @Override
    public void watchBlockUpdates(UUID observerId, ProjectionBlockUpdateRepair repair) {
        blockRepairs.put(observerId, repair);
    }

    @Override
    public void unwatchBlockUpdates(UUID observerId, ProjectionBlockUpdateRepair repair) {
        blockRepairs.remove(observerId, repair);
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    void markSent(UUID playerId, long chunkKey) {
        PlayerChunks chunks = sentChunks.computeIfAbsent(playerId, ignored -> newPlayerChunks());
        chunks.sent.add(chunkKey);
        long revision = lifecycleClock.incrementAndGet();
        chunks.contentRevisions.put(Long.valueOf(chunkKey), Long.valueOf(revision));
        chunks.revision.set(revision);
    }

    void markUnsent(UUID playerId, long chunkKey) {
        PlayerChunks chunks = sentChunks.computeIfAbsent(playerId, ignored -> newPlayerChunks());
        chunks.sent.remove(chunkKey);
        chunks.contentRevisions.remove(Long.valueOf(chunkKey));
        chunks.revision.set(lifecycleClock.incrementAndGet());
    }

    boolean isTracked(UUID playerId, int chunkX, int chunkZ) {
        PlayerChunks chunks = sentChunks.get(playerId);
        return chunks != null && chunks.sent.contains(chunkKey(chunkX, chunkZ));
    }

    long revision(UUID playerId) {
        PlayerChunks chunks = sentChunks.get(playerId);
        return chunks == null ? Long.MIN_VALUE : chunks.revision.get();
    }

    long chunkRevision(UUID playerId, int chunkX, int chunkZ) {
        PlayerChunks chunks = sentChunks.get(playerId);
        if (chunks == null) {
            return Long.MIN_VALUE;
        }
        Long revision = chunks.contentRevisions.get(Long.valueOf(chunkKey(chunkX, chunkZ)));
        return revision == null ? Long.MIN_VALUE : revision.longValue();
    }

    private void reset(UUID playerId) {
        sentChunks.put(playerId, newPlayerChunks());
        ProjectionBlockUpdateRepair repair = blockRepairs.get(playerId);
        if (repair != null) {
            repair.reset();
        }
    }

    private void trackBlockUpdates(PacketSendEvent event, UUID playerId, boolean own) {
        ProjectionBlockUpdateRepair repair = blockRepairs.get(playerId);
        if (repair == null) {
            return;
        }
        LongOpenHashSet touched = new LongOpenHashSet();
        LongOpenHashSet attempted = new LongOpenHashSet();
        List<Consumer<Boolean>> receipts = new ArrayList<Consumer<Boolean>>();
        int readerIndex = ByteBufHelper.readerIndex(event.getByteBuf());
        try {
            if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
                WrapperPlayServerBlockChange update = new WrapperPlayServerBlockChange(event);
                Vector3i position = update.getBlockPosition();
                collectUpdate(repair, touched, attempted, receipts, position.getX(), position.getY(), position.getZ(), update.getBlockId());
            } else {
                WrapperPlayServerMultiBlockChange update = new WrapperPlayServerMultiBlockChange(event);
                for (WrapperPlayServerMultiBlockChange.EncodedBlock block : update.getBlocks()) {
                    collectUpdate(repair, touched, attempted, receipts, block.getX(), block.getY(), block.getZ(), block.getBlockId());
                }
            }
        } finally {
            ByteBufHelper.readerIndex(event.getByteBuf(), readerIndex);
        }
        if (attempted.isEmpty()) {
            return;
        }
        long[] keys = touched.toLongArray();
        long[] failed = attempted.toLongArray();
        afterWrite(event, playerId, success -> {
            for (Consumer<Boolean> receipt : receipts) {
                receipt.accept(success);
            }
            if (blockRepairs.get(playerId) == repair && (success || !event.isCancelled() || own)) {
                repair.overwritten(success ? keys : failed);
            }
        });
    }

    private void trackFidelity(PacketSendEvent event, UUID playerId, boolean own, boolean managed) {
        ProjectionBlockUpdateRepair repair = blockRepairs.get(playerId);
        if (repair == null) {
            return;
        }
        int readerIndex = ByteBufHelper.readerIndex(event.getByteBuf());
        try {
            if (event.getPacketType() == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
                Vector3i position = new WrapperPlayServerBlockEntityData(event).getPosition();
                long key = CellKeys.pack(position.getX(), position.getY(), position.getZ());
                afterWrite(event, playerId, success -> {
                    if ((!own && success || own && !success && !managed) && blockRepairs.get(playerId) == repair) {
                        repair.overwriteBlockEntity(key);
                    }
                });
            } else if (event.getPacketType() == PacketType.Play.Server.UPDATE_LIGHT) {
                WrapperPlayServerUpdateLight update = new WrapperPlayServerUpdateLight(event);
                long key = chunkKey(update.getX(), update.getZ());
                LightOverlay.ChunkLight baseline = localLight(update.getX(), update.getZ(), update.getLightData());
                afterWrite(event, playerId, success -> {
                    if ((!own && success || own && !success && !managed) && blockRepairs.get(playerId) == repair) {
                        repair.overwriteChunk(key, ProjectionBlockUpdateRepair.LIGHT, null, success ? baseline : null);
                    }
                });
            } else if (event.getPacketType() == PacketType.Play.Server.CHUNK_BIOMES) {
                PacketWrapper<?> packet = PacketWrapper.createUniversalPacketWrapper(event.getByteBuf(), event.getServerVersion());
                int count = packet.readVarInt();
                long[] keys = new long[count];
                int[][][] columns = new int[count][][];
                for (int index = 0; index < count; index++) {
                    Vector2i position = Vector2i.read(packet);
                    keys[index] = chunkKey(position.getX(), position.getZ());
                    int length = ByteBufHelper.readVarInt(event.getByteBuf());
                    int end = ByteBufHelper.readerIndex(event.getByteBuf()) + length;
                    List<int[]> sections = new ArrayList<int[]>();
                    while (ByteBufHelper.readerIndex(event.getByteBuf()) < end) {
                        sections.add(biomeCells(PaletteType.BIOME.read(packet)));
                    }
                    if (ByteBufHelper.readerIndex(event.getByteBuf()) != end) {
                        throw new IllegalArgumentException("Biome packet column length does not match its palettes");
                    }
                    columns[index] = sections.toArray(int[][]::new);
                }
                afterWrite(event, playerId, success -> {
                    if (blockRepairs.get(playerId) != repair || own == success || own && managed) {
                        return;
                    }
                    for (int index = 0; index < keys.length; index++) {
                        repair.overwriteChunk(keys[index], ProjectionBlockUpdateRepair.BIOMES
                            | (success ? ProjectionBlockUpdateRepair.BASELINE_CHANGED : 0), success ? columns[index] : null, null);
                    }
                });
            }
        } finally {
            ByteBufHelper.readerIndex(event.getByteBuf(), readerIndex);
        }
    }

    private static LightOverlay.ChunkLight localLight(int x, int z, LightData light) {
        return new LightOverlay.ChunkLight(x, z, (BitSet) light.getBlockLightMask().clone(),
            (BitSet) light.getSkyLightMask().clone(), (BitSet) light.getEmptyBlockLightMask().clone(),
            (BitSet) light.getEmptySkyLightMask().clone(), copyArrays(light.getSkyLightArray()), copyArrays(light.getBlockLightArray()));
    }

    private static byte[][] copyArrays(byte[][] arrays) {
        byte[][] copy = new byte[arrays.length][];
        for (int index = 0; index < arrays.length; index++) {
            copy[index] = arrays[index].clone();
        }
        return copy;
    }

    private static int[] biomeCells(DataPalette palette) {
        int[] cells = new int[64];
        for (int index = 0; index < cells.length; index++) {
            cells[index] = palette.get(index & 3, (index >> 4) & 3, (index >> 2) & 3);
        }
        return cells;
    }

    private void afterWrite(PacketSendEvent event, UUID playerId, Consumer<Boolean> action) {
        Object channel = event.getChannel();
        PacketOrder order = packetOrders.compute(playerId, (ignored, previous) ->
            previous == null || previous.channel != channel ? new PacketOrder(channel) : previous);
        OrderedWrite write = new OrderedWrite(action);
        synchronized (order) {
            order.pending.add(write);
        }
        event.getTasksOnWriteCompletion().add((success, transportFailure) -> {
            synchronized (order) {
                if (packetOrders.get(playerId) != order) {
                    return;
                }
                write.success = success && !event.isCancelled();
                write.complete = true;
                RuntimeException callbackFailure = null;
                while (!order.pending.isEmpty() && order.pending.peek().complete) {
                    OrderedWrite completed = order.pending.remove();
                    try {
                        completed.action.accept(completed.success);
                    } catch (RuntimeException failure) {
                        if (callbackFailure == null) {
                            callbackFailure = failure;
                        } else {
                            callbackFailure.addSuppressed(failure);
                        }
                    }
                    if (packetOrders.get(playerId) != order) {
                        order.pending.clear();
                        break;
                    }
                }
                if (callbackFailure != null) {
                    throw callbackFailure;
                }
            }
        });
    }

    private static void collectUpdate(ProjectionBlockUpdateRepair repair, LongOpenHashSet touched,
                                      LongOpenHashSet attempted, List<Consumer<Boolean>> receipts, int x, int y, int z, int globalId) {
        long key = CellKeys.pack(x, y, z);
        if (repair.claimed(key) || repair.hasReceipt(key)) {
            attempted.add(key);
        }
        boolean own = repair.consumeBukkitBlockChange(key);
        if (own) {
            Consumer<Boolean> receipt = repair.takeReceipt(key);
            if (receipt != null) {
                receipts.add(receipt);
            }
        }
        if (!own && repair.touchesClaim(key, globalId)) {
            touched.add(key);
        }
    }

    private static final class PacketOrder {
        private final ArrayDeque<OrderedWrite> pending = new ArrayDeque<OrderedWrite>();
        private final Object channel;

        private PacketOrder(Object channel) {
            this.channel = channel;
        }
    }

    private static final class OrderedWrite {
        private final Consumer<Boolean> action;
        private boolean complete;
        private boolean success;

        private OrderedWrite(Consumer<Boolean> action) {
            this.action = action;
        }
    }

    private PlayerChunks newPlayerChunks() {
        return new PlayerChunks(lifecycleClock.incrementAndGet());
    }

    private static final class PlayerChunks {
        private final Set<Long> sent;
        private final ConcurrentHashMap<Long, Long> contentRevisions;
        private final AtomicLong revision;

        private PlayerChunks(long revision) {
            this.sent = ConcurrentHashMap.newKeySet();
            this.contentRevisions = new ConcurrentHashMap<Long, Long>();
            this.revision = new AtomicLong(revision);
        }
    }
}
