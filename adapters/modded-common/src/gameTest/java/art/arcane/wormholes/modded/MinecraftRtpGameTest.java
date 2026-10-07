package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import net.minecraft.core.BlockPos;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.rtp.RtpSafetyMode;
import art.arcane.wormholes.portal.rtp.RtpVerticalMode;
import art.arcane.optics.math.Box;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MinecraftRtpGameTest {
    private static final int EDITOR_CLICK_TICKS = 2;
    private static final int EDITOR_MUTATION_TICKS = 4;
    private static final int RIM_WINDOW_TICKS = 5;
    private static final int ANNULUS_INNER = 12;
    private static final int ANNULUS_OUTER = 24;
    private static final int ANNULUS_STEP = 2;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer connection;
    private final MinecraftPortal portal;
    private MinecraftPortal preview;
    private UUID previousRoute;
    private MinecraftGameTestPlayer second;
    private long rotationDeadline;
    private String targetBiome;
    private boolean cleaned;
    private MinecraftPortal editorPortal;
    private RtpSettings editorInitial;
    private MinecraftWindow editorWindow;
    private double editorCenterX;
    private int rimWindowStart;
    private int rimParticles;

    private MinecraftRtpGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        connection = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "RtpTraveler");
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 2; x <= 4; x++) {
            for (int y = 2; y <= 4; y++) {
                cells.add(helper.absolutePos(new BlockPos(x, y, 4)));
            }
        }
        portal = runtime.portals().create(connection.player().getUUID(), helper.getLevel(), cells, PortalType.RTP, new Vec3(0, 0, -1));
        RtpSettings settings = runtime.rtp().settings(portal).toBuilder().radii(ANNULUS_INNER, ANNULUS_OUTER).rotationMode(RtpRotationMode.STATIC).soundEnabled(false).build();
        portal.setRtpSettings(settings);
        runtime.portals().save(portal);
    }

    public static void run(GameTestHelper helper) {
        MinecraftRtpGameTest test = new MinecraftRtpGameTest(helper);
        try {
            test.start();
        } catch (RuntimeException error) {
            test.cleanup();
            throw error;
        }
    }

    private void start() {
        RuntimeBaselineEnvironment.cleanupOnTeardown(this::cleanup);
        for (int x = 0; x < 7; x++) {
            for (int z = 1; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        approach();
        editor(helper.startSequence()).thenWaitUntil(() -> {
            preview = runtime.rtp().projectionDestination(connection.player(), portal);
            helper.assertTrue(preview != null, "Random destination preview did not become ready");
        }).thenExecute(() -> {
            previousRoute = preview.getId();
            helper.assertTrue(preview.getWorldKey().equals(portal.getWorldKey()), "Random destination changed target world");
            RtpService.Snapshot snapshot = runtime.rtp().snapshot(portal.getId()).orElseThrow();
            double dx = snapshot.runtime().active().blockX() + 0.5D - portal.getOrigin().x();
            double dz = snapshot.runtime().active().blockZ() + 0.5D - portal.getOrigin().z();
            helper.assertTrue(dx * dx + dz * dz >= 100 && dx * dx + dz * dz <= 650, "Random candidate is outside configured annulus");
            helper.assertTrue(!runtime.portals().link(connection.player(), portal.getId(), UUID.randomUUID()), "Random portal accepted a fixed link");
            connection.drainPackets();
            rimWindowStart = runtime.server().getTickCount();
            rimParticles = 0;
        }).thenExecuteFor(RIM_WINDOW_TICKS, this::attendRim).thenExecute(this::closeRimWindow)
            .thenExecuteFor(RIM_WINDOW_TICKS, this::attendRim).thenExecute(() -> {
                closeRimWindow();
                helper.assertTrue(rimParticles >= 8, "RTP rim sent no refresh across two refresh intervals");
            }).thenIdle(3).thenExecute(this::cross).thenWaitUntil(() -> {
            helper.assertTrue(connection.player().position().distanceToSqr(portal.getOrigin().x(), portal.getOrigin().y(), portal.getOrigin().z()) > 64,
                "Random traveler did not reach a sampled destination");
            helper.assertTrue(!runtime.rtp().locked(connection.player().getUUID()), "Random traveler retained a traversal lock");
        }).thenExecute(() -> {
            RtpService.Snapshot snapshot = runtime.rtp().snapshot(portal.getId()).orElseThrow();
            helper.assertTrue(snapshot.runtime().sharedClaims() == 0, "Successful random traversal retained its shared claim");
            helper.assertTrue(connection.player().blockPosition().below().getY() >= helper.getLevel().getMinY(), "Random arrival is below world bounds");
            helper.assertTrue(!helper.getLevel().getBlockState(connection.player().blockPosition().below()).getCollisionShape(
                helper.getLevel(), connection.player().blockPosition().below()).isEmpty(), "Random arrival has no ground support");
            approach();
            runtime.rtp().projectionDestination(connection.player(), portal);
            helper.assertTrue(runtime.rtp().reroll(portal.getId()).join(), "Manual random reroll was rejected");
        }).thenWaitUntil(() -> {
            MinecraftPortal rerolled = runtime.rtp().projectionDestination(connection.player(), portal);
            helper.assertTrue(rerolled != null && !rerolled.getId().equals(previousRoute), "Manual reroll did not replace the preview route");
        }).thenExecute(() -> {
            targetBiome = commonLandBiome();
            portal.setRtpSettings(runtime.rtp().settings(portal).toBuilder().targetBiomeKey(targetBiome)
                .rotationMode(RtpRotationMode.TIMED).cycleDurationMillis(15_000L).build());
            runtime.portals().save(portal);
        }).thenWaitUntil(() -> {
            preview = runtime.rtp().projectionDestination(connection.player(), portal);
            helper.assertTrue(preview != null, "Biome-filtered timed destination did not become ready");
            BlockPos feet = BlockPos.containing(preview.getOrigin().x(), preview.getOrigin().y(), preview.getOrigin().z());
            helper.assertTrue(helper.getLevel().getBiome(feet).unwrapKey().orElseThrow().identifier().toString().equals(targetBiome),
                "Biome filter selected a different biome than " + targetBiome);
        }).thenExecute(() -> {
            previousRoute = preview.getId();
            rotationDeadline = System.currentTimeMillis() + 22_000L;
        }).thenWaitUntil(() -> {
            MinecraftPortal current = runtime.rtp().projectionDestination(connection.player(), portal);
            if (System.currentTimeMillis() > rotationDeadline) {
                throw new IllegalStateException("Timed rotation did not advance within its configured cycle");
            }
            helper.assertTrue(current != null && !current.getId().equals(previousRoute), "Timed rotation has not advanced");
        }).thenExecute(() -> {
            portal.setRtpSettings(runtime.rtp().settings(portal).toBuilder().allocationMode(RtpAllocationMode.PER_PLAYER)
                .rotationMode(RtpRotationMode.STATIC).build());
            runtime.portals().save(portal);
            second = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "RtpSecond");
            second.player().snapTo(connection.player().position());
        }).thenWaitUntil(() -> {
            MinecraftPortal firstRoute = runtime.rtp().projectionDestination(connection.player(), portal);
            MinecraftPortal secondRoute = runtime.rtp().projectionDestination(second.player(), portal);
            helper.assertTrue(firstRoute != null && secondRoute != null, "Private destinations did not become ready");
            helper.assertTrue(!firstRoute.getOrigin().equals(secondRoute.getOrigin()), "Two players received the same reserved destination");
            helper.assertTrue(runtime.rtp().snapshot(portal.getId()).orElseThrow().runtime().reservedPlayers() == 2,
                "Private allocation did not reserve one destination for each player");
        }).thenExecute(() -> {
            Vec3d point = portal.getOrigin();
            PlaneCrossing crossing = new PlaneCrossing(portal.getFrame(), point, point,
                new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), true);
            helper.assertTrue(runtime.rtp().begin(connection.player(), portal, crossing), "Cancellation fixture could not begin traversal");
            runtime.rtp().disconnected(connection.player());
            helper.assertTrue(!runtime.rtp().locked(connection.player().getUUID()), "Disconnected player retained a random traversal lock");
        }).thenIdle(5).thenExecute(() -> {
            helper.assertTrue(runtime.rtp().snapshot(portal.getId()).orElseThrow().runtime().playerClaims() == 0,
                "Cancelled traversal retained a private claim");
            helper.assertTrue(connection.player().position().distanceToSqr(portal.getOrigin().x(), portal.getOrigin().y(), portal.getOrigin().z()) < 16,
                "Cancelled traversal still teleported the player");
            Vec3d point = portal.getOrigin();
            PlaneCrossing crossing = new PlaneCrossing(portal.getFrame(), point, point,
                new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), true);
            helper.assertTrue(runtime.rtp().begin(second.player(), portal, crossing), "World-change fixture could not begin traversal");
            ServerLevel destination = runtime.server().getLevel(Level.NETHER);
            helper.assertTrue(destination != null, "World-change fixture has no Nether dimension");
            helper.assertTrue(second.player().teleport(new TeleportTransition(destination, new Vec3(0.5D, 100.0D, 0.5D), Vec3.ZERO,
                0.0F, 0.0F, TeleportTransition.DO_NOTHING)) != null, "World-change fixture could not change dimension");
        }).thenIdle(5).thenExecute(() -> {
            helper.assertTrue(!runtime.rtp().locked(second.player().getUUID()), "World change retained a traversal lock");
            helper.assertTrue(second.player().level().dimension() == Level.NETHER, "Cancelled traversal pulled player back from new dimension");
            helper.assertTrue(runtime.rtp().snapshot(portal.getId()).orElseThrow().runtime().playerClaims() == 0,
                "World change retained a private claim");
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS rtp_runtime sampled_preview rim_cadence annulus safe_arrival claim_release manual_reroll biome_filter timed_rotation private_reservations disconnect_cancellation world_change_cancellation");
            cleanup();
        }).thenSucceed();
    }

    private GameTestSequence editor(GameTestSequence sequence) {
        ServerPlayer viewer = connection.player();
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 10; x <= 12; x++) {
            for (int y = 2; y <= 4; y++) {
                cells.add(helper.absolutePos(new BlockPos(x, y, 12)));
            }
        }
        MinecraftRtpMenus menus = new MinecraftRtpMenus(runtime);
        return sequence.thenExecute(() -> {
            editorPortal = runtime.portals().create(viewer.getUUID(), helper.getLevel(), cells, PortalType.RTP, new Vec3(0, 0, -1));
            editorInitial = runtime.rtp().settings(editorPortal);
            MinecraftGameTestPlayer outsider = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "RtpOutsider");
            try {
                menus.open(outsider.player(), editorPortal.getId());
                MinecraftSubsystemMenuProbe.assertClosed(helper, outsider.player(), "Outsider RTP editor");
                helper.assertTrue(MinecraftSubsystemMenuProbe.messaged(outsider.messages(), MinecraftSubsystemMenuProbe.text(outsider.player(),
                    WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty())), "Outsider RTP editor denial was not sent");
            } finally {
                outsider.close();
            }
            menus.open(viewer, editorPortal.getId());
            assertOverview(viewer);
            MinecraftSubsystemMenuProbe.left(viewer, 3, 2);
        }).thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> {
            assertEditorWindow(viewer);
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 2, 1, Items.GUNPOWDER, MinecraftSubsystemMenuProbe.name(viewer,
                WormholesMessages.RTP_RIM_OFF_AVAILABLE, MessageArgs.empty()), false, "RTP rim off");
            MinecraftSubsystemMenuProbe.left(viewer, 2, 1);
        }).thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> {
            helper.assertTrue(!runtime.rtp().settings(editorPortal).isRimEnabled(), "RTP rim control did not persist");
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 2, 1, Items.GUNPOWDER, MinecraftSubsystemMenuProbe.name(viewer,
                WormholesMessages.RTP_RIM_OFF_SELECTED, MessageArgs.empty()), true, "RTP rim off selected");
            helper.assertTrue(noticed(viewer, WormholesMessages.PORTAL_RTP_APPLIED), "RTP applied notice was not sent");
            MinecraftSubsystemMenuProbe.left(viewer, 2, 3);
        }).thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> {
            helper.assertTrue(!runtime.rtp().settings(editorPortal).isSoundEnabled(), "RTP sound control did not persist");
            MinecraftSubsystemMenuProbe.left(viewer, 0, 5);
        }).thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> {
            assertOverview(viewer);
            MinecraftSubsystemMenuProbe.left(viewer, -3, 2);
        }).thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, 2, 3))
            .thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> {
                helper.assertTrue(runtime.rtp().settings(editorPortal).getCustomCenterX() != null, "RTP custom center did not apply");
                editorCenterX = runtime.rtp().settings(editorPortal).getCustomCenterX();
                MinecraftSubsystemMenuProbe.left(viewer, -3, 4);
            }).thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, 2, 2))
            .thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> {
                helper.assertTrue(runtime.rtp().settings(editorPortal).getCustomCenterX() == editorCenterX + 16D,
                    "RTP numeric coordinate control failed");
                MinecraftSubsystemMenuProbe.left(viewer, 0, 5);
            }).thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, 3, 5))
            .thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, -3, 1))
            .thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> {
                helper.assertTrue(runtime.rtp().settings(editorPortal).getTargetBiomeKey() != null, "RTP biome picker did not select a biome");
                MinecraftSubsystemMenuProbe.left(viewer, -4, 1);
            }).thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> {
                helper.assertTrue(runtime.rtp().settings(editorPortal).getTargetBiomeKey() == null, "RTP biome picker did not clear the biome");
                MinecraftSubsystemMenuProbe.left(viewer, 0, 5);
            }).thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, 0, 5))
            .thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> {
                assertOverview(viewer);
                MinecraftSubsystemMenuProbe.left(viewer, 1, 2);
            }).thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, 2, 1))
            .thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, -3, 3))
            .thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, 2, 2))
            .thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> {
                RtpSettings settings = runtime.rtp().settings(editorPortal);
                helper.assertTrue(settings.getAllocationMode() == RtpAllocationMode.PER_PLAYER
                    && settings.getCycleDurationMillis() == editorInitial.getCycleDurationMillis() + 30_000L, "RTP private rotation controls did not apply");
                MinecraftSubsystemMenuProbe.left(viewer, 0, 5);
            }).thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, 0, 5))
            .thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, -1, 2))
            .thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, 2, 1))
            .thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, 0, 2))
            .thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> {
                RtpSettings settings = runtime.rtp().settings(editorPortal);
                helper.assertTrue(settings.getVerticalMode() == RtpVerticalMode.PREFERRED_AVERAGE && settings.getSafetyMode() == RtpSafetyMode.UNSAFE,
                    "RTP landing controls did not apply");
                MinecraftSubsystemMenuProbe.left(viewer, 0, 5);
            }).thenIdle(EDITOR_CLICK_TICKS).thenExecute(() -> MinecraftSubsystemMenuProbe.left(viewer, 0, 4))
            .thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> {
                helper.assertTrue(runtime.rtp().settings(editorPortal).equals(editorInitial), "RTP reset did not restore every default");
                helper.assertTrue(noticed(viewer, WormholesMessages.PORTAL_RTP_RESET_DEFAULTS), "RTP reset notice was not sent");
                assertOverview(viewer);
                editorWindow = MinecraftWindow.active(viewer);
                MinecraftSubsystemMenuProbe.left(viewer, 0, 5);
            }).thenIdle(EDITOR_MUTATION_TICKS).thenExecute(() -> {
                helper.assertTrue(editorWindow != null && !editorWindow.isVisible() && viewer.containerMenu != viewer.inventoryMenu,
                    "RTP back did not open the portal menu");
                viewer.closeContainer();
                menus.close();
                runtime.portals().remove(viewer, editorPortal.getId());
                editorPortal = null;
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS rtp_editor denial layout effects coordinates biomes private landing reset notices back_navigation");
            });
    }

    private void assertEditorWindow(ServerPlayer viewer) {
        MinecraftSubsystemMenuProbe.assertWindow(helper, viewer, MinecraftSubsystemMenuProbe.text(viewer, WormholesMessages.PORTAL_RTP_EDITOR_TITLE,
            MinecraftPortalText.arguments("portal", editorPortal.getName())), 6, Items.STAINED_GLASS_PANE.black(),
            MinecraftSubsystemMenuProbe.slot(-4, 4), "RTP editor");
    }

    private void assertOverview(ServerPlayer viewer) {
        assertEditorWindow(viewer);
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, -3, 2, Items.RECOVERY_COMPASS,
            MinecraftSubsystemMenuProbe.name(viewer, WormholesMessages.RTP_OVERVIEW_DESTINATION, MessageArgs.empty()), false, "RTP destination");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, -1, 2, Items.GRASS_BLOCK,
            MinecraftSubsystemMenuProbe.name(viewer, WormholesMessages.RTP_OVERVIEW_LANDING, MessageArgs.empty()), false, "RTP landing");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 1, 2, Items.CLOCK,
            MinecraftSubsystemMenuProbe.name(viewer, WormholesMessages.RTP_OVERVIEW_ROUTING, MessageArgs.empty()), false, "RTP routing");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 3, 2, Items.GLOWSTONE_DUST,
            MinecraftSubsystemMenuProbe.name(viewer, WormholesMessages.RTP_OVERVIEW_EFFECTS, MessageArgs.empty()), false, "RTP effects");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 0, 4, Items.TNT_MINECART,
            MinecraftSubsystemMenuProbe.name(viewer, WormholesMessages.RTP_RESET_DEFAULTS, MessageArgs.empty()), false, "RTP reset");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer, 0, 5, Items.ARROW,
            MinecraftSubsystemMenuProbe.name(viewer, WormholesMessages.RTP_BACK_PORTAL, MessageArgs.empty()), false, "RTP back");
    }

    private boolean noticed(ServerPlayer viewer, TextKey message) {
        String expected = MinecraftSubsystemMenuProbe.text(viewer, message, MessageArgs.empty());
        for (Component line : connection.messages()) {
            if (line.getString().contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private void attendRim() {
        if (runtime.server().getTickCount() != rimWindowStart) {
            runtime.rtp().projectionDestination(connection.player(), portal);
        }
    }

    private void closeRimWindow() {
        int particles = rimParticles(connection.drainPackets());
        helper.assertTrue(particles <= 8, "RTP rim sent " + particles + " particles within one refresh interval while its colour held");
        rimParticles += particles;
        rimWindowStart = runtime.server().getTickCount();
    }

    private int rimParticles(List<Object> packets) {
        Box area = portal.getGeometry().getArea();
        int count = 0;
        for (Object packet : packets) {
            if (packet instanceof ClientboundLevelParticlesPacket particles && particles.particle() instanceof DustParticleOptions
                && (particles.x() == area.getXa() || particles.x() == area.getXb()) && (particles.y() == area.getYa() || particles.y() == area.getYb())
                && (particles.z() == area.getZa() || particles.z() == area.getZb())) {
                count += particles.count();
            }
        }
        return count;
    }

    private String commonLandBiome() {
        ServerLevel level = helper.getLevel();
        int centerX = Mth.floor(portal.getOrigin().x());
        int centerZ = Mth.floor(portal.getOrigin().z());
        Map<String, Integer> counts = new HashMap<>();
        for (int dx = -ANNULUS_OUTER; dx <= ANNULUS_OUTER; dx += ANNULUS_STEP) {
            for (int dz = -ANNULUS_OUTER; dz <= ANNULUS_OUTER; dz += ANNULUS_STEP) {
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < ANNULUS_INNER * ANNULUS_INNER || distanceSquared > ANNULUS_OUTER * ANNULUS_OUTER) {
                    continue;
                }
                int x = centerX + dx;
                int z = centerZ + dz;
                BlockPos surface = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (!level.getFluidState(surface.below()).isEmpty()) {
                    continue;
                }
                counts.merge(level.getBiome(surface).unwrapKey().orElseThrow().identifier().toString(), 1, Integer::sum);
            }
        }
        String common = null;
        int best = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                common = entry.getKey();
                best = entry.getValue();
            }
        }
        helper.assertTrue(common != null, "Random destination annulus has no dry land");
        return common;
    }

    private void approach() {
        connection.player().snapTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3))));
        connection.player().setDeltaMovement(Vec3.ZERO);
    }

    private void cross() {
        ServerPlayer player = connection.player();
        Vec3 position = player.position();
        player.xo = position.x;
        player.yo = position.y;
        player.zo = position.z;
        player.setPos(position.x, position.y, position.z + 1.5D);
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        if (editorPortal != null) {
            runtime.portals().remove(connection.player(), editorPortal.getId());
        }
        if (second != null) {
            second.close();
        }
        runtime.portals().remove(connection.player(), portal.getId());
        connection.close();
    }
}
