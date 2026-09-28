package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.modded.MinecraftJsonDocuments;
import art.arcane.wormholes.modded.MinecraftPacketBlobs;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftRemoteViewCodec;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.replication.ChunkResyncRequest;
import art.arcane.wormholes.network.replication.RemoteChunkStore;
import art.arcane.wormholes.network.replication.ReplicationStreamKey;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewSlice;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class NativeViewProbe {
    private NativeViewProbe() {
    }

    public static CompletableFuture<Boolean> run(GameTestHelper helper, WormholesModRuntime runtime) {
        runtime.requireServerThread();
        MinecraftServer server = runtime.server();
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = (ServerPlayer) helper.makeMockServerPlayer(GameType.CREATIVE);
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 2, 8));
        BlockPos sample = anchor.offset(0, 0, 1);
        BlockState previousBlock = level.getBlockState(sample);
        level.setBlockAndUpdate(sample, Blocks.GOLD_BLOCK.defaultBlockState());
        MinecraftPortal portal = runtime.portals().create(owner.getUUID(), level,
            List.of(anchor, anchor.above(), anchor.east(), anchor.east().above()), PortalType.GATEWAY, new Vec3(0, 0, -1));
        portal.setNetworkViewDepth(3);
        portal.setNetworkViewLateralPad(1);
        portal.setRenderMode(ProjectionRenderMode.PANOPTIC);
        runtime.portals().save(portal);
        ArmorStand entity = new ArmorStand(EntityTypes.ARMOR_STAND, level);
        entity.setPos(sample.getX() + 0.5D, sample.getY() + 1.0D, sample.getZ() + 0.5D);
        entity.setNoGravity(true);
        entity.setInvisible(true);
        entity.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        level.addFreshEntity(entity);
        NetworkManager target = runtime.network().manager();
        NetworkConfig previous = target.activeConfig();
        String peerName = "view-probe-" + UUID.randomUUID();
        NetworkConfig enabled = config(target.getLocalName());
        enabled.enabled = true;
        target.applyConfig(enabled);
        target.statusPollInFlight.add(peerName);
        RemoteViewCache<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> cache =
            new RemoteViewCache<>(new MinecraftRemoteViewCodec(server.registryAccess()), RemoteViewCache.Options.defaults());
        cache.getOrCreate(target.getLocalName(), portal.getId());
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread.ofVirtual().name("Wormholes-view-probe").start(() -> {
            Throwable failure = null;
            try {
                exchange(server, target, new Probe(peerName, portal.getId(), entity.getUUID(), sample, cache), () -> {
                    level.setBlockAndUpdate(sample, Blocks.DIAMOND_BLOCK.defaultBlockState());
                    entity.setInvisible(false);
                    entity.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
                });
            } catch (Throwable error) {
                failure = error;
            }
            Throwable outcome = failure;
            server.execute(() -> {
                Throwable cleanupFailure = outcome;
                try {
                    runtime.network().viewServer().peerDisconnected(peerName);
                    target.statusPollInFlight.remove(peerName);
                    target.removePeer(peerName);
                    runtime.network().remotePortals().removePeer(peerName);
                    target.applyConfig(previous);
                    runtime.portals().remove(owner, portal.getId());
                    entity.discard();
                    level.setBlockAndUpdate(sample, previousBlock);
                } catch (Throwable error) {
                    if (cleanupFailure == null) {
                        cleanupFailure = error;
                    } else {
                        cleanupFailure.addSuppressed(error);
                    }
                }
                if (cleanupFailure == null) {
                    LoggerFactory.getLogger("WormholesGameTest").info(
                        "WORMHOLES_GAME_TEST_PASS remote_view signed_subscribe bulk_palette live_block_diff entity_metadata equipment_reset resync unsubscribe");
                    result.complete(true);
                } else {
                    result.completeExceptionally(cleanupFailure);
                }
            });
        });
        return result;
    }

    private static void exchange(MinecraftServer server, NetworkManager target, Probe probe, Runnable change) throws Exception {
        Path directory = Files.createTempDirectory("wormholes-view-probe-");
        NetworkManager client = null;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            NetworkConfig settings = config(probe.peer());
            settings.enabled = true;
            client = new NetworkManager(Logger.getLogger("Wormholes-view-probe"), new NetworkManager.Options(settings,
                SharedConstants.getCurrentVersion().name(), "probe", reserved.getLocalPort(), directory,
                MinecraftJsonDocuments.INSTANCE, SharedConstants.getCurrentVersion().protocolVersion()));
            NetworkConfig.PeerEntry route = new NetworkConfig.PeerEntry();
            route.name = target.getLocalName();
            route.host = "127.0.0.1";
            route.publicHost = "127.0.0.1";
            route.privateHost = "127.0.0.1";
            route.publicPort = server.getPort();
            route.privatePort = server.getPort();
            Queue<WireMessage> outgoing = new ConcurrentLinkedQueue<>();
            int[] bulks = {0};
            int[] diffs = {0};
            client.setMessageSink((peer, message) -> receive(probe, peer, message, outgoing, bulks, diffs));
            client.savePeer(route);
            client.statusPollInFlight.add(target.getLocalName());
            client.start();
            outgoing.add(new WireMessage.ViewSubscribe(probe.portal()));
            NetworkManager source = client;
            await(() -> ready(probe, target.getLocalName(), Blocks.GOLD_BLOCK.defaultBlockState(), Items.GOLDEN_HELMET, true),
                () -> poll(source, route, outgoing), "initial remote scene");
            CompletableFuture<Void> changed = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    change.run();
                    changed.complete(null);
                } catch (Throwable error) {
                    changed.completeExceptionally(error);
                }
            });
            changed.get(10, TimeUnit.SECONDS);
            await(() -> ready(probe, target.getLocalName(), Blocks.DIAMOND_BLOCK.defaultBlockState(), Items.DIAMOND_HELMET, false),
                () -> poll(source, route, outgoing), "live block, equipment and default metadata reset");
            if (diffs[0] == 0) {
                throw new IllegalStateException("Remote block changed without a chunk diff");
            }
            RemoteViewCache.RemoteView<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view =
                probe.cache().get(target.getLocalName(), probe.portal());
            int previousBulks = bulks[0];
            outgoing.add(new WireMessage.ChunkResyncRequestMessage(new ChunkResyncRequest(
                new ReplicationStreamKey(probe.portal(), view.getSourceWorldId(),
                    ViewSlice.columnKey(probe.sample().getX() >> 4, probe.sample().getZ() >> 4), view.getRenderMode()), 0L)));
            await(() -> bulks[0] > previousBulks, () -> poll(source, route, outgoing), "canonical resync bulk");
            outgoing.add(new WireMessage.ViewUnsubscribe(probe.portal()));
            await(outgoing::isEmpty, () -> poll(source, route, outgoing), "unsubscribe acknowledgement");
            CompletableFuture<Boolean> unsubscribed = new CompletableFuture<>();
            server.execute(() -> unsubscribed.complete(target.getReplicationManager().streamsFor(probe.peer()).isEmpty()));
            if (!unsubscribed.get(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Unsubscribe retained source replication streams");
            }
        } finally {
            if (client != null) {
                client.stop();
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void receive(Probe probe, String peer, WireMessage message, Queue<WireMessage> outgoing, int[] bulks, int[] diffs) {
        switch (message) {
            case WireMessage.ChunkBulkBatch bulk -> {
                probe.cache().applyChunkBulk(peer, bulk.chunks());
                bulks[0] += bulk.chunks().size();
            }
            case WireMessage.ChunkDiff diff -> {
                for (RemoteChunkStore.ApplyOutcome result : probe.cache().applyChunkDiff(peer, diff.batches())) {
                    if (result.resyncRequested()) {
                        outgoing.add(new WireMessage.ChunkResyncRequestMessage(new ChunkResyncRequest(result.stream(), result.expectedSequenceOrLastApplied())));
                    }
                }
                diffs[0]++;
            }
            case WireMessage.ViewEntities entities -> probe.cache().applyEntities(peer, entities.portalId(), entities.entities(), entities.presentIds());
            case WireMessage.ViewTime time -> probe.cache().applyTime(peer, time.portalId(), time.skyDarken());
            case WireMessage.ViewBulkComplete complete -> probe.cache().markViewReady(peer, complete.portalId());
            default -> {
            }
        }
    }

    private static boolean ready(Probe probe, String peer, BlockState block, Item helmet, boolean invisible) {
        RemoteViewCache.RemoteView<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view = probe.cache().get(peer, probe.portal());
        if (view == null || !view.isViewReady()) {
            return false;
        }
        BlockPos position = probe.sample();
        RemoteViewCache.DecodedSlice<BlockState> slice = view.sliceAt(position.getX(), position.getZ());
        if (slice == null || slice.blockAt(position.getX(), position.getY(), position.getZ()) != block) {
            return false;
        }
        List<MinecraftPacketBlobs.Equipment> items = view.getEquipment(probe.entity());
        List<SynchedEntityData.DataValue<?>> values = view.getMetadata(probe.entity());
        if (items == null || values == null) {
            return false;
        }
        boolean equipment = items.stream().anyMatch(piece -> piece.slot() == EquipmentSlot.HEAD && piece.item().is(helmet));
        boolean metadata = values.stream().anyMatch(value -> value.id() == 0
            && value.value() instanceof Byte flags && ((flags & 32) != 0) == invisible);
        return equipment && metadata;
    }

    private static void poll(NetworkManager client, NetworkConfig.PeerEntry route, Queue<WireMessage> outgoing) throws Exception {
        WireMessage pending = outgoing.peek();
        List<MinecraftStatusBridge.EncodedMessage> messages = pending == null ? List.of()
            : List.of(new MinecraftStatusBridge.EncodedMessage(pending, WireCodec.encodeFrame(pending)));
        MinecraftStatusBridge.StatusPacket request = client.createStatusBridgePacket(route.name, messages);
        MinecraftStatusBridge.StatusPacket response = client.statusBridge().poll(route, request);
        if (response == null || !client.handleStatusBridgeResponse(route.name, response, 1L)) {
            throw new IllegalStateException("Native view response failed authentication or admission");
        }
        if (pending != null && response.ackNonce() == request.nonce()) {
            outgoing.remove(pending);
        }
        Thread.sleep(75L);
    }

    private static void await(BooleanSupplier condition, Step poll, String stage) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out waiting for " + stage);
            }
            poll.run();
        }
    }

    private static NetworkConfig config(String name) {
        NetworkConfig config = new NetworkConfig();
        config.serverName = name;
        config.listenEnabled = false;
        config.advertiseHostOverride = "127.0.0.1";
        config.transport.udsEnabled = false;
        config.mesh.enabled = false;
        return config;
    }

    private record Probe(String peer, UUID portal, UUID entity, BlockPos sample,
                         RemoteViewCache<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> cache) {
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }
}
