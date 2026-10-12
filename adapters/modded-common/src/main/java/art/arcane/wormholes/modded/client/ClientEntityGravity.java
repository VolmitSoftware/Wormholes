package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;

public final class ClientEntityGravity {
    private static final int MAX_PENDING = 256;
    private static final long WAIT_MILLIS = 10_000L;

    private final Map<Key, Pending> pending = new LinkedHashMap<>();

    public void receive(Level level, Entity player, boolean predicting, TravelMessage.EntityGravity message, long now) {
        if (apply(level, player, predicting, message)) {
            pending.remove(new Key(message.levelHandle(), message.entityUuid()));
            return;
        }
        Key key = new Key(message.levelHandle(), message.entityUuid());
        if (pending.size() >= MAX_PENDING && !pending.containsKey(key)) {
            pending.remove(pending.keySet().iterator().next());
        }
        pending.put(key, new Pending(message, now + WAIT_MILLIS));
    }

    public void tick(IntFunction<? extends Level> worlds, Entity player, boolean predicting, long now) {
        Iterator<Pending> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending next = iterator.next();
            if (now >= next.expiresAt() || apply(worlds.apply(next.message().levelHandle()), player, predicting, next.message())) {
                iterator.remove();
            }
        }
    }

    public void clear() {
        pending.clear();
    }

    public void discard(UUID entity) {
        pending.keySet().removeIf(key -> key.entity().equals(entity));
    }

    private static boolean apply(Level level, Entity player, boolean predicting, TravelMessage.EntityGravity message) {
        Entity entity = level == null ? null : level.getEntity(message.entityId());
        if (entity == null) {
            return false;
        }
        if (!entity.getUUID().equals(message.entityUuid())) {
            return true;
        }
        if (entity == player && predicting) {
            return false;
        }
        if (entity instanceof CrossingEntity holder && holder.wormholes$crossing() != null && !holder.wormholes$crossing().crossed()) {
            return false;
        }
        if (message.restoreWorldOrientation()) {
            MinecraftGravity.reset(entity);
        }
        MinecraftGravity.set(entity, message.frame());
        return true;
    }

    private record Key(int level, UUID entity) {
    }

    private record Pending(TravelMessage.EntityGravity message, long expiresAt) {
    }
}
