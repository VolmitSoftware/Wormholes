package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.MinecraftGameTestPlayer;
import art.arcane.wormholes.modded.MinecraftJsonDocuments;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.mesh.DestinationCandidate;
import art.arcane.wormholes.network.mesh.DestinationPolicy;
import art.arcane.wormholes.network.mesh.LoadBeacon;
import art.arcane.wormholes.network.mesh.SelectionStrategy;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class NativeGatewayPolicyProbe {
    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final String peer = "queue-probe-" + UUID.randomUUID();
    private final UUID destination = UUID.randomUUID();
    private final long deadline = System.currentTimeMillis() + 15_000L;
    private MinecraftGameTestPlayer actor;
    private MinecraftPortal source;
    private ProbeNetwork network;
    private MinecraftPlayerHandoffs handoffs;
    private MinecraftGatewayPolicies policies;
    private PortalCrossing crossing;
    private Path directory;
    private int stage;
    private int ticks;

    private NativeGatewayPolicyProbe(GameTestHelper helper, WormholesModRuntime runtime) {
        this.helper = helper;
        this.runtime = runtime;
    }

    public static CompletableFuture<Boolean> run(GameTestHelper helper, WormholesModRuntime runtime) {
        NativeGatewayPolicyProbe probe = new NativeGatewayPolicyProbe(helper, runtime);
        probe.start();
        return probe.result;
    }

    private void start() {
        try {
            directory = Files.createTempDirectory("wormholes-gateway-policy-");
            actor = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "QueueProbe");
            BlockPos position = helper.absolutePos(new BlockPos(4, 2, 4));
            source = runtime.portals().create(actor.player().getUUID(), helper.getLevel(),
                List.of(position, position.above(), position.east(), position.east().above()), PortalType.GATEWAY, new Vec3(0, 0, -1));
            source.setMeshPolicy(new DestinationPolicy(List.of(new DestinationCandidate(peer, destination, null, 1)),
                SelectionStrategy.LEAST_LOADED, 1, 18, true).encode());
            actor.player().setNoGravity(true);
            actor.player().setPos(source.getOrigin().x(), source.getOrigin().y(), source.getOrigin().z());
            runtime.portals().recordArrival(actor.player(), source);
            crossing = new PortalCrossing(source.getFrame(), source.getOrigin(), source.getOrigin(),
                new GeometryVector(0, 0, 0.1D), new GeometryVector(0, 0, -1), true);
            runtime.network().remotePortals().applyDirectory(peer, List.of(new PortalInfo(destination, "queue-exit", "minecraft:overworld",
                "GATEWAY", true, "N", "E", "U", 0, 64, 0, 0, 64, 0, 2, 67, 1)));
            network = new ProbeNetwork(directory, peer);
            handoffs = new MinecraftPlayerHandoffs(runtime, network);
            policies = new MinecraftGatewayPolicies(runtime, network, handoffs);
            load(true);
            assertThat(policies.begin(actor.player(), source, crossing), "Full gateway policy was not handled");
            assertThat(policies.locked(actor.player().getUUID()), "Full destination did not hold traveler");
            actor.player().setDeltaMovement(0.2, 0, 0);
            policies.tick();
            assertThat(actor.player().getDeltaMovement().lengthSqr() == 0, "Queued traveler was not pinned");
            tick();
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void tick() {
        try {
            assertThat(System.currentTimeMillis() < deadline, "Gateway queue stage " + stage + " timed out");
            policies.tick();
            if (stage == 0 && ++ticks >= 3) {
                assertThat(network.sent.isEmpty(), "Full gateway sent a handoff before capacity opened");
                load(false);
                stage = 1;
            } else if (stage == 1 && !policies.locked(actor.player().getUUID())) {
                WireMessage.HandoffRequest request = network.sent.stream().filter(WireMessage.HandoffRequest.class::isInstance)
                    .map(WireMessage.HandoffRequest.class::cast).findFirst().orElseThrow();
                assertThat(destination.equals(request.destPortalId()), "Released queue chose the wrong destination");
                handoffs.receive(peer, new WireMessage.HandoffDeny(request.transferId(), "fixture cleanup", 0));
                assertThat(!handoffs.locked(actor.player().getUUID()), "Denied released handoff retained its lock");
                actor.player().setPos(source.getOrigin().x(), source.getOrigin().y(), source.getOrigin().z());
            runtime.portals().recordArrival(actor.player(), source);
                load(true);
                network.activeConfig().policy.queueMaxWaitSec = 1;
                assertThat(policies.begin(actor.player(), source, crossing), "Timeout policy was not handled");
                assertThat(policies.locked(actor.player().getUUID()), "Timeout fixture did not queue");
                stage = 2;
            } else if (stage == 2 && !policies.locked(actor.player().getUUID())) {
                assertThat(actor.player().position().distanceToSqr(crossing.rejectionPoint().x(), crossing.rejectionPoint().y(), crossing.rejectionPoint().z()) < 0.1,
                    "Timed-out traveler was not returned to the source side");
                actor.player().setPos(source.getOrigin().x(), source.getOrigin().y(), source.getOrigin().z());
            runtime.portals().recordArrival(actor.player(), source);
                assertThat(policies.begin(actor.player(), source, crossing), "Cleanup policy did not queue");
                policies.close();
                assertThat(!policies.locked(actor.player().getUUID()), "Queue close retained traveler");
                finish(null);
                return;
            }
            runtime.schedule(this::tick, 1);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void load(boolean full) {
        network.loads().record(peer, new LoadBeacon(full ? 1 : 0, 1, 0, 20, 2, false, 0, System.currentTimeMillis()), System.currentTimeMillis());
    }

    private void finish(Throwable failure) {
        try {
            if (policies != null) { policies.close(); }
            if (handoffs != null) { handoffs.close(); }
            runtime.network().remotePortals().removePeer(peer);
            if (source != null) { runtime.portals().remove(source.getId()); }
            if (actor != null) { actor.close(); }
            if (network != null) { network.stop(); }
            if (directory != null) {
                try (Stream<Path> files = Files.walk(directory)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) { Files.delete(path); }
                }
            }
        } catch (Throwable cleanup) {
            if (failure == null) { failure = cleanup; } else { failure.addSuppressed(cleanup); }
        }
        if (failure == null) {
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS gateway_policy load_selection native_hold capacity_release handoff_offer timeout_return close_cleanup");
            result.complete(true);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }

    private static final class ProbeNetwork extends NetworkManager {
        private final List<WireMessage> sent = new ArrayList<>();
        private final NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        private ProbeNetwork(Path directory, String name) {
            super(Logger.getLogger("WormholesGatewayPolicyTest"), new NetworkManager.Options(new NetworkConfig(),
                SharedConstants.getCurrentVersion().name(), "fixture", 25565, directory, MinecraftJsonDocuments.INSTANCE,
                SharedConstants.getCurrentVersion().protocolVersion()));
            peer.name = name;
            peer.useProxy = true;
            activeConfig().transferMode = "proxy";
        }
        @Override public NetworkConfig.PeerEntry getPeer(String name) { return peer; }
        @Override public boolean isPeerReady(String name) { return true; }
        @Override public boolean send(String name, WireMessage message) { sent.add(message); return true; }
    }
}
