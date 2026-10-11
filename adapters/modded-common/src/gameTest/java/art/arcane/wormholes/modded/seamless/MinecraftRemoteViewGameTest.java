package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.modded.MinecraftGameTestAccess;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.ViewMutationAccess;
import art.arcane.wormholes.modded.mixin.SeamlessChunkMapAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MinecraftRemoteViewGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesGameTest");
    private static final int STAGE_TICKS = 400;
    private static final int DESTINATION_OFFSET = 4_096;
    private static final double MARKER_OFFSET = 32.0D;
    private static final int SETTLE_TICKS = 20;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private SeamlessGameFixture fixture;
    private SeamlessGameFixture leaver;
    private MinecraftPortal source;
    private MinecraftPortal destination;
    private ArmorStand stand;
    private BlockPos marker;
    private ChunkPos forced;
    private BlockState original;
    private BlockPos signPosition;
    private BlockState originalSign;
    private Holder<Biome> originalBiome;
    private Holder<Biome> expectedBiome;
    private BlockState expectedBlock;
    private int beforeRevision;
    private long beforeVersion;
    private Stage stage = Stage.LEAVE;
    private int handle;
    private int remaining = STAGE_TICKS;
    private int routedChunks;
    private int changedAt;
    private int settled;
    private int settledRevision = -1;
    private String delivery = "";

    private MinecraftRemoteViewGameTest(GameTestHelper helper, WormholesModRuntime runtime) {
        this.helper = helper;
        this.runtime = runtime;
    }

    public static CompletableFuture<Boolean> run(GameTestHelper helper, WormholesModRuntime runtime) {
        MinecraftRemoteViewGameTest test = new MinecraftRemoteViewGameTest(helper, runtime);
        try {
            test.start();
        } catch (Throwable failure) {
            test.finish(failure);
        }
        return test.result;
    }

    private void start() throws Exception {
        ServerLevel level = helper.getLevel();
        fixture = SeamlessGameFixture.connect(runtime, level, "seamless-view");
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 6));
        source = fixture.portal(level, base);
        destination = fixture.portal(level, base.offset(DESTINATION_OFFSET, 0, 0));
        helper.assertTrue(fixture.link(source, destination), "Remote view fixture did not link portals");
        marker = BlockPos.containing(destination.getOrigin().x() + MARKER_OFFSET, destination.getOrigin().y(), destination.getOrigin().z() + 3.0D);
        original = level.getBlockState(marker);
        level.setBlockAndUpdate(marker, Blocks.STONE.defaultBlockState());
        signPosition = marker.above();
        originalSign = level.getBlockState(signPosition);
        level.setBlockAndUpdate(signPosition, Blocks.OAK_SIGN.defaultBlockState());
        Vec3d arrival = destination.getOrigin();
        stand = new ArmorStand(level, arrival.x() + 1.0D, Math.floor(arrival.y()) - 1.0D, arrival.z() + 2.5D);
        stand.setNoGravity(true);
        helper.assertTrue(level.addFreshEntity(stand), "Remote view fixture could not spawn the destination marker entity");
        fixture.stand(source, -3.0D);
        fixture.negotiate();
        leaver = SeamlessGameFixture.connect(runtime, level, "seamless-leaver");
        leaver.stand(source, -3.0D);
        leaver.negotiate();
        helper.runAfterDelay(1, this::step);
    }

    private void step() {
        try {
            fixture.pump();
            if (leaver != null) {
                leaver.pump();
            }
            if (advance()) {
                return;
            }
            helper.assertTrue(--remaining > 0, "Remote view stage " + stage + " timed out; travel " + fixture.travel().size()
                + " messages, routed " + fixture.routed().size() + " packets, after change " + afterChange());
            helper.runAfterDelay(1, this::step);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private boolean advance() throws InterruptedException {
        switch (stage) {
            case LEAVE -> {
                if (runtime.remoteRoutes().routes(leaver.player().getUUID()).isEmpty()) {
                    return false;
                }
                disconnectFromNetwork(leaver);
                next(Stage.LEFT);
            }
            case LEFT -> {
                if (!runtime.remoteRoutes().routes(leaver.player().getUUID()).isEmpty()) {
                    return false;
                }
                leaver.close();
                leaver = null;
                next(Stage.STREAM);
            }
            case STREAM -> {
                TravelMessage.RemoteLevelOpen open = opened();
                if (open == null || !streamed(open) || !paired(open.levelHandle(), stand.getId())) {
                    return false;
                }
                handle = open.levelHandle();
                marker = BlockPos.containing(destination.getOrigin().x() + MARKER_OFFSET, destination.getOrigin().y(), destination.getOrigin().z() + 3.0D);
                next(Stage.DELIVERED);
            }
            case DELIVERED -> {
                RemoteRoute route = runtime.remoteRoutes().route(fixture.player().getUUID(), source.getId());
                ChunkPos column = ChunkPos.containing(marker);
                if (route == null || !route.stream().delivered(column.pack())) {
                    return false;
                }
                helper.assertTrue(!ticking(column), "The marker column was already ticking when it was delivered");
                next(Stage.NON_TICKING_SETTLED);
            }
            case NON_TICKING_SETTLED -> {
                if (!settled(false)) {
                    return false;
                }
                beginMutation(Blocks.GOLD_BLOCK.defaultBlockState());
                markerChunk().setBlockState(marker, expectedBlock, 0);
                helper.assertTrue(helper.getLevel().getBlockState(marker) == expectedBlock, "The non-ticking LevelChunk write did not apply");
                next(Stage.NON_TICKING_CHUNK);
            }
            case NON_TICKING_CHUNK -> {
                if (!nativeBlock(false)) {
                    return false;
                }
                assertNoOpWrites();
                beginMutation(Blocks.DIAMOND_BLOCK.defaultBlockState());
                helper.assertTrue(helper.getLevel().setBlock(marker, expectedBlock, 2), "The non-ticking Level write did not apply");
                next(Stage.NON_TICKING_LEVEL);
            }
            case NON_TICKING_LEVEL -> {
                if (!nativeBlock(false)) {
                    return false;
                }
                beginMutation(Blocks.EMERALD_BLOCK.defaultBlockState());
                LevelChunk chunk = markerChunk();
                int sectionIndex = chunk.getSectionIndex(marker.getY());
                LevelChunkSection replacement = chunk.getSection(sectionIndex).copy();
                replacement.setBlockState(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15, expectedBlock);
                chunk.getSections()[sectionIndex] = replacement;
                chunk.markUnsaved();
                next(Stage.NON_TICKING_REPLACEMENT);
            }
            case NON_TICKING_REPLACEMENT -> {
                if (!nativeBlock(false)) {
                    return false;
                }
                ChunkPos column = ChunkPos.containing(marker);
                forced = column;
                helper.getLevel().setChunkForced(column.x(), column.z(), true);
                next(Stage.TICKING);
            }
            case TICKING -> {
                if (!settled(true)) {
                    return false;
                }
                changedAt = fixture.routed().size();
                expectedBlock = Blocks.GOLD_BLOCK.defaultBlockState();
                helper.getLevel().setBlockAndUpdate(marker, expectedBlock);
                next(Stage.BLOCK);
            }
            case BLOCK -> {
                if (!updated(marker)) {
                    return false;
                }
                beginMutation(Blocks.DIAMOND_BLOCK.defaultBlockState());
                markerSection().setBlockState(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15, expectedBlock, false);
                next(Stage.DIRECT_SECTION);
            }
            case DIRECT_SECTION -> {
                if (!nativeBlock(true)) {
                    return false;
                }
                assertFailedReads();
                beginMutation(Blocks.EMERALD_BLOCK.defaultBlockState());
                markerSection().getStates().set(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15, expectedBlock);
                next(Stage.DIRECT_PALETTE);
            }
            case DIRECT_PALETTE -> {
                if (!nativeBlock(true)) {
                    return false;
                }
                beginMutation(Blocks.GOLD_BLOCK.defaultBlockState());
                assertPartialFailedRead();
                next(Stage.PARTIAL_SECTION_DATA);
            }
            case PARTIAL_SECTION_DATA -> {
                if (!nativeBlock(true)) {
                    return false;
                }
                beginMutation(Blocks.LAPIS_BLOCK.defaultBlockState());
                markerSection().getStates().set(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15, expectedBlock);
                next(Stage.RECOVERED_PALETTE);
            }
            case RECOVERED_PALETTE -> {
                if (!nativeBlock(true)) {
                    return false;
                }
                beginMutation(Blocks.GOLD_BLOCK.defaultBlockState());
                replaceSectionData();
                next(Stage.SECTION_DATA);
            }
            case SECTION_DATA -> {
                if (!nativeBlock(true)) {
                    return false;
                }
                beginMutation(expectedBlock);
                LevelChunkSection section = markerSection();
                originalBiome = section.getNoiseBiome((marker.getX() & 15) >> 2, (marker.getY() & 15) >> 2, (marker.getZ() & 15) >> 2);
                expectedBiome = helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME)
                    .getOrThrow(originalBiome.is(Biomes.BADLANDS) ? Biomes.SWAMP : Biomes.BADLANDS);
                replaceBiome(expectedBiome);
                helper.getLevel().getChunkSource().chunkMap.resendBiomesForChunks(List.of(markerChunk()));
                next(Stage.BIOME);
            }
            case BIOME -> {
                ClientboundLevelChunkWithLightPacket packet = nativeChunk();
                if (packet == null) {
                    return false;
                }
                LevelChunkSection decoded = decodedSection(packet);
                if (!decoded.getNoiseBiome((marker.getX() & 15) >> 2, (marker.getY() & 15) >> 2, (marker.getZ() & 15) >> 2).equals(expectedBiome)) {
                    return false;
                }
                assertAppliedDelivery(true);
                beginMutation(expectedBlock);
                helper.assertTrue(helper.getLevel().getBlockEntity(signPosition) instanceof SignBlockEntity, "The metadata marker is not a sign");
                SignBlockEntity sign = (SignBlockEntity) helper.getLevel().getBlockEntity(signPosition);
                CompoundTag data = sign.saveCustomOnly(helper.getLevel().registryAccess());
                data.putBoolean("is_waxed", true);
                BlockState before = sign.getBlockState();
                sign.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), data));
                sign.setChanged();
                helper.assertTrue(sign.getBlockState() == before && sign.isWaxed(), "The metadata edit changed block state or did not apply");
                next(Stage.METADATA);
            }
            case METADATA -> {
                ClientboundLevelChunkWithLightPacket packet = nativeChunk();
                if (packet == null) {
                    return false;
                }
                AtomicBoolean metadata = new AtomicBoolean();
                MinecraftGameTestAccess.blockEntityTags(packet, (position, type, tag) -> {
                    if (position.equals(signPosition) && tag != null && tag.getBooleanOr("is_waxed", false)) {
                        metadata.set(true);
                    }
                });
                if (!metadata.get()) {
                    return false;
                }
                assertAppliedDelivery(true);
                stand.discard();
                next(Stage.REMOVED);
            }
            case REMOVED -> {
                if (!removed(stand.getId())) {
                    return false;
                }
                fixture.shift(0.0D, 2_000.0D);
                next(Stage.CLOSE);
            }
            case CLOSE -> {
                if (!closed(handle)) {
                    return false;
                }
                helper.assertTrue(!vanillaChunk(), "The destination chunk leaked into the vanilla chunk stream");
                LOGGER.info("WORMHOLES_GAME_TEST_PASS remote_view handle={} routed_chunks={} entity={} block={} delivery={} close=true disconnect=network",
                    handle, routedChunks, stand.getId(), marker.toShortString(), delivery);
                finish(null);
                return true;
            }
        }
        return false;
    }

    private void beginMutation(BlockState expected) {
        RemoteRoute route = runtime.remoteRoutes().route(fixture.player().getUUID(), source.getId());
        helper.assertTrue(route != null && route.stream().delivered(ChunkPos.containing(marker).pack()), "The marker column was not resident before editing");
        beforeRevision = route.stream().revision(ChunkPos.containing(marker).pack());
        beforeVersion = runtime.projections().changes().currentVersion();
        changedAt = fixture.routed().size();
        expectedBlock = expected;
    }

    private void assertAppliedDelivery(boolean expectedTicking) {
        ChunkPos column = ChunkPos.containing(marker);
        RemoteRoute route = runtime.remoteRoutes().route(fixture.player().getUUID(), source.getId());
        helper.assertTrue(ticking(column) == expectedTicking, "The mutation changed the marker column's simulation status");
        helper.assertTrue(runtime.projections().changes().currentVersion() > beforeVersion, "The applied mutation did not stamp the canonical world");
        helper.assertTrue(route != null && route.stream().delivered(column.pack()) && route.stream().revision(column.pack()) > beforeRevision,
            "The applied mutation did not advance resident column delivery");
        helper.assertTrue(fixture.player().level() == helper.getLevel(), "The observer left the source level during mutation delivery");
    }

    private boolean nativeBlock(boolean expectedTicking) {
        ClientboundLevelChunkWithLightPacket packet = nativeChunk();
        if (packet == null || decodedSection(packet).getBlockState(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15) != expectedBlock) {
            return false;
        }
        helper.assertTrue(helper.getLevel().getBlockState(marker) == expectedBlock, "The decoded resident marker disagreed with the authoritative world");
        assertAppliedDelivery(expectedTicking);
        return true;
    }

    private ClientboundLevelChunkWithLightPacket nativeChunk() {
        List<SeamlessGameFixture.Routed> packets = fixture.routed();
        for (int index = packets.size() - 1; index >= changedAt; index--) {
            SeamlessGameFixture.Routed routed = packets.get(index);
            if (routed.handle() == handle && routed.packet() instanceof ClientboundLevelChunkWithLightPacket packet
                && MinecraftGameTestAccess.chunkX(packet) == marker.getX() >> 4 && MinecraftGameTestAccess.chunkZ(packet) == marker.getZ() >> 4) {
                return packet;
            }
        }
        return null;
    }

    private LevelChunkSection decodedSection(ClientboundLevelChunkWithLightPacket packet) {
        FriendlyByteBuf buffer = MinecraftGameTestAccess.chunkData(packet).getReadBuffer();
        try {
            int wanted = helper.getLevel().getSectionIndex(marker.getY());
            for (int index = 0; index <= wanted; index++) {
                LevelChunkSection section = new LevelChunkSection(helper.getLevel().palettedContainerFactory());
                section.read(buffer);
                if (index == wanted) {
                    return section;
                }
            }
        } finally {
            buffer.release();
        }
        throw new IllegalStateException("The marker section was not decoded");
    }

    private LevelChunk markerChunk() {
        LevelChunk chunk = helper.getLevel().getChunkSource().getChunkNow(marker.getX() >> 4, marker.getZ() >> 4);
        helper.assertTrue(chunk != null, "The marker chunk was not kept resident by its view lease");
        return chunk;
    }

    private LevelChunkSection markerSection() {
        LevelChunk chunk = markerChunk();
        return chunk.getSection(chunk.getSectionIndex(marker.getY()));
    }

    private void assertNoOpWrites() {
        long version = runtime.projections().changes().currentVersion();
        LevelChunk chunk = markerChunk();
        LevelChunkSection section = markerSection();
        chunk.setBlockState(marker, expectedBlock, 0);
        section.setBlockState(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15, expectedBlock);
        section.getStates().set(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15, expectedBlock);
        int sectionIndex = chunk.getSectionIndex(marker.getY());
        chunk.getSections()[sectionIndex] = section.copy();
        chunk.markUnsaved();
        helper.assertTrue(runtime.projections().changes().currentVersion() == version, "No-op block/section/palette writes stamped the world");
        section.setBlockState(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15, Blocks.LAPIS_BLOCK.defaultBlockState());
        helper.assertTrue(runtime.projections().changes().currentVersion() == version, "A retired section dirtied its former chunk");
        ProtoChunk generating = new ProtoChunk(ChunkPos.containing(marker), UpgradeData.EMPTY, helper.getLevel(),
            helper.getLevel().palettedContainerFactory(), null);
        generating.setBlockState(marker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 0);
        generating.getSection(generating.getSectionIndex(marker.getY())).getStates()
            .set(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15, Blocks.EMERALD_BLOCK.defaultBlockState());
        helper.assertTrue(runtime.projections().changes().currentVersion() == version, "ProtoChunk generation dirtied a live resident world");
        LevelChunk retired = new LevelChunk(helper.getLevel(), ChunkPos.containing(marker));
        retired.setLoaded(true);
        retired.setBlockState(marker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 0);
        retired.getSection(retired.getSectionIndex(marker.getY())).setBlockState(marker.getX() & 15, marker.getY() & 15,
            marker.getZ() & 15, Blocks.EMERALD_BLOCK.defaultBlockState());
        retired.setLoaded(false);
        helper.assertTrue(runtime.projections().changes().currentVersion() == version, "A replaced chunk instance dirtied its live replacement");
    }

    private void replaceSectionData() {
        LevelChunkSection replacement = markerSection().copy();
        replacement.setBlockState(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15, expectedBlock);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            replacement.write(buffer);
            markerSection().read(buffer);
        } finally {
            buffer.release();
        }
    }

    private void assertFailedReads() {
        LevelChunkSection section = markerSection();
        LevelChunkSection before = section.copy();
        long version = runtime.projections().changes().currentVersion();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            boolean failed = false;
            try {
                section.read(buffer);
            } catch (IndexOutOfBoundsException failure) {
                failed = true;
                helper.assertTrue(failure.getSuppressed().length == 0, "The failed section read also failed cleanup");
            }
            helper.assertTrue(failed, "An empty section replacement did not fail");
            helper.assertTrue(((ViewMutationAccess.Section) section).wormholesPaletteActive(), "The failed section replacement left palette tracking suppressed");
            helper.assertTrue(ViewMutationAccess.sameContents(before, section), "The empty section replacement changed contents");
            helper.assertTrue(runtime.projections().changes().currentVersion() == version, "The failed unchanged section replacement stamped the world");
            failed = false;
            try {
                section.readBiomes(buffer);
            } catch (IndexOutOfBoundsException failure) {
                failed = true;
                helper.assertTrue(failure.getSuppressed().length == 0, "The failed biome read also failed cleanup");
            }
            helper.assertTrue(failed, "An empty biome replacement did not fail");
            helper.assertTrue(((ViewMutationAccess.Section) section).wormholesPaletteActive(), "The failed biome replacement left palette tracking suppressed");
            helper.assertTrue(ViewMutationAccess.sameContents(before, section), "The empty biome replacement changed contents");
            helper.assertTrue(runtime.projections().changes().currentVersion() == version, "The failed unchanged biome replacement stamped the world");
        } finally {
            buffer.release();
        }
    }

    private void assertPartialFailedRead() {
        LevelChunkSection section = markerSection();
        LevelChunkSection replacement = section.copy();
        replacement.setBlockState(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15, expectedBlock);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            replacement.write(buffer);
            buffer.writerIndex(4 + replacement.getStates().getSerializedSize());
            boolean failed = false;
            try {
                section.read(buffer);
            } catch (IndexOutOfBoundsException failure) {
                failed = true;
                helper.assertTrue(failure.getSuppressed().length == 0, "The partially applied section read also failed cleanup");
            }
            helper.assertTrue(failed, "A section replacement missing its biome data did not fail");
            helper.assertTrue(section.getBlockState(marker.getX() & 15, marker.getY() & 15, marker.getZ() & 15) == expectedBlock,
                "The failing section replacement did not apply its complete block palette");
            helper.assertTrue(((ViewMutationAccess.Section) section).wormholesPaletteActive(), "The partially applied replacement left palette tracking suppressed");
            helper.assertTrue(runtime.projections().changes().currentVersion() > beforeVersion, "The partially applied replacement did not stamp the world");
        } finally {
            buffer.release();
        }
    }

    private void replaceBiome(Holder<Biome> biome) {
        PalettedContainer<Holder<Biome>> biomes = markerSection().getBiomes().copy();
        biomes.set((marker.getX() & 15) >> 2, (marker.getY() & 15) >> 2, (marker.getZ() & 15) >> 2, biome);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            biomes.write(buffer);
            markerSection().readBiomes(buffer);
        } finally {
            buffer.release();
        }
    }

    private boolean settled(boolean expectedTicking) {
        RemoteRoute route = runtime.remoteRoutes().route(fixture.player().getUUID(), source.getId());
        ChunkPos column = ChunkPos.containing(marker);
        long key = column.pack();
        if (route == null || ticking(column) != expectedTicking || !route.stream().delivered(key)) {
            settled = 0;
            settledRevision = -1;
            return false;
        }
        int revision = route.stream().revision(key);
        settled = revision == settledRevision ? settled + 1 : 0;
        settledRevision = revision;
        return settled >= SETTLE_TICKS;
    }

    private boolean ticking(ChunkPos column) {
        ChunkHolder holder = ((SeamlessChunkMapAccess) helper.getLevel().getChunkSource().chunkMap).wormholesVisibleChunk(column.pack());
        return holder != null && holder.getTickingChunk() != null;
    }

    private boolean streamed(TravelMessage.RemoteLevelOpen open) {
        boolean centered = false;
        routedChunks = 0;
        boolean arrival = false;
        for (SeamlessGameFixture.Routed routed : fixture.routed()) {
            if (routed.handle() != open.levelHandle()) {
                continue;
            }
            if (routed.packet() instanceof ClientboundSetChunkCacheCenterPacket center) {
                centered |= center.getX() == open.center().x() && center.getZ() == open.center().z();
            } else if (routed.packet() instanceof ClientboundLevelChunkWithLightPacket chunk) {
                helper.assertTrue(centered, "A routed chunk arrived before the resident level cache centre");
                routedChunks++;
                arrival |= MinecraftGameTestAccess.chunkX(chunk) == open.center().x() && MinecraftGameTestAccess.chunkZ(chunk) == open.center().z();
            }
        }
        return centered && arrival;
    }

    private TravelMessage.RemoteLevelOpen opened() {
        int x = (int) Math.floor(destination.getOrigin().x()) >> 4;
        int z = (int) Math.floor(destination.getOrigin().z()) >> 4;
        for (TravelMessage message : fixture.travel()) {
            if (message instanceof TravelMessage.RemoteLevelOpen open && open.center().x() == x && open.center().z() == z) {
                return open;
            }
        }
        return null;
    }

    private boolean paired(int levelHandle, int entity) {
        for (SeamlessGameFixture.Routed routed : fixture.routed()) {
            if (routed.handle() == levelHandle && routed.packet() instanceof ClientboundAddEntityPacket add && add.getId() == entity) {
                return true;
            }
        }
        return false;
    }

    private boolean updated(BlockPos position) {
        AtomicBoolean found = new AtomicBoolean();
        List<SeamlessGameFixture.Routed> routed = fixture.routed();
        for (int index = changedAt; index < routed.size(); index++) {
            SeamlessGameFixture.Routed packet = routed.get(index);
            if (packet.handle() != handle) {
                continue;
            }
            if (packet.packet() instanceof ClientboundBlockUpdatePacket update && update.getPos().equals(position)) {
                helper.assertTrue(update.getBlockState() == expectedBlock, "The live block packet contained the old state");
                delivery = "block_update";
                return true;
            }
            if (packet.packet() instanceof ClientboundSectionBlocksUpdatePacket section) {
                section.runUpdates((changed, state) -> found.compareAndSet(false, changed.equals(position) && state == expectedBlock));
                if (found.get()) {
                    delivery = "section_update";
                    return true;
                }
            }
            if (packet.packet() instanceof ClientboundLevelChunkWithLightPacket chunk && MinecraftGameTestAccess.chunkX(chunk) == position.getX() >> 4
                && MinecraftGameTestAccess.chunkZ(chunk) == position.getZ() >> 4) {
                helper.fail("The block change in a delivered ticking column was re-sent as a whole chunk instead of a live update");
            }
        }
        return false;
    }

    private String afterChange() {
        List<SeamlessGameFixture.Routed> routed = fixture.routed();
        StringBuilder detail = new StringBuilder();
        for (int index = changedAt; index < routed.size(); index++) {
            SeamlessGameFixture.Routed packet = routed.get(index);
            detail.append(packet.handle()).append(':').append(packet.packet().type().id().getPath());
            if (packet.packet() instanceof ClientboundBlockUpdatePacket update) {
                detail.append('@').append(update.getPos().toShortString()).append('=').append(update.getBlockState());
            }
            detail.append(' ');
        }
        for (TravelMessage message : fixture.travel()) {
            if (message instanceof TravelMessage.RemoteLevelClose close) {
                detail.append("close:").append(close.levelHandle()).append(' ');
            }
        }
        detail.append(" marker ").append(marker == null ? "none" : marker.toShortString());
        if (marker != null) {
            RemoteRoute route = runtime.remoteRoutes().route(fixture.player().getUUID(), source.getId());
            long key = ChunkPos.containing(marker).pack();
            detail.append(" state=").append(helper.getLevel().getBlockState(marker)).append(" ticking=").append(ticking(ChunkPos.containing(marker)));
            if (route != null) {
                detail.append(" delivered=").append(route.stream().delivered(key)).append(" live=").append(route.stream().live(key))
                    .append(" revision=").append(route.stream().revision(key)).append(" window=").append(route.window().centerX()).append(',')
                    .append(route.window().centerZ()).append('r').append(route.window().radius());
            }
        }
        return detail.toString().trim();
    }

    private boolean closed(int levelHandle) {
        for (TravelMessage message : fixture.travel()) {
            if (message instanceof TravelMessage.RemoteLevelClose close && close.levelHandle() == levelHandle) {
                return true;
            }
        }
        return false;
    }

    private boolean removed(int entity) {
        for (SeamlessGameFixture.Routed routed : fixture.routed()) {
            if (routed.handle() == handle && routed.packet() instanceof ClientboundRemoveEntitiesPacket remove && MinecraftGameTestAccess.removed(remove).contains(entity)) {
                return true;
            }
        }
        return false;
    }

    private boolean vanillaChunk() {
        int x = (int) Math.floor(destination.getOrigin().x()) >> 4;
        int z = (int) Math.floor(destination.getOrigin().z()) >> 4;
        for (Object packet : fixture.vanilla()) {
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk && MinecraftGameTestAccess.chunkX(chunk) == x && MinecraftGameTestAccess.chunkZ(chunk) == z) {
                return true;
            }
        }
        return false;
    }

    private void disconnectFromNetwork(SeamlessGameFixture leaving) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread network = new Thread(() -> {
            try {
                runtime.playerDisconnected(leaving.player());
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "seamless-test-network");
        network.start();
        network.join();
        if (failure.get() != null) {
            throw new IllegalStateException("A disconnect reported from the network thread failed", failure.get());
        }
    }

    private void next(Stage value) {
        stage = value;
        remaining = STAGE_TICKS;
    }

    private void finish(Throwable failure) {
        try {
            if (originalBiome != null) {
                replaceBiome(originalBiome);
            }
            if (original != null) {
                helper.getLevel().setBlockAndUpdate(marker, original);
            }
            if (originalSign != null) {
                helper.getLevel().setBlockAndUpdate(signPosition, originalSign);
            }
            if (forced != null) {
                helper.getLevel().setChunkForced(forced.x(), forced.z(), false);
            }
            if (stand != null && !stand.isRemoved()) {
                stand.remove(Entity.RemovalReason.DISCARDED);
            }
            if (leaver != null) {
                leaver.close();
            }
            if (fixture != null) {
                fixture.close();
            }
        } catch (Throwable cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        if (failure == null) {
            result.complete(true);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private enum Stage {
        LEAVE, LEFT, STREAM, DELIVERED, NON_TICKING_SETTLED, NON_TICKING_CHUNK, NON_TICKING_LEVEL,
        NON_TICKING_REPLACEMENT, TICKING, BLOCK, DIRECT_SECTION, DIRECT_PALETTE, PARTIAL_SECTION_DATA, RECOVERED_PALETTE,
        SECTION_DATA, BIOME, METADATA, REMOVED, CLOSE
    }
}
