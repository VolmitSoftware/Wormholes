package art.arcane.wormholes.modded;

import art.arcane.wormholes.modded.clientview.MinecraftApertureShapeGameTest;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewGameTest;
import art.arcane.wormholes.modded.seamless.MinecraftRemoteViewGameTest;
import art.arcane.wormholes.modded.seamless.MinecraftSeamlessValidationGameTest;
import art.arcane.wormholes.portal.PortalStateCodec;
import art.arcane.wormholes.network.NativeHandoffProbe;
import art.arcane.wormholes.network.NativeGatewayPolicyProbe;
import art.arcane.wormholes.network.NativeEntityTransferProbe;
import art.arcane.wormholes.portal.PortalType;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class WormholesGameTests {
    public static WormholesModRuntime RUNTIME = new WormholesModRuntime();
    public static final ResourceLocation PORTAL_RUNTIME = new ResourceLocation("wormholes", "portal_runtime");
    public static final ResourceLocation INTERACTION_RUNTIME = new ResourceLocation("wormholes", "interaction_runtime");
    public static final ResourceLocation ENTITY_PROJECTION_RUNTIME = new ResourceLocation("wormholes", "entity_projection_runtime");
    public static final ResourceLocation RTP_RUNTIME = new ResourceLocation("wormholes", "rtp_runtime");
    public static final ResourceLocation EFFECTS_RUNTIME = new ResourceLocation("wormholes", "effects_runtime");
    public static final ResourceLocation LOOK_LABEL_RUNTIME = new ResourceLocation("wormholes", "look_label_runtime");
    public static final ResourceLocation OPS_RUNTIME = new ResourceLocation("wormholes", "ops_runtime");
    public static final ResourceLocation RULES_RUNTIME = new ResourceLocation("wormholes", "rules_runtime");
    public static final ResourceLocation COSTS_RUNTIME = new ResourceLocation("wormholes", "costs_runtime");
    public static final ResourceLocation HANDOFF_RUNTIME = new ResourceLocation("wormholes", "handoff_runtime");
    public static final ResourceLocation NEXUS_RUNTIME = new ResourceLocation("wormholes", "nexus_runtime");
    public static final ResourceLocation ENTITY_TRANSFERS_RUNTIME = new ResourceLocation("wormholes", "entity_transfers_runtime");
    public static final ResourceLocation RECIPE_BOOK_RUNTIME = new ResourceLocation("wormholes", "recipe_book_runtime");
    public static final ResourceLocation MENU_PARITY_RUNTIME = new ResourceLocation("wormholes", "menu_parity_runtime");
    public static final ResourceLocation NATIVE_MENU_PROTOCOL_RUNTIME = new ResourceLocation("wormholes", "native_menu_protocol_runtime");
    public static final ResourceLocation LANGUAGE_RUNTIME = new ResourceLocation("wormholes", "language_runtime");
    public static final ResourceLocation OCCLUSION_SKIN_RUNTIME = new ResourceLocation("wormholes", "occlusion_skin_runtime");
    public static final ResourceLocation PROJECTION_DIRT_RUNTIME = new ResourceLocation("wormholes", "projection_dirt_runtime");
    public static final ResourceLocation PROJECTION_GAZE_RUNTIME = new ResourceLocation("wormholes", "projection_gaze_runtime");
    public static final ResourceLocation PROJECTION_RETARGET_RUNTIME = new ResourceLocation("wormholes", "projection_retarget_runtime");
    public static final ResourceLocation PROJECTION_SECTION_CACHE_RUNTIME = new ResourceLocation("wormholes", "projection_section_cache_runtime");
    public static final ResourceLocation PROJECTION_PLATE_CAPTURE_RUNTIME = new ResourceLocation("wormholes", "projection_plate_capture_runtime");
    public static final ResourceLocation CLIENTVIEW_NEGOTIATION = new ResourceLocation("wormholes", "clientview_negotiation");
    public static final ResourceLocation CLIENTVIEW_STREAM = new ResourceLocation("wormholes", "clientview_stream");
    public static final ResourceLocation SEAMLESS_VALIDATION = new ResourceLocation("wormholes", "seamless_validation");
    public static final ResourceLocation REMOTE_VIEW = new ResourceLocation("wormholes", "remote_view");
    public static final ResourceLocation APERTURE_SHAPE_RUNTIME = new ResourceLocation("wormholes", "aperture_shape_runtime");
    public static final ResourceLocation LOOK_CONTINUITY_RUNTIME = new ResourceLocation("wormholes", "look_continuity_runtime");
    private static final Set<CompletableFuture<?>> REPORTED_FAILURES = new HashSet<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesGameTest");

    private WormholesGameTests() {
    }

    public static void start(MinecraftServer server) {
        RUNTIME.start(server);
        attach(server, RUNTIME);
    }

    public static void attach(MinecraftServer server, WormholesModRuntime runtime) {
        if (!runtime.running() || runtime.server() != server) {
            throw new IllegalStateException("GameTests require the running Wormholes server runtime");
        }
        RUNTIME = runtime;
    }

    public static void interactionRuntime(GameTestHelper helper) {
        CompletableFuture<Boolean> result = new MinecraftInteractionGameTest(helper).run();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, result, "production interaction"),
            "Production interaction did not complete")).thenSucceed();
    }

    public static void entityProjectionRuntime(GameTestHelper helper) {
        CompletableFuture<Boolean> result = MinecraftEntityProjectionGameTest.standalone(helper, RUNTIME);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, result, "entity projection"),
            "Native entity projection did not complete")).thenSucceed();
    }

    public static void rtpRuntime(GameTestHelper helper) {
        MinecraftRtpGameTest.run(helper);
    }

    public static void effectsRuntime(GameTestHelper helper) {
        MinecraftPortalEffectsGameTest.run(helper);
    }

    public static void recipeBookRuntime(GameTestHelper helper) {
        MinecraftRecipeBookGameTest.run(helper);
    }

    public static void lookLabelRuntime(GameTestHelper helper) {
        MinecraftLookLabelGameTest.run(helper);
    }

    public static void apertureShapeRuntime(GameTestHelper helper) {
        MinecraftApertureShapeGameTest.run(helper);
    }

    public static void lookContinuityRuntime(GameTestHelper helper) {
        MinecraftGravityPoseGameTest.verify(helper);
        MinecraftLookContinuityGameTest.run(helper);
    }

    public static void opsRuntime(GameTestHelper helper) {
        MinecraftOperationsGameTest.run(helper);
    }

    public static void rulesRuntime(GameTestHelper helper) {
        MinecraftRulesGameTest.run(helper);
    }

    public static void menuParityRuntime(GameTestHelper helper) {
        MinecraftMenuParityGameTest.run(helper);
    }

    public static void nativeMenuProtocolRuntime(GameTestHelper helper) {
        MinecraftNativeMenuProtocolGameTest.run(helper);
    }

    public static void languageRuntime(GameTestHelper helper) {
        MinecraftLanguageGameTest.run(helper);
    }

    public static void occlusionSkinRuntime(GameTestHelper helper) {
        MinecraftOcclusionSkinGameTest.run(helper);
    }

    public static void projectionDirtRuntime(GameTestHelper helper) {
        CompletableFuture<Boolean> result = MinecraftProjectionScheduleGameTest.dirt(helper, RUNTIME);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, result, "projection dirt"),
            "Projection dirt checks did not complete")).thenSucceed();
    }

    public static void projectionGazeRuntime(GameTestHelper helper) {
        CompletableFuture<Boolean> result = MinecraftProjectionScheduleGameTest.gaze(helper, RUNTIME);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, result, "projection gaze"),
            "Projection gaze scheduling did not complete")).thenSucceed();
    }

    public static void projectionRetargetRuntime(GameTestHelper helper) {
        CompletableFuture<Boolean> result = MinecraftProjectionScheduleGameTest.retarget(helper, RUNTIME);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, result, "projection retarget"),
            "Projection retarget did not complete")).thenSucceed();
    }

    public static void projectionSectionCacheRuntime(GameTestHelper helper) {
        MinecraftSectionCacheGameTest.run(helper);
    }

    public static void projectionPlateCaptureRuntime(GameTestHelper helper) {
        MinecraftPlateCaptureGameTest.run(helper);
    }

    public static void clientViewNegotiation(GameTestHelper helper) {
        CompletableFuture<Boolean> result = MinecraftClientViewGameTest.negotiation(helper, RUNTIME);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, result, "clientview negotiation"),
            "ClientView negotiation did not complete")).thenSucceed();
    }

    public static void clientViewStream(GameTestHelper helper) {
        CompletableFuture<Boolean> result = MinecraftClientViewGameTest.stream(helper, RUNTIME);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, result, "clientview stream"),
            "ClientView stream did not complete")).thenSucceed();
    }

    public static void seamlessValidation(GameTestHelper helper) {
        CompletableFuture<Boolean> result = MinecraftSeamlessValidationGameTest.run(helper, RUNTIME);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, result, "seamless validation"),
            "Seamless validation did not complete")).thenSucceed();
    }

    public static void remoteView(GameTestHelper helper) {
        CompletableFuture<Boolean> result = MinecraftRemoteViewGameTest.run(helper, RUNTIME);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, result, "remote view"),
            "Remote view did not complete")).thenSucceed();
    }

    public static void costsRuntime(GameTestHelper helper) {
        MinecraftItemExchangeGameTest.run(helper);
        MinecraftCostGameTest.run(helper);
    }

    public static void nexusRuntime(GameTestHelper helper) {
        CompletableFuture<Boolean> result = MinecraftNexusGameTest.run(helper, RUNTIME)
            .thenCompose(ignored -> NativeGatewayPolicyProbe.run(helper, RUNTIME));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, result, "nexus"),
            "Native Nexus did not complete")).thenSucceed();
    }

    public static void entityTransfersRuntime(GameTestHelper helper) {
        CompletableFuture<Boolean> transfers = NativeEntityTransferProbe.run(helper, RUNTIME);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, transfers, "entity transfers"),
            "Entity transfers did not complete")).thenSucceed();
    }

    public static void handoffRuntime(GameTestHelper helper) {
        CompletableFuture<Boolean> handoff = NativeHandoffProbe.run(helper, RUNTIME);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, handoff, "handoff"),
            "Player handoff did not complete")).thenSucceed();
    }

    public static void portalRuntime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer actor = MinecraftGameTestAccess.mockServerPlayer(helper, GameType.CREATIVE);
        MinecraftPortal source = RUNTIME.portals().create(actor.getUUID(), level, cells(helper, 2), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = RUNTIME.portals().create(actor.getUUID(), level, cells(helper, 14), PortalType.PORTAL, new Vec3(0, 0, -1));
        helper.assertTrue(RUNTIME.portals().link(actor, source.getId(), destination.getId()), "Portal link rejected owner");
        helper.assertTrue(destination.getId().equals(source.getDestinationId()), "Portal destination did not change");
        helper.assertTrue(RUNTIME.portals().at(level, helper.absolutePos(new BlockPos(2, 2, 4))) == source,
            "Aperture lookup missed constructed portal");
        BlockPos sampled = helper.absolutePos(new BlockPos(8, 2, 8));
        level.setBlockAndUpdate(sampled, Blocks.GOLD_BLOCK.defaultBlockState());
        try (MinecraftProjectionWorldView view = MinecraftProjectionWorldView.uncached(RUNTIME, level)) {
            helper.assertTrue(view.sampleBlockData(sampled.getX(), sampled.getY(), sampled.getZ()).is(Blocks.GOLD_BLOCK),
                "Projection world view did not read the live block");
            helper.assertTrue(view.sampleBlockData(sampled.getX(), level.getMaxBuildHeight() - 1, sampled.getZ()) == null,
                "Projection world view sampled outside build height");
        }
        Short2ObjectMap<BlockState> changes = new Short2ObjectOpenHashMap<>();
        short local = (short) (((sampled.getX() & 15) << 8) | ((sampled.getZ() & 15) << 4) | (sampled.getY() & 15));
        changes.put(local, Blocks.DIAMOND_BLOCK.defaultBlockState());
        List<BlockPos> captured = new ArrayList<>();
        MinecraftProjectionPackets.sectionPacket(SectionPos.asLong(sampled.getX() >> 4, sampled.getY() >> 4, sampled.getZ() >> 4), changes)
            .runUpdates((position, state) -> {
                helper.assertTrue(state.is(Blocks.DIAMOND_BLOCK), "Projection packet changed block state");
                captured.add(position.immutable());
            });
        helper.assertTrue(captured.equals(List.of(sampled)), "Projection packet changed world coordinates");
        helper.assertTrue(level.getBlockState(sampled).is(Blocks.GOLD_BLOCK), "Projection packet mutated the real world");
        ArmorStand traveler = helper.spawn(MinecraftEntityTypes.ARMOR_STAND, new Vec3(3, 2, 3.5));
        traveler.setNoGravity(true);
        helper.runAfterDelay(4, () -> {
            Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
            traveler.setPos(start);
            traveler.xo = start.x;
            traveler.yo = start.y;
            traveler.zo = start.z;
            traveler.setDeltaMovement(0, 0, 0.5);
            traveler.setPos(start.x, start.y, start.z + 1.5);
        });
        Path file = PortalStateCodec.file(RUNTIME.stores().data().resolve("portals"), source.getId());
        CompletableFuture<Boolean> persisted = CompletableFuture.supplyAsync(() -> persisted(file, destination.getId()));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(traveler.getX() > helper.absolutePos(new BlockPos(10, 0, 0)).getX(), "Traveler has not reached the destination aperture");
            helper.assertTrue(completed(helper, persisted, "Portal persistence"), "Portal link was not persisted");
        }).thenExecute(() -> {
            helper.assertTrue(RUNTIME.portals().link(actor, source.getId(), null), "Portal unlink rejected owner");
            helper.assertTrue(source.getDestinationId() == null, "Portal remained linked");
            helper.assertTrue(RUNTIME.portals().remove(actor, source.getId()), "Portal removal rejected owner");
            helper.assertTrue(RUNTIME.portals().remove(actor, destination.getId()), "Destination removal rejected owner");
            traveler.discard();
            LOGGER.info("WORMHOLES_GAME_TEST_PASS portal_runtime creation link traversal persistence world_view packet_codec unlink removal");
            CompletableFuture<Boolean> menus = MinecraftPortalMenuGameTest.run(helper);
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, menus, "portal menus"),
                "Native portal menus did not complete")).thenExecute(() -> {
                    CompletableFuture<Boolean> construction = new MinecraftConstructionGameTest(helper).run();
                    helper.startSequence().thenWaitUntil(() -> helper.assertTrue(completed(helper, construction, "construction"),
                        "Native construction did not complete")).thenExecute(() -> new MinecraftDoorGameTest(helper).start());
                });
        });
    }

    public static boolean completed(GameTestHelper helper, CompletableFuture<Boolean> future, String stage) {
        if (!future.isDone()) {
            return false;
        }
        try {
            return future.join();
        } catch (CompletionException failure) {
            if (REPORTED_FAILURES.add(future)) {
                LOGGER.error("Wormholes runtime test failed during {}", stage, failure.getCause());
                helper.startSequence().thenFail(() -> new GameTestAssertException(stage + ": " + failure.getCause()));
            }
            return false;
        }
    }

    private static List<BlockPos> cells(GameTestHelper helper, int minX) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = minX; x < minX + 3; x++) {
            for (int y = 2; y < 5; y++) {
                cells.add(helper.absolutePos(new BlockPos(x, y, 4)));
            }
        }
        return cells;
    }

    private static boolean persisted(Path file, UUID destination) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        try {
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(file)) {
                    Map<String, Object> values = MinecraftJsonDocuments.INSTANCE.decode(Files.readString(file));
                    if (destination.equals(MinecraftPortal.read(values).getDestinationId())) {
                        return true;
                    }
                }
                Thread.sleep(20);
            }
            return false;
        } catch (IOException error) {
            throw new CompletionException(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CompletionException(error);
        }
    }
}
