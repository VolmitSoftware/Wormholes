package art.arcane.wormholes.modded;

import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.door.DoorArrivals;
import art.arcane.wormholes.door.PocketEscapePolicy;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketRescuePolicy;
import art.arcane.wormholes.door.PocketRoom;
import art.arcane.wormholes.door.PocketRooms;
import art.arcane.wormholes.door.PocketSpace;
import art.arcane.wormholes.door.ReturnTicket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.LevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MinecraftPocketRules implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);

    private final WormholesModRuntime runtime;
    private final MinecraftDoorService doors;
    private final MinecraftServer server;
    private final Map<UUID, Rescue> rescues = new HashMap<>();
    private final Map<UUID, Boolean> retainedDeaths = new HashMap<>();
    private final Map<UUID, PocketSpace> previousSpaces = new HashMap<>();
    private final Map<UUID, ClockOverride> clocks = new ConcurrentHashMap<>();
    private boolean closed;

    MinecraftPocketRules(WormholesModRuntime runtime, MinecraftDoorService doors) {
        this.runtime = runtime;
        this.doors = doors;
        server = runtime.server();
    }

    public void tick() {
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                observe(player);
            }
        }
    }

    public boolean rescuing(UUID entityId) {
        return rescues.containsKey(entityId);
    }

    public boolean denySpawn(ServerLevel level, Entity entity) {
        if (!(entity instanceof Mob)) {
            return false;
        }
        PocketSpace space = doors.spaceAt(level, entity.blockPosition());
        return space != null && !space.rules().allowsSpawn();
    }

    public boolean denyPvp(ServerPlayer victim, DamageSource source) {
        Entity attacker = source.getEntity();
        if (!(attacker instanceof ServerPlayer player) || player == victim) {
            return false;
        }
        PocketSpace space = doors.spaceAt(victim.level(), victim.blockPosition());
        return space != null && !space.rules().allowsPvp();
    }

    public void recordDeath(ServerPlayer player) {
        PocketSpace space = doors.spaceAt(player.level(), player.blockPosition());
        retainedDeaths.put(player.getUUID(), space != null && space.rules().keepInventory());
    }

    public boolean keepInventory(ServerPlayer player) {
        Boolean retained = retainedDeaths.get(player.getUUID());
        if (retained != null) {
            return retained;
        }
        PocketSpace space = doors.spaceAt(player.level(), player.blockPosition());
        return space != null && space.rules().keepInventory();
    }

    public boolean restoreInventory(ServerPlayer player) {
        boolean retained = keepInventory(player);
        retainedDeaths.remove(player.getUUID());
        return retained;
    }

    public boolean retainHealth(ServerPlayer player, float resultingHealth) {
        if (!MinecraftDoorService.isPocketLevel(player.level())) {
            return false;
        }
        PocketRescuePolicy.Decision decision = PocketRescuePolicy.evaluate(player.getHealth(), player.getMaxHealth(),
            Math.max(0.0D, player.getHealth() - resultingHealth), rescues.containsKey(player.getUUID()));
        if (!decision.preventsDamage()) {
            return false;
        }
        player.setHealth((float) decision.retainedHealth());
        player.fallDistance = 0.0D;
        player.clearFire();
        player.setInvulnerableTime(Math.max(player.getInvulnerableTime(), 40));
        if (decision.startsEjection() && !doors.travelling(player.getUUID())) {
            rescue(player, "lethal damage");
        }
        return true;
    }

    public ClientboundSetTimePacket clockPacket(UUID playerId, ClientboundSetTimePacket packet) {
        ClockOverride override = clocks.get(playerId);
        if (override == null) {
            return packet;
        }
        ClockNetworkState current = packet.clockUpdates().get(override.clock());
        if (current != null && current.totalTicks() == override.time() && current.partialTick() == 0 && current.rate() == 0) {
            return packet;
        }
        Map<Holder<WorldClock>, ClockNetworkState> updates = new LinkedHashMap<>(packet.clockUpdates());
        updates.put(override.clock(), new ClockNetworkState(override.time(), 0, 0));
        return new ClientboundSetTimePacket(packet.gameTime(), Map.copyOf(updates));
    }

    public void playerDisconnected(ServerPlayer player) {
        Rescue rescue = rescues.remove(player.getUUID());
        if (rescue != null && rescue.lease != null) {
            rescue.lease.close();
        }
        previousSpaces.remove(player.getUUID());
        clocks.remove(player.getUUID());
        retainedDeaths.remove(player.getUUID());
    }

    @Override
    public void close() {
        closed = true;
        for (Rescue rescue : rescues.values()) {
            if (rescue.lease != null) {
                rescue.lease.close();
            }
        }
        rescues.clear();
        retainedDeaths.clear();
        previousSpaces.clear();
        clocks.clear();
    }

    private void observe(ServerPlayer player) {
        Rescue rescue = rescues.get(player.getUUID());
        if (rescue != null && System.currentTimeMillis() >= rescue.deadline) {
            finish(player, rescue);
        }
        PocketSpace space = doors.spaceAt(player.level(), player.blockPosition());
        updateClock(player, space);
        if (!MinecraftDoorService.isPocketLevel(player.level())) {
            previousSpaces.remove(player.getUUID());
            return;
        }
        if (doors.changingPocket(player.blockPosition())) {
            return;
        }
        PocketSpace previous = space == null ? previousSpaces.get(player.getUUID()) : space;
        if (space != null) {
            previousSpaces.put(player.getUUID(), space);
        }
        if (!player.isSpectator() && !doors.travelling(player.getUUID()) && escaped(previous, player.blockPosition())) {
            rescue(player, previous == null ? "not inside any pocket at " + player.blockPosition().toShortString()
                : "outside pocket " + previous.spaceId() + " at " + player.blockPosition().toShortString());
        }
    }

    private void updateClock(ServerPlayer player, PocketSpace space) {
        ClockOverride previous = clocks.get(player.getUUID());
        Holder<WorldClock> clock = space == null || !space.rules().hasFixedTime() ? null
            : player.level().dimensionType().defaultClock().orElse(null);
        if (clock == null) {
            if (previous == null) {
                return;
            }
            clocks.remove(player.getUUID());
        } else {
            long time = space.rules().fixedTime();
            if (previous != null && previous.time() == time && previous.clock().equals(clock)) {
                return;
            }
            clocks.put(player.getUUID(), new ClockOverride(clock, time));
        }
        player.connection.send(clockPacket(player.getUUID(), server.clockManager().createFullSyncPacket()));
    }

    private void rescue(ServerPlayer player, String reason) {
        if (closed || rescues.containsKey(player.getUUID()) || !player.isAlive()) {
            return;
        }
        LOGGER.info("Returning {} from the pocket dimension: {}", player.getScoreboardName(), reason);
        ReturnTicket ticket = doors.state().getReturnTicket(player.getUUID()).orElse(null);
        Rescue rescue = new Rescue(ticket, System.currentTimeMillis() + 30_000L);
        rescues.put(player.getUUID(), rescue);
        if (!runtime.schedule(() -> route(player, rescue), 1L)) {
            finish(player, rescue);
        }
    }

    private void route(ServerPlayer player, Rescue rescue) {
        if (!current(player, rescue)) {
            finish(player, rescue);
            return;
        }
        ReturnTicket ticket = rescue.ticket;
        ServerLevel level = ticket == null ? null : doors.level(ticket.sourceWorldKey());
        if (level == null || MinecraftDoorService.isPocketLevel(level)) {
            fallback(player, rescue);
            return;
        }
        load(player, rescue, new Target(level, new Vec3d(ticket.x(), ticket.y(), ticket.z()), ticket.yaw(), ticket.pitch()), false);
    }

    private void fallback(ServerPlayer player, Rescue rescue) {
        ServerLevel level = server.overworld();
        LevelData.RespawnData spawn = level.getRespawnData();
        BlockPos point = spawn.pos();
        load(player, rescue, new Target(level, new Vec3d(point.getX() + 0.5D, point.getY(), point.getZ() + 0.5D),
            spawn.yaw(), spawn.pitch()), true);
    }

    private void load(ServerPlayer player, Rescue rescue, Target target, boolean fallback) {
        if (!current(player, rescue)) {
            finish(player, rescue);
            return;
        }
        try {
            rescue.lease = runtime.leases().retain(target.level(), MinecraftDoorService.worldId(target.level()),
                (int) Math.floor(target.point().x()) >> 4, (int) Math.floor(target.point().z()) >> 4);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not prepare pocket rescue for {}", player.getUUID(), exception);
            failed(player, rescue, fallback);
            return;
        }
        ChunkLease lease = rescue.lease;
        lease.ready().whenCompleteAsync((ready, error) -> {
            boolean success = false;
            try {
                if (error != null) {
                    LOGGER.error("Could not load pocket rescue destination for {}", player.getUUID(), error);
                }
                if (!current(player, rescue)) {
                    return;
                }
                if (Boolean.TRUE.equals(ready)) {
                    Optional<Vec3d> point = DoorArrivals.findSafeNear(target.point(), 3,
                        candidate -> MinecraftDoorService.safe(player, target.level(), candidate, true));
                    if (point.isPresent()) {
                        success = doors.teleport(player, target.level(), point.get(), target.yaw(), target.pitch(), ZERO);
                    }
                }
                if (success) {
                    if (rescue.ticket != null) {
                        doors.removeTicket(player, rescue.ticket);
                    }
                    finish(player, rescue);
                } else {
                    failed(player, rescue, fallback);
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Pocket rescue failed for {}", player.getUUID(), exception);
                failed(player, rescue, fallback);
            } finally {
                lease.close();
                if (!current(player, rescue) && rescues.get(player.getUUID()) == rescue) {
                    finish(player, rescue);
                }
            }
        }, server);
    }

    private void failed(ServerPlayer player, Rescue rescue, boolean fallback) {
        if (!fallback && current(player, rescue)) {
            fallback(player, rescue);
            return;
        }
        if (!closed && !player.hasDisconnected()) {
            player.sendSystemMessage(Component.literal("No safe pocket return point is available."));
        }
        finish(player, rescue);
    }

    private boolean current(ServerPlayer player, Rescue rescue) {
        return !closed && rescues.get(player.getUUID()) == rescue && !player.hasDisconnected() && player.isAlive()
            && MinecraftDoorService.isPocketLevel(player.level()) && System.currentTimeMillis() < rescue.deadline;
    }

    private void finish(ServerPlayer player, Rescue rescue) {
        rescues.remove(player.getUUID(), rescue);
        if (rescue.lease != null) {
            rescue.lease.close();
        }
    }

    private static boolean escaped(PocketSpace space, BlockPos point) {
        if (space == null) {
            return true;
        }
        if (!PocketEscapePolicy.isEscaped(new PocketLayout(space), point.getX(), point.getY(), point.getZ())) {
            return false;
        }
        for (PocketRoom room : space.rooms()) {
            if (!PocketEscapePolicy.isEscaped(PocketRooms.layout(space, room), point.getX(), point.getY(), point.getZ())) {
                return false;
            }
        }
        return true;
    }

    private record ClockOverride(Holder<WorldClock> clock, long time) { }
    private record Target(ServerLevel level, Vec3d point, float yaw, float pitch) { }

    private static final class Rescue {
        private final ReturnTicket ticket;
        private final long deadline;
        private ChunkLease lease;

        private Rescue(ReturnTicket ticket, long deadline) {
            this.ticket = ticket;
            this.deadline = deadline;
        }
    }
}
