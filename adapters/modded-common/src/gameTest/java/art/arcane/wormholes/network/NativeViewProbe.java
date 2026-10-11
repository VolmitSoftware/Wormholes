package art.arcane.wormholes.network;

import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.fidelity.BlockEntitySanitizer;
import art.arcane.optics.math.CellKeys;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.modded.MinecraftBlockEntityTags;
import art.arcane.wormholes.modded.MinecraftGameTestAccess;
import art.arcane.wormholes.modded.MinecraftJsonDocuments;
import art.arcane.wormholes.modded.MinecraftPacketBlobs;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftRemoteViewCodec;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.BlockEntityDiff;
import art.arcane.wormholes.network.replication.ChunkBulk;
import art.arcane.wormholes.network.replication.ChunkDiffBatch;
import art.arcane.wormholes.network.replication.ChunkResyncRequest;
import art.arcane.wormholes.network.replication.RemoteChunkStore;
import art.arcane.wormholes.network.replication.ReplicationStreamKey;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.FidelitySettings;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        BlockPos signPosition = sample.above();
        BlockState previousBlock = level.getBlockState(sample);
        BlockState previousSign = level.getBlockState(signPosition);
        BlockEntity previousSignEntity = level.getBlockEntity(signPosition);
        CompoundTag previousSignData = previousSignEntity == null ? null : previousSignEntity.saveWithFullMetadata(level.registryAccess());
        level.setBlockAndUpdate(sample, Blocks.GOLD_BLOCK.defaultBlockState());
        BlockState signState = Blocks.OAK_SIGN.defaultBlockState();
        level.setBlockAndUpdate(signPosition, signState);
        BlockEntitySample initialSign = changeSign(level, signPosition, signState, "native-before", false);
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
        Probe probe = new Probe(peerName, portal.getId(), entity.getUUID(), sample,
            new SignSample(signPosition, signState, initialSign), level, server.registryAccess(), cache);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread.ofVirtual().name("Wormholes-view-probe").start(() -> {
            Throwable failure = null;
            try {
                exchange(server, target, probe);
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
                    level.setBlockAndUpdate(signPosition, previousSign);
                    if (previousSignData != null) {
                        BlockEntity restored = level.getBlockEntity(signPosition);
                        if (restored == null) {
                            throw new IllegalStateException("Native view cleanup lost the previous block entity");
                        }
                        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), previousSignData));
                        restored.setChanged();
                    }
                } catch (Throwable error) {
                    if (cleanupFailure == null) {
                        cleanupFailure = error;
                    } else {
                        cleanupFailure.addSuppressed(error);
                    }
                }
                if (cleanupFailure == null) {
                    LoggerFactory.getLogger("WormholesGameTest").info(
                        "WORMHOLES_GAME_TEST_PASS remote_view signed_subscribe bulk_palette live_block_diff entity_metadata equipment_reset sign_metadata_only encoded_metadata_diff remote_sign_state no_metadata_resync resync unsubscribe");
                    result.complete(true);
                } else {
                    result.completeExceptionally(cleanupFailure);
                }
            });
        });
        return result;
    }

    private static void exchange(MinecraftServer server, NetworkManager target, Probe probe) throws Exception {
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
            Delivery delivery = new Delivery();
            Queue<WireMessage> outgoing = delivery.outgoing;
            client.setMessageSink((peer, message) -> receive(probe, peer, message, delivery));
            client.savePeer(route);
            client.statusPollInFlight.add(target.getLocalName());
            client.start();
            outgoing.add(new WireMessage.ViewSubscribe(probe.portal(), 0));
            NetworkManager source = client;
            await(() -> ready(probe, target.getLocalName(), Blocks.GOLD_BLOCK.defaultBlockState(), Items.GOLDEN_HELMET, true),
                () -> poll(source, route, outgoing), "initial remote scene");
            await(() -> remoteSign(probe, target.getLocalName(), probe.sign().initial(), "native-before", false),
                () -> poll(source, route, outgoing), "initial remote sign metadata");
            CompletableFuture<Void> changed = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    changeScene(probe);
                    changed.complete(null);
                } catch (Throwable error) {
                    changed.completeExceptionally(error);
                }
            });
            changed.get(10, TimeUnit.SECONDS);
            await(() -> ready(probe, target.getLocalName(), Blocks.DIAMOND_BLOCK.defaultBlockState(), Items.DIAMOND_HELMET, false),
                () -> poll(source, route, outgoing), "live block, equipment and default metadata reset");
            if (delivery.diffs.get() == 0) {
                throw new IllegalStateException("Remote block changed without a chunk diff");
            }
            RemoteViewCache.RemoteView<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view =
                probe.cache().get(target.getLocalName(), probe.portal());
            ReplicationStreamKey signStream = new ReplicationStreamKey(probe.portal(), view.getSourceWorldId(),
                CellKeys.chunkKey(probe.sign().position().getX() >> 4, probe.sign().position().getZ() >> 4), view.getRenderMode());
            long previousSequence = delivery.sequences.getOrDefault(signStream, -1L);
            if (previousSequence < 0) {
                throw new IllegalStateException("Initial sign subscription did not establish its replication sequence");
            }
            int metadataBulks = delivery.bulks.get();
            int metadataResyncs = delivery.resyncs.get();
            delivery.batches.clear();
            CompletableFuture<BlockEntitySample> metadata = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    metadata.complete(changeSign(probe.level(), probe.sign().position(), probe.sign().state(), "native-after", true));
                } catch (Throwable error) {
                    metadata.completeExceptionally(error);
                }
            });
            BlockEntitySample updatedSign = metadata.get(10, TimeUnit.SECONDS);
            if (updatedSign.equals(probe.sign().initial())) {
                throw new IllegalStateException("Source sign metadata did not change");
            }
            MetadataExpectation expected = new MetadataExpectation(signStream, previousSequence, metadataBulks, metadataResyncs, updatedSign);
            await(() -> metadataReady(probe, target.getLocalName(), delivery, expected),
                () -> poll(source, route, outgoing), "metadata-only signed sign diff and remote client state");
            int previousBulks = delivery.bulks.get();
            outgoing.add(new WireMessage.ChunkResyncRequestMessage(new ChunkResyncRequest(
                new ReplicationStreamKey(probe.portal(), view.getSourceWorldId(),
                    CellKeys.chunkKey(probe.sample().getX() >> 4, probe.sample().getZ() >> 4), view.getRenderMode()), 0L)));
            await(() -> delivery.bulks.get() > previousBulks, () -> poll(source, route, outgoing), "canonical resync bulk");
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

    private static void receive(Probe probe, String peer, WireMessage message, Delivery delivery) {
        switch (message) {
            case WireMessage.ChunkBulkBatch bulk -> {
                probe.cache().applyChunkBulk(peer, bulk.chunks());
                delivery.bulks.addAndGet(bulk.chunks().size());
                for (ChunkBulk chunk : bulk.chunks()) {
                    delivery.sequences.merge(chunk.stream(), chunk.sequence(), Math::max);
                }
            }
            case WireMessage.ChunkDiff diff -> {
                for (RemoteChunkStore.ApplyOutcome result : probe.cache().applyChunkDiff(peer, diff.batches())) {
                    if (result.resyncRequested()) {
                        delivery.resyncs.incrementAndGet();
                        delivery.outgoing.add(new WireMessage.ChunkResyncRequestMessage(new ChunkResyncRequest(result.stream(), result.expectedSequenceOrLastApplied())));
                    }
                }
                for (ChunkDiffBatch batch : diff.batches()) {
                    delivery.batches.add(batch);
                    delivery.sequences.merge(batch.stream(), batch.sequence(), Math::max);
                }
                delivery.diffs.incrementAndGet();
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

    private static void changeScene(Probe probe) {
        if (!probe.level().getServer().isSameThread()) {
            throw new IllegalStateException("Native view scene mutation left the server thread");
        }
        if (!(probe.level().getEntity(probe.entity()) instanceof ArmorStand entity)) {
            throw new IllegalStateException("Native view source entity is missing");
        }
        probe.level().setBlockAndUpdate(probe.sample(), Blocks.DIAMOND_BLOCK.defaultBlockState());
        entity.setInvisible(false);
        entity.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
    }

    private static BlockEntitySample changeSign(ServerLevel level, BlockPos position, BlockState state, String message, boolean waxed) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Native sign mutation left the server thread");
        }
        if (level.getBlockState(position) != state || !(level.getBlockEntity(position) instanceof SignBlockEntity sign)) {
            throw new IllegalStateException("Native view source sign is missing or changed block state");
        }
        CompoundTag data = MinecraftGameTestAccess.signTextTag(sign, level.registryAccess(), message);
        data.putBoolean("is_waxed", waxed);
        sign.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
        sign.setChanged();
        if (level.getBlockState(position) != state || sign.getBlockState() != state
            || sign.isWaxed() != waxed || !MinecraftGameTestAccess.signLine(sign).equals(message)) {
            throw new IllegalStateException("Source sign edit failed or invented a block-state change");
        }
        String type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(sign.getType()).toString();
        BlockEntitySample sample = BlockEntitySanitizer.sanitize(type, sign.saveWithFullMetadata(level.registryAccess()),
            new BlockEntitySanitizer.Options<>(FidelitySettings.blockEntityTypes, FidelitySettings.blockEntityContainers, MinecraftBlockEntityTags.INSTANCE));
        if (sample == null) {
            throw new IllegalStateException("Native view source sign metadata was not captured by the fidelity policy");
        }
        return sample;
    }

    private static boolean metadataReady(Probe probe, String peer, Delivery delivery, MetadataExpectation expected) {
        if (delivery.bulks.get() != expected.bulks() || delivery.resyncs.get() != expected.resyncs()) {
            throw new IllegalStateException("Metadata-only sign edit required a replacement bulk or resync");
        }
        BlockPos position = probe.sign().position();
        int packed = BlockChange.pack(position.getX(), position.getY(), position.getZ());
        boolean received = false;
        for (ChunkDiffBatch batch : delivery.batches) {
            if (!batch.stream().equals(expected.stream()) || batch.sequence() <= expected.sequence()) {
                continue;
            }
            for (BlockChange block : batch.blocks()) {
                if (block.packedXyz() == packed) {
                    throw new IllegalStateException("Metadata-only sign edit invented a block diff");
                }
            }
            for (BlockEntityDiff entity : batch.entities()) {
                if (entity.packedXyz() != packed) {
                    continue;
                }
                if (!batch.blocks().isEmpty()) {
                    throw new IllegalStateException("Sign metadata payload was delivered with block mutations");
                }
                try {
                    received |= BlockEntitySample.decode(entity.nbt()).equals(expected.sample());
                } catch (IOException error) {
                    throw new IllegalStateException("Signed peer delivered invalid sign metadata", error);
                }
            }
        }
        return received && remoteSign(probe, peer, expected.sample(), "native-after", true);
    }

    private static boolean remoteSign(Probe probe, String peer, BlockEntitySample expected, String message, boolean waxed) {
        RemoteViewCache.RemoteView<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view =
            probe.cache().get(peer, probe.portal());
        if (view == null || !view.isViewReady()) {
            return false;
        }
        BlockPos position = probe.sign().position();
        RemoteViewCache.DecodedSlice<BlockState> slice = view.sliceAt(position.getX(), position.getZ());
        if (slice == null || slice.blockAt(position.getX(), position.getY(), position.getZ()) != probe.sign().state()) {
            return false;
        }
        BlockEntitySample sample = slice.blockEntityAt(position.getX(), position.getY(), position.getZ());
        if (!expected.equals(sample)) {
            return false;
        }
        if (!sample.typeKey().equals("minecraft:sign")) {
            throw new IllegalStateException("Remote sign metadata retained an incorrect block-entity type");
        }
        try {
            CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(sample.nbt())));
            if (tag == null || tag.contains("id") || tag.contains("x") || tag.contains("y") || tag.contains("z")) {
                throw new IllegalStateException("Remote sign metadata is missing or contains source identity fields");
            }
            SignBlockEntity sign = new SignBlockEntity(position, probe.sign().state());
            sign.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, probe.registries(), tag));
            return sign.isWaxed() == waxed && MinecraftGameTestAccess.signLine(sign).equals(message);
        } catch (IOException error) {
            throw new IllegalStateException("Remote client sign metadata is invalid", error);
        }
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

    private record Probe(String peer, UUID portal, UUID entity, BlockPos sample, SignSample sign, ServerLevel level, HolderLookup.Provider registries,
                         RemoteViewCache<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> cache) {
    }

    private record SignSample(BlockPos position, BlockState state, BlockEntitySample initial) {
    }

    private record MetadataExpectation(ReplicationStreamKey stream, long sequence, int bulks, int resyncs, BlockEntitySample sample) {
    }

    private static final class Delivery {
        private final Queue<WireMessage> outgoing = new ConcurrentLinkedQueue<>();
        private final Queue<ChunkDiffBatch> batches = new ConcurrentLinkedQueue<>();
        private final Map<ReplicationStreamKey, Long> sequences = new ConcurrentHashMap<>();
        private final AtomicInteger bulks = new AtomicInteger();
        private final AtomicInteger diffs = new AtomicInteger();
        private final AtomicInteger resyncs = new AtomicInteger();
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }
}
