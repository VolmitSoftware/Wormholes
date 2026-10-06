package art.arcane.wormholes.network;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.modded.MinecraftGameTestPlayer;
import art.arcane.wormholes.modded.MinecraftJsonDocuments;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.convoy.ConvoyManifest;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
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

public final class NativeEntityTransferProbe {
    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final List<Entity> created = new ArrayList<>();
    private final long deadline = System.currentTimeMillis() + 45_000L;
    private MinecraftGameTestPlayer actor;
    private ProbeNetwork network;
    private MinecraftEntityTransfers transfers;
    private MinecraftPortal source;
    private MinecraftPortal exit;
    private PlaneCrossing crossing;
    private Path directory;
    private Pig pig;
    private WireMessage.EntityTransfer entityOffer;
    private ConvoyManifest convoy;
    private int stage;

    private NativeEntityTransferProbe(GameTestHelper helper, WormholesModRuntime runtime) {
        this.helper = helper;
        this.runtime = runtime;
    }

    public static CompletableFuture<Boolean> run(GameTestHelper helper, WormholesModRuntime runtime) {
        NativeEntityTransferProbe probe = new NativeEntityTransferProbe(helper, runtime);
        probe.start();
        return probe.result;
    }

    private void start() {
        try {
            directory = Files.createTempDirectory("wormholes-entity-transfers-");
            actor = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "EntityProbe");
            source = portal(3);
            exit = portal(12);
            source.linkRemote("destination", exit.getId());
            crossing = new PlaneCrossing(source.getFrame(), source.getOrigin(), source.getOrigin(),
                new Vec3d(0, 0, 0.1D), new Vec3d(0, 0, -1), true);
            network = new ProbeNetwork(directory);
            transfers = new MinecraftEntityTransfers(runtime, network);
            pig = pig("EntityTransferSource");
            assertThat(transfers.begin(pig, source, crossing, new NetworkMember(exit.getId(), "", "", 0, "destination")), "Native entity offer was rejected");
            entityOffer = network.last(WireMessage.EntityTransfer.class);
            assertThat(pig.isPermanentlyInvulnerable() && pig.isSilent() && pig.isNoGravity(), "Source entity was not frozen");
            transfers.receive("impostor", new WireMessage.EntityTransferAck(entityOffer.transferId(), true));
            assertThat(!pig.isRemoved() && transfers.locked(pig.getUUID()), "Foreign peer acknowledged source entity");
            transfers.receive("destination", new WireMessage.EntityTransferAck(entityOffer.transferId(), false));
            assertThat(!pig.isPermanentlyInvulnerable() && !pig.isSilent() && !transfers.locked(pig.getUUID()), "Denied source entity was not restored");
            NetworkConfig config = runtime.configuration().settings().getNetwork();
            long timeout = config.handoffTimeoutMs;
            try {
                config.handoffTimeoutMs = 100L;
                assertThat(transfers.begin(pig, source, crossing, new NetworkMember(exit.getId(), "", "", 0, "destination")), "Native timeout offer was rejected");
                entityOffer = network.last(WireMessage.EntityTransfer.class);
            } finally {
                config.handoffTimeoutMs = timeout;
            }
            tick();
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void tick() {
        try {
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("Entity transfer runtime stage " + stage + " timed out");
            }
            transfers.tick();
            if (stage == 0 && !transfers.locked(pig.getUUID()) && !pig.isPermanentlyInvulnerable()) {
                transfers.receive("destination", new WireMessage.EntityTransferAck(entityOffer.transferId(), true));
                assertThat(pig.isRemoved(), "Late accepted ACK did not remove the restored source copy");
                pig = pig("EntityTransferArrival");
                byte[] snapshot = MinecraftEntitySnapshots.capture(pig);
                pig.discard();
                entityOffer = new WireMessage.EntityTransfer(UUID.randomUUID(), exit.getId(), snapshot, WireTraversive.fromCrossing(crossing));
                transfers.receive("source", entityOffer);
                stage = 1;
            } else if (stage == 1 && network.accepted(entityOffer.transferId())) {
                List<Entity> arrivals = named("EntityTransferArrival");
                assertThat(arrivals.size() == 1, "Destination did not spawn exactly one native entity");
                created.addAll(arrivals);
                Vec3d target = crossing.outPoint(exit.getFrame(), exit.getOrigin());
                assertThat(arrivals.getFirst().position().distanceToSqr(target.x(), target.y(), target.z()) < 4,
                    "Destination entity was not placed at its exit");
                transfers.receive("source", entityOffer);
                assertThat(named("EntityTransferArrival").size() == 1, "Replayed entity offer duplicated its destination copy");
                pig = pig("ConvoyTransferMember");
                actor.player().teleportTo(pig.getX(), pig.getY(), pig.getZ());
                assertThat(actor.player().startRiding(pig, true, false), "Could not mount source convoy fixture");
                assertThat(transfers.beginConvoy(pig, source, crossing, new NetworkMember(exit.getId(), "", "", 0, "destination")), "Native convoy was not handled");
                convoy = network.last(WireMessage.ConvoyTransfer.class).manifest();
                assertThat(convoy.members().size() == 2 && pig.isPermanentlyInvulnerable(), "Source convoy did not freeze its member");
                transfers.receive("destination", new WireMessage.ConvoyAck(convoy.groupId(), false, "fixture denial"));
                assertThat(!pig.isPermanentlyInvulnerable() && actor.player().getVehicle() == pig, "Source convoy denial did not restore its rig");
                transfers.receive("source", new WireMessage.ConvoyTransfer(convoy));
                stage = 2;
            } else if (stage == 2 && network.convoyAccepted(convoy.groupId())) {
                List<Entity> members = named("ConvoyTransferMember");
                assertThat(members.size() == 2, "Destination convoy snapshot duplicated passengers or lost its member");
                Entity arrived = members.stream().filter(entity -> entity != pig).findFirst().orElseThrow();
                created.add(arrived);
                assertThat(arrived.isInvisible() && arrived.isPermanentlyInvulnerable(), "Destination convoy member was not held");
                transfers.playerPlaced(actor.player(), exit, crossing);
                assertThat(actor.player().getVehicle() == arrived, "Destination did not restore the passenger attachment");
                assertThat(!arrived.isInvisible() && !arrived.isPermanentlyInvulnerable(), "Destination did not reveal and restore the convoy member");
                transfers.receive("source", new WireMessage.ConvoyTransfer(convoy));
                assertThat(named("ConvoyTransferMember").size() == 2, "Completed convoy replay duplicated its member");
                transfers.receive("impostor", new WireMessage.ConvoyTransfer(convoy));
                assertThat(!network.last(WireMessage.ConvoyAck.class).accepted(), "Foreign peer replay received convoy admission");
                finish(null);
                return;
            }
            runtime.schedule(this::tick, 1L);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private MinecraftPortal portal(int x) {
        BlockPos anchor = helper.absolutePos(new BlockPos(x, 3, 8));
        return runtime.portals().create(actor.player().getUUID(), helper.getLevel(),
            List.of(anchor, anchor.above(), anchor.east(), anchor.east().above()), PortalType.GATEWAY, new Vec3(0, 0, -1));
    }

    private Pig pig(String name) {
        Pig entity = helper.spawn(EntityTypes.PIG, new Vec3(3.5, 3, 3.5));
        entity.setNoAi(true);
        entity.setNoGravity(true);
        entity.setCustomName(Component.literal(name));
        created.add(entity);
        return entity;
    }

    private List<Entity> named(String name) {
        List<Entity> matching = new ArrayList<>();
        for (Entity entity : helper.getLevel().getAllEntities()) {
            if (!entity.isRemoved() && entity.getCustomName() != null && entity.getCustomName().getString().equals(name)) {
                matching.add(entity);
            }
        }
        return matching;
    }

    private void finish(Throwable failure) {
        if (result.isDone()) {
            return;
        }
        try {
            if (transfers != null) {
                transfers.close();
            }
            if (actor != null) {
                actor.player().stopRiding();
            }
            for (Entity entity : created) {
                entity.discard();
            }
            if (source != null) {
                runtime.portals().remove(actor.player(), source.getId());
            }
            if (exit != null) {
                runtime.portals().remove(actor.player(), exit.getId());
            }
            if (actor != null) {
                actor.close();
            }
            if (network != null) {
                network.stop();
            }
            if (directory != null) {
                try (Stream<Path> files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            }
        } catch (Throwable cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        if (failure == null) {
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS entity_transfers native_snapshot source_freeze denial_rollback timeout_restore late_ack destination_spawn replay convoy_hold passenger_attach receipt peer_binding");
            result.complete(true);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class ProbeNetwork extends NetworkManager {
        private final List<WireMessage> sent = new ArrayList<>();
        private final NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();

        private ProbeNetwork(Path directory) {
            super(Logger.getLogger("WormholesEntityTest"), new NetworkManager.Options(new NetworkConfig(),
                SharedConstants.getCurrentVersion().name(), "fixture", 25565, directory, MinecraftJsonDocuments.INSTANCE,
                SharedConstants.getCurrentVersion().protocolVersion()));
            peer.name = "destination";
        }

        @Override
        public NetworkConfig.PeerEntry getPeer(String name) { return peer; }
        @Override
        public boolean isPeerReady(String name) { return true; }
        @Override
        public boolean peerSupports(String name, WireCapability capability) { return true; }
        @Override
        public boolean send(String name, WireMessage message) { sent.add(message); return true; }

        private <T extends WireMessage> T last(Class<T> type) {
            for (int index = sent.size() - 1; index >= 0; index--) {
                if (type.isInstance(sent.get(index))) {
                    return type.cast(sent.get(index));
                }
            }
            throw new IllegalStateException("Missing transfer message " + type.getSimpleName());
        }

        private boolean accepted(UUID transferId) {
            return sent.stream().anyMatch(message -> message instanceof WireMessage.EntityTransferAck ack
                && ack.transferId().equals(transferId) && ack.accepted());
        }

        private boolean convoyAccepted(UUID groupId) {
            return sent.stream().anyMatch(message -> message instanceof WireMessage.ConvoyAck ack
                && ack.groupId().equals(groupId) && ack.accepted());
        }
    }
}
