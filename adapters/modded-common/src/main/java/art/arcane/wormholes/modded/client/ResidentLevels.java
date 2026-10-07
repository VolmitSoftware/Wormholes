package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.modded.seamless.RoutedPackets;
import art.arcane.wormholes.network.client.TravelMessage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkBatchSizeCalculator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ResidentLevels {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final Consumer<TravelMessage> sender;
    private final long budgetBytes;
    private final RoutedPacketDecoder decoder = new RoutedPacketDecoder();
    private final ChunkBatchSizeCalculator chunkRate = new ChunkBatchSizeCalculator();
    private final Int2ObjectOpenHashMap<ResidentLevel> handles = new Int2ObjectOpenHashMap<>();
    private final List<ResidentLevel> levels = new ArrayList<>();
    private final IntLinkedOpenHashSet unacknowledged = new IntLinkedOpenHashSet();
    private final IntOpenHashSet reopening = new IntOpenHashSet();
    private final ClientParticleLevels particles = new ClientParticleLevels();
    private ClientLevel crossingSource;
    private Routing routing;
    private long clock;

    public ResidentLevels(Consumer<TravelMessage> sender, long budgetBytes) {
        this.sender = sender;
        this.budgetBytes = budgetBytes;
    }

    public ClientLevel open(TravelMessage.RemoteLevelOpen open) {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        ResidentLevel bound = handles.get(open.levelHandle());
        if (bound != null && bound.world().equals(open.world()) && bound.level() != activeLevel()) {
            bound.bind(open, ++clock);
            return bound.level();
        }
        if (bound != null) {
            unbind(bound);
        }
        ResidentLevel resident = reusable(open);
        if (resident == null) {
            resident = new ResidentLevel(ResidentLevel.create(open.world(), open.environment(), open.center()), open.world());
            levels.add(resident);
        }
        resident.bind(open, ++clock);
        handles.put(open.levelHandle(), resident);
        reopening.remove(open.levelHandle());
        enforceBudget();
        return resident.level();
    }

    public void close(TravelMessage.RemoteLevelClose close) {
        ResidentLevel resident = handles.get(close.levelHandle());
        if (resident == null) {
            return;
        }
        unbind(resident);
        if (resident.level() == activeLevel()) {
            levels.remove(resident);
            return;
        }
        ResidentLevel.discardEntities(resident.level());
        enforceBudget();
    }

    public void route(TravelMessage.RoutedPacket fragment) {
        ResidentLevel resident = handles.get(fragment.levelHandle());
        if (resident == null) {
            if (reopening.add(fragment.levelHandle())) {
                LOGGER.info("Resident level {} is not open here; asking the server to reopen it", fragment.levelHandle());
                sender.accept(new TravelMessage.RemoteLevelReopen(fragment.levelHandle()));
            }
            return;
        }
        List<byte[]> payloads;
        try {
            payloads = decoder.accept(fragment);
        } catch (IllegalArgumentException failure) {
            LOGGER.warn("Discarding routed packets for resident level {}", fragment.levelHandle(), failure);
            decoder.forget(fragment.levelHandle());
            return;
        }
        if (payloads.isEmpty()) {
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        for (byte[] payload : payloads) {
            Packet<? super ClientGamePacketListener> packet;
            try {
                packet = RoutedPackets.decode(RoutedPackets.inbound(connection.getConnection(), connection.registryAccess()), payload);
            } catch (RuntimeException failure) {
                LOGGER.warn("Unable to decode a routed packet for resident level {}", fragment.levelHandle(), failure);
                continue;
            }
            apply(resident, connection, packet);
        }
        unacknowledged.add(fragment.levelHandle());
    }

    public void withLevel(ClientLevel level, Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null || level == null) {
            action.run();
            return;
        }
        PreparedPacketAccess access = (PreparedPacketAccess) connection;
        ClientLevel previousLevel = minecraft.level;
        ClientLevel previousConnectionLevel = connection.getLevel();
        ClientLevel.ClientLevelData previousData = access.wormholes$data();
        Routing outer = routing;
        routing = new Routing(outer == null ? previousLevel : outer.active());
        minecraft.level = level;
        access.wormholes$level(level);
        access.wormholes$data(level.getLevelData());
        try {
            action.run();
        } finally {
            routing = outer;
            if (minecraft.level == level && minecraft.getConnection() == connection) {
                minecraft.level = previousLevel;
                access.wormholes$level(previousConnectionLevel);
                access.wormholes$data(previousData);
            }
        }
    }

    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null || routing != null) {
            return;
        }
        ClientLevel active = minecraft.level;
        for (int index = 0; index < levels.size(); index++) {
            ResidentLevel resident = levels.get(index);
            ClientLevel level = resident.level();
            if (!resident.bound() || level == active || level == crossingSource) {
                continue;
            }
            withLevel(level, () -> tickLevel(level));
        }
        acknowledge();
    }

    public boolean has(int handle) {
        return handles.containsKey(handle);
    }

    public ClientLevel level(int handle) {
        ResidentLevel resident = handles.get(handle);
        return resident == null ? null : resident.level();
    }

    public int handle(ClientLevel level) {
        ResidentLevel resident = find(level);
        return resident == null ? 0 : resident.handle();
    }

    public boolean resident(ClientLevel level) {
        return find(level) != null;
    }

    public ClientLevel activeLevel() {
        return routing == null ? Minecraft.getInstance().level : routing.active();
    }

    public boolean muted(Object level) {
        if (!(level instanceof ClientLevel clientLevel) || clientLevel == activeLevel()) {
            return false;
        }
        return clientLevel == crossingSource || find(clientLevel) != null;
    }

    public boolean routing() {
        return routing != null;
    }

    public ClientLevel redirectTarget() {
        if (routing != null || crossingSource == null || !Minecraft.getInstance().isSameThread()) {
            return null;
        }
        return crossingSource;
    }

    public void crossing(ClientLevel source) {
        crossingSource = source;
    }

    public ClientLevel crossingSource() {
        return crossingSource;
    }

    public long bytes() {
        ClientLevel active = activeLevel();
        long total = 0L;
        for (ResidentLevel resident : levels) {
            if (resident.level() != active) {
                total += ResidentLevel.estimate(resident.level());
            }
        }
        return total;
    }

    public void clear() {
        clear(null);
    }

    public void clear(ClientLevel keep) {
        ClientLevel active = Minecraft.getInstance().level;
        for (ResidentLevel resident : levels) {
            if (resident.level() != keep && resident.level() != active) {
                forget(resident.level());
            }
        }
        levels.clear();
        handles.clear();
        unacknowledged.clear();
        decoder.clear();
        reopening.clear();
        particles.clear();
        crossingSource = null;
    }

    ClientParticleLevels particles() {
        return particles;
    }

    public void retire(ClientLevel level) {
        ResidentLevel existing = find(level);
        if (existing != null) {
            existing.touch(++clock);
            enforceBudget();
            return;
        }
        TravelMessage.TravelWorld world = ((ClientTravelWorld) level).wormholes$travelWorld();
        if (world == null) {
            return;
        }
        ResidentLevel resident = new ResidentLevel(level, world);
        resident.touch(++clock);
        levels.add(resident);
        enforceBudget();
    }

    private void apply(ResidentLevel resident, ClientPacketListener connection, Packet<? super ClientGamePacketListener> packet) {
        boolean chunk = packet instanceof ClientboundLevelChunkWithLightPacket;
        if (chunk) {
            chunkRate.onBatchStart();
        }
        try {
            withLevel(resident.level(), () -> packet.handle(connection));
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to apply routed {} to resident level {}", packet.type(), resident.handle(), failure);
            return;
        }
        if (chunk) {
            chunkRate.onBatchFinished(1);
        }
        if (packet instanceof ClientboundAddEntityPacket added) {
            removeDuplicates(resident.level(), added.getId());
        }
    }

    private void removeDuplicates(ClientLevel owner, int id) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel active = activeLevel();
        if (active != null && active != owner && !active.dimension().equals(owner.dimension())) {
            Entity existing = active.getEntity(id);
            if (existing != null && existing != minecraft.player) {
                active.removeEntity(id, Entity.RemovalReason.DISCARDED);
            }
        }
        for (ResidentLevel other : levels) {
            ClientLevel level = other.level();
            if (level != owner && level != active && !level.dimension().equals(owner.dimension()) && level.getEntity(id) != null) {
                level.removeEntity(id, Entity.RemovalReason.DISCARDED);
            }
        }
    }

    private void tickLevel(ClientLevel level) {
        try {
            level.tickEntities();
            level.tick(() -> true);
            level.update();
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to tick resident level {}", level.dimension().identifier(), failure);
        }
    }

    private void acknowledge() {
        if (unacknowledged.isEmpty()) {
            return;
        }
        int hint = Math.max(1, Math.min(TravelMessage.MAX_CHUNKS_PER_TICK_HINT, Math.round(chunkRate.getDesiredChunksPerTick())));
        for (int handle : unacknowledged) {
            int sequence = decoder.lastSequence(handle);
            if (sequence >= 0 && handles.containsKey(handle)) {
                sender.accept(new TravelMessage.RemoteViewAck(handle, sequence, hint));
            }
        }
        unacknowledged.clear();
    }

    private ResidentLevel reusable(TravelMessage.RemoteLevelOpen open) {
        ClientLevel active = activeLevel();
        ResidentLevel best = null;
        for (ResidentLevel resident : levels) {
            if (resident.bound() || resident.level() == active || resident.level() == crossingSource || !resident.overlaps(open)) {
                continue;
            }
            if (best == null || resident.used() > best.used()) {
                best = resident;
            }
        }
        return best;
    }

    private ResidentLevel find(ClientLevel level) {
        for (ResidentLevel resident : levels) {
            if (resident.level() == level) {
                return resident;
            }
        }
        return null;
    }

    private void unbind(ResidentLevel resident) {
        handles.remove(resident.handle());
        decoder.forget(resident.handle());
        unacknowledged.remove(resident.handle());
        reopening.remove(resident.handle());
        resident.unbind(++clock);
    }

    private void enforceBudget() {
        long total = bytes();
        ClientLevel active = activeLevel();
        while (total > budgetBytes) {
            ResidentLevel oldest = null;
            for (ResidentLevel resident : levels) {
                if (resident.bound() || resident.level() == active || resident.level() == crossingSource) {
                    continue;
                }
                if (oldest == null || resident.used() < oldest.used()) {
                    oldest = resident;
                }
            }
            if (oldest == null) {
                return;
            }
            total -= ResidentLevel.estimate(oldest.level());
            levels.remove(oldest);
            forget(oldest.level());
        }
    }

    private void forget(ClientLevel level) {
        particles.forget(level);
        ClientSodiumTerrain.forget(level);
        ResidentLevel.discardEntities(level);
    }

    private record Routing(ClientLevel active) {
    }
}
