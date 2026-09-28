package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.localization.MeshMessages;
import art.arcane.wormholes.modded.MinecraftMenuText;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.mesh.DestinationPolicy;
import art.arcane.wormholes.network.mesh.DestinationPolicyEngine;
import art.arcane.wormholes.network.mesh.HandoffQueue;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.portal.DepartureHoldPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MinecraftGatewayPolicies implements AutoCloseable {
    private final WormholesModRuntime runtime;
    private final NetworkManager network;
    private final MinecraftPlayerHandoffs handoffs;
    private final DestinationPolicyEngine engine = new DestinationPolicyEngine();
    private final HandoffQueue queue = new HandoffQueue();
    private final Map<UUID, Hold> holds = new HashMap<>();
    private int ticks;

    MinecraftGatewayPolicies(WormholesModRuntime runtime, NetworkManager network, MinecraftPlayerHandoffs handoffs) {
        this.runtime = runtime;
        this.network = network;
        this.handoffs = handoffs;
    }

    public static boolean active(MinecraftPortal portal) {
        DestinationPolicy policy = policy(portal);
        return policy != null && !policy.candidates().isEmpty();
    }

    boolean begin(ServerPlayer player, MinecraftPortal portal, PortalCrossing crossing) {
        DestinationPolicy policy = policy(portal);
        if (policy == null || policy.candidates().isEmpty()) {
            return false;
        }
        if (player.hasDisconnected() || player.isRemoved() || player.isPassenger() || !player.getPassengers().isEmpty()
            || !portal.isOpen() || !runtime.portals().canDepart(player, portal)) {
            return true;
        }
        if (holds.containsKey(player.getUUID())) {
            return true;
        }
        DestinationPolicyEngine.Resolution resolution = resolve(player, policy);
        if (resolution.kind() == DestinationPolicyEngine.Resolution.Kind.CHOSEN) {
            handoffs.beginResolved(player, resolution.server(), portal, crossing, resolution.portalId());
            return true;
        }
        NetworkConfig config = network.activeConfig();
        if (resolution.kind() != DestinationPolicyEngine.Resolution.Kind.QUEUE || !config.policy.queueEnabled) {
            reject(player, portal, crossing, false);
            return true;
        }
        long budget = config.handoffTimeoutMs + NetworkManager.PLAYER_ENDPOINT_TIMEOUT_MILLIS + 1_000L;
        long wait = Math.max(1_000L, Math.min(config.policy.queueMaxWaitSec * 1_000L, 30_000L - budget));
        Hold hold = new Hold(player, portal, crossing, policy, player.level(), player.position());
        holds.put(player.getUUID(), hold);
        queue.enqueue(player.getUUID(), System.currentTimeMillis(), wait, () -> valid(hold) ? resolve(player, policy) : DestinationPolicyEngine.Resolution.none(),
            chosen -> {
                holds.remove(player.getUUID());
                handoffs.beginResolved(player, chosen.server(), portal, crossing, chosen.portalId());
            }, () -> {
                holds.remove(player.getUUID());
                reject(player, portal, crossing, true);
            }, (position, remaining) -> player.sendSystemMessage(MinecraftMenuText.text(player, MeshMessages.QUEUE_POSITION,
                Map.of("count", position, "seconds", Math.max(0L, (remaining + 999L) / 1000L))), true));
        return true;
    }

    boolean locked(UUID playerId) {
        return holds.containsKey(playerId);
    }

    void disconnected(ServerPlayer player) {
        Hold removed = holds.remove(player.getUUID());
        queue.remove(queue.ticket(player.getUUID()));
        if (removed != null) {
            runtime.rules().failed(player);
        }
    }

    void tick() {
        for (Hold hold : List.copyOf(holds.values())) {
            if (!valid(hold)) {
                holds.remove(hold.player().getUUID());
                queue.remove(queue.ticket(hold.player().getUUID()));
                reject(hold.player(), hold.portal(), hold.crossing(), true);
                continue;
            }
            hold.player().setDeltaMovement(Vec3.ZERO);
            if (hold.player().position().distanceToSqr(hold.position()) > DepartureHoldPolicy.LEASH_DRIFT_SQUARED) {
                hold.player().connection.teleport(hold.position().x, hold.position().y, hold.position().z,
                    hold.player().getYRot(), hold.player().getXRot());
            }
        }
        if (++ticks >= 20) {
            ticks = 0;
            queue.tick(System.currentTimeMillis());
        }
    }

    @Override
    public void close() {
        for (Hold hold : List.copyOf(holds.values())) {
            queue.remove(queue.ticket(hold.player().getUUID()));
            reject(hold.player(), hold.portal(), hold.crossing(), true);
        }
        holds.clear();
    }

    private DestinationPolicyEngine.Resolution resolve(ServerPlayer player, DestinationPolicy policy) {
        long now = System.currentTimeMillis();
        return engine.resolve(player.getUUID(), policy, DestinationPolicyEngine.inputs(network, runtime.network().remotePortals(),
            network.activeConfig().policy.beaconStaleSec * 1000L, now), now);
    }

    private boolean valid(Hold hold) {
        ServerPlayer player = hold.player();
        return !player.hasDisconnected() && !player.isRemoved() && !player.isPassenger() && player.getPassengers().isEmpty()
            && runtime.portals().get(hold.portal().getId()) == hold.portal() && hold.portal().isOpen()
            && runtime.portals().canDepart(player, hold.portal()) && hold.policy().equals(policy(hold.portal()))
            && DepartureHoldPolicy.decide(true, player.level() == hold.level(),
                hold.crossing().sourceSideDistance(new GeometryVector(player.getX(), player.getY(), player.getZ())),
                player.position().distanceToSqr(hold.position()), 1L) == DepartureHoldPolicy.Decision.HOLD_PIN;
    }

    private void reject(ServerPlayer player, MinecraftPortal portal, PortalCrossing crossing, boolean timedOut) {
        runtime.rules().failed(player);
        if (player.hasDisconnected() || player.isRemoved()) {
            return;
        }
        if (player.level() == runtime.portals().resolveLevel(portal)) {
            GeometryVector point = crossing.rejectionPoint();
            player.connection.teleport(point.x(), point.y(), point.z(), player.getYRot(), player.getXRot());
            runtime.portals().recordArrival(player, portal);
        }
        player.sendSystemMessage(MinecraftMenuText.text(player, timedOut ? MeshMessages.QUEUE_TIMEOUT : MeshMessages.POLICY_NONE, Map.of()));
    }

    private static DestinationPolicy policy(MinecraftPortal portal) {
        return portal == null ? null : portal.meshPolicy();
    }

    private record Hold(ServerPlayer player, MinecraftPortal portal, PortalCrossing crossing, DestinationPolicy policy, Level level, Vec3 position) {
    }
}
