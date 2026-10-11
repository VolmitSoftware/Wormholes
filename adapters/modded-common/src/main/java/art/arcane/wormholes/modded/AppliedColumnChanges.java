package art.arcane.wormholes.modded;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.ObjLongConsumer;

public final class AppliedColumnChanges<W> {
    private final Map<W, Long2ObjectOpenHashMap<LongSet>> blocks = new IdentityHashMap<>();
    private final Map<W, LongSet> columns = new IdentityHashMap<>();

    public void block(W world, long column, long position) {
        blocks.computeIfAbsent(world, ignored -> new Long2ObjectOpenHashMap<>())
            .computeIfAbsent(column, ignored -> new LongOpenHashSet()).add(position);
    }

    public void column(W world, long column) {
        columns.computeIfAbsent(world, ignored -> new LongOpenHashSet()).add(column);
    }

    public void notified(W world, long column, long position) {
        Long2ObjectOpenHashMap<LongSet> pending = blocks.get(world);
        if (pending == null) {
            return;
        }
        LongSet positions = pending.get(column);
        if (positions != null && positions.remove(position) && positions.isEmpty()) {
            pending.remove(column);
            if (pending.isEmpty()) {
                blocks.remove(world);
            }
        }
    }

    public void drain(ObjLongConsumer<W> consumer) {
        for (Map.Entry<W, Long2ObjectOpenHashMap<LongSet>> world : blocks.entrySet()) {
            LongSet dirty = columns.computeIfAbsent(world.getKey(), ignored -> new LongOpenHashSet());
            for (Long2ObjectMap.Entry<LongSet> column : world.getValue().long2ObjectEntrySet()) {
                dirty.add(column.getLongKey());
            }
        }
        blocks.clear();
        for (Map.Entry<W, LongSet> world : columns.entrySet()) {
            for (long column : world.getValue()) {
                consumer.accept(world.getKey(), column);
            }
        }
        columns.clear();
    }

    public void clear() {
        blocks.clear();
        columns.clear();
    }
}
