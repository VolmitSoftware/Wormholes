package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.PlateHandoff;
import art.arcane.optics.plate.ViewPlate;
import net.minecraft.world.level.block.state.BlockState;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalPlateHandles {
    private static final ConcurrentHashMap<Long, PlateHandoff<BlockState>> HANDLES = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_HANDLE = new AtomicLong(1L);
    private static final long NONCE = generateNonce();

    private LocalPlateHandles() {
    }

    public static long nonce() {
        return NONCE;
    }

    public static PlateHandoff<BlockState> publish(int portalKey, int plateRevision, ViewPlate<BlockState> plate, BlockState backingState,
                                                   BrickLightSource light) {
        long handle = NEXT_HANDLE.getAndIncrement();
        PlateHandoff<BlockState> handoff = new PlateHandoff<>(handle, portalKey, plateRevision, plate, backingState, light, System.nanoTime());
        HANDLES.put(handle, handoff);
        return handoff;
    }

    public static PlateHandoff<BlockState> take(long handle) {
        return HANDLES.remove(handle);
    }

    public static int purgeExpired(long nowNanos) {
        int purged = 0;
        Iterator<Map.Entry<Long, PlateHandoff<BlockState>>> iterator = HANDLES.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expired(nowNanos)) {
                iterator.remove();
                purged++;
            }
        }
        return purged;
    }

    public static void clear() {
        HANDLES.clear();
    }

    public static int size() {
        return HANDLES.size();
    }

    private static long generateNonce() {
        long value = new SecureRandom().nextLong();
        return value == 0L ? 1L : value;
    }
}
