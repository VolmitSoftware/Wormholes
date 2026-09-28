package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MinecraftRtpGameTest {
    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer connection;
    private final MinecraftPortal portal;
    private MinecraftPortal preview;
    private UUID previousRoute;
    private MinecraftGameTestPlayer second;
    private long rotationDeadline;
    private boolean cleaned;

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
        RtpSettings settings = runtime.rtp().settings(portal).toBuilder().radii(12, 24).rotationMode(RtpRotationMode.STATIC).soundEnabled(false).build();
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
        runtime.schedule(this::cleanup, 1190);
        for (int x = 0; x < 7; x++) {
            for (int z = 1; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        approach();
        helper.startSequence().thenWaitUntil(() -> {
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
            portal.setRtpSettings(runtime.rtp().settings(portal).toBuilder().targetBiomeKey("minecraft:plains")
                .rotationMode(RtpRotationMode.TIMED).cycleDurationMillis(15_000L).build());
            runtime.portals().save(portal);
        }).thenWaitUntil(() -> {
            preview = runtime.rtp().projectionDestination(connection.player(), portal);
            helper.assertTrue(preview != null, "Biome-filtered timed destination did not become ready");
            BlockPos feet = BlockPos.containing(preview.getOrigin().x(), preview.getOrigin().y(), preview.getOrigin().z());
            helper.assertTrue(helper.getLevel().getBiome(feet).unwrapKey().orElseThrow().identifier().toString().equals("minecraft:plains"),
                "Biome filter selected a different biome");
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
            GeometryVector point = portal.getOrigin();
            PortalCrossing crossing = new PortalCrossing(portal.getFrame(), point, point,
                new GeometryVector(0, 0, 0), new GeometryVector(0, 0, 1), true);
            helper.assertTrue(runtime.rtp().begin(connection.player(), portal, crossing), "Cancellation fixture could not begin traversal");
            runtime.rtp().disconnected(connection.player());
            helper.assertTrue(!runtime.rtp().locked(connection.player().getUUID()), "Disconnected player retained a random traversal lock");
        }).thenIdle(5).thenExecute(() -> {
            helper.assertTrue(runtime.rtp().snapshot(portal.getId()).orElseThrow().runtime().playerClaims() == 0,
                "Cancelled traversal retained a private claim");
            helper.assertTrue(connection.player().position().distanceToSqr(portal.getOrigin().x(), portal.getOrigin().y(), portal.getOrigin().z()) < 16,
                "Cancelled traversal still teleported the player");
            GeometryVector point = portal.getOrigin();
            PortalCrossing crossing = new PortalCrossing(portal.getFrame(), point, point,
                new GeometryVector(0, 0, 0), new GeometryVector(0, 0, 1), true);
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
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS rtp_runtime sampled_preview annulus safe_arrival claim_release manual_reroll biome_filter timed_rotation private_reservations disconnect_cancellation world_change_cancellation");
            cleanup();
        }).thenSucceed();
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
        if (second != null) {
            second.close();
        }
        runtime.portals().remove(connection.player(), portal.getId());
        connection.close();
    }
}
