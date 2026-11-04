package art.arcane.wormholes.clientgametest;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

/** The owner's portal pair walked through and back several times: the home terrain must then match a full terrain rebuild. */
public final class CrossingRoundTripsClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int EDGE = 3;
    private static final int TRIPS = 4;
    private static final int TIMEOUT_TICKS = 1200;
    private static final double MAXIMUM_DIFFERENCE = 15.0D;
    private static final BlockPos SOURCE = new BlockPos(-108, 64, 192);
    private static final BlockPos DESTINATION = new BlockPos(-12, 76, 173);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(creator -> creator.setSeed("-8937524544511030531")).create()) {
            FabricConnection connection = FabricConnection.of(singleplayer);
            FabricSeamlessClient client = new FabricSeamlessClient(context, connection);
            FabricSeamlessServer server = new FabricSeamlessServer(singleplayer.getServer(), connection);
            SeamlessScenario.join(client);
            server.computeOnServer(minecraftServer -> {
                ServerLevel level = minecraftServer.overworld();
                ServerPlayer actor = connection.getServerPlayer();
                minecraftServer.getCommands().performPrefixedCommand(minecraftServer.createCommandSourceStack(), "time set noon");
                minecraftServer.getCommands().performPrefixedCommand(minecraftServer.createCommandSourceStack(), "gamerule doDaylightCycle false");
                for (int x = -1; x <= 3; x++) {
                    for (int y = 0; y <= 3; y++) {
                        for (int z = 1; z <= 8; z++) {
                            level.setBlockAndUpdate(SOURCE.offset(x, y, z), Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                WormholesModRuntime wormholes = runtime(minecraftServer);
                MinecraftPortal a = wormholes.portals().create(actor.getUUID(), level, cells(SOURCE), PortalType.WORMHOLE, new Vec3(0, 0, -1));
                MinecraftPortal b = wormholes.portals().create(actor.getUUID(), level, cells(DESTINATION), PortalType.WORMHOLE, new Vec3(0, 0, -1));
                SeamlessScenario.assertTrue(a != null && b != null, "portal creation rejected");
                b.setFrame(new Frame(Face.N, Face.D, Face.E));
                a.setGravityRotationEnabled(true);
                b.setGravityRotationEnabled(true);
                SeamlessScenario.assertTrue(wormholes.portals().link(actor, a.getId(), b.getId()), "link rejected");
                SeamlessScenario.assertTrue(wormholes.portals().link(actor, b.getId(), a.getId()), "return link rejected");
                actor.setGameMode(GameType.CREATIVE);
                actor.getAbilities().mayfly = true;
                actor.getAbilities().flying = true;
                actor.onUpdateAbilities();
                return actor.teleportTo(level, SOURCE.getX() + 1.5D, SOURCE.getY() + 0.2D, SOURCE.getZ() + 6.5D, Set.of(), 180.0F, 0.0F, false);
            });
            client.runOnClient(minecraft -> minecraft.options.renderDistance().set(20));
            client.waitTicks(240);
            capture(client, "trip0-home");
            for (int trip = 1; trip <= TRIPS; trip++) {
                walk(client, 180.0F);
                client.waitTicks(40);
                capture(client, "trip" + trip + "-away");
                walk(client, 0.0F);
                client.waitTicks(60);
                for (float yaw : new float[] {180.0F, 90.0F, 270.0F}) {
                    client.lookAt(yaw, -15.0F);
                    client.waitTicks(15);
                    capture(client, "trip" + trip + "-home-" + (int) yaw);
                }
                LOGGER.info("[round-trips {}] {}", trip, client.computeOnClient(minecraft -> "feet " + minecraft.player.position()));
                client.lookAt(180.0F, 0.0F);
            }
            client.lookAt(180.0F, -15.0F);
            client.waitTicks(20);
            FramebufferProbe after = capture(client, "final");
            client.runOnClient(minecraft -> ClientWorldLoader.rebuildMainTerrain());
            client.waitTicks(200);
            FramebufferProbe rebuilt = capture(client, "rebuilt");
            double difference = difference(after, rebuilt);
            LOGGER.info("[round-trips] mean difference from a full terrain rebuild {}", difference);
            SeamlessScenario.assertTrue(difference < MAXIMUM_DIFFERENCE,
                "terrain after " + TRIPS + " round trips differs from a full rebuild by " + difference + " (captures final.png and rebuilt.png)");
        }
    }

    /**
     * Mean absolute luma difference over the frame, skipping the hotbar and the hand, after scaling the first frame to the
     * second's mean brightness: a rebuild resets automatic exposure, which must not count as a terrain difference.
     */
    private static double difference(FramebufferProbe first, FramebufferProbe second) {
        double firstMean = 0.0D;
        double secondMean = 0.0D;
        int count = 0;
        for (int y = 0; y < first.height() * 3 / 4; y += 4) {
            for (int x = 0; x < first.width(); x += 4) {
                firstMean += luma(first.read(x, y));
                secondMean += luma(second.read(x, y));
                count++;
            }
        }
        double exposure = firstMean > 0.0D ? secondMean / firstMean : 1.0D;
        double total = 0.0D;
        for (int y = 0; y < first.height() * 3 / 4; y += 4) {
            for (int x = 0; x < first.width(); x += 4) {
                total += Math.abs(luma(first.read(x, y)) * exposure - luma(second.read(x, y)));
            }
        }
        return total / count;
    }

    private static double luma(int color) {
        return 0.2126D * ((color >> 16) & 0xFF) + 0.7152D * ((color >> 8) & 0xFF) + 0.0722D * (color & 0xFF);
    }

    private static void walk(FabricSeamlessClient client, float yaw) {
        client.lookAt(yaw, 0.0F);
        client.waitTicks(5);
        int departure = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.level));
        client.holdForward();
        try {
            client.waitFor(minecraft -> System.identityHashCode(minecraft.level) != departure, TIMEOUT_TICKS);
            client.waitTicks(12);
        } finally {
            client.releaseForward();
        }
    }

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(EDGE * EDGE);
        for (int x = 0; x < EDGE; x++) {
            for (int y = 0; y < EDGE; y++) {
                cells.add(min.offset(x, y, 0));
            }
        }
        return cells;
    }

    private static FramebufferProbe capture(FabricSeamlessClient client, String name) {
        Path output = client.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots/trips/" + name + ".png"));
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        client.runOnClient(minecraft -> FramebufferProbe.capture(minecraft, output, captured::set));
        client.waitFor(minecraft -> captured.get() != null, TIMEOUT_TICKS);
        return captured.get();
    }
}
