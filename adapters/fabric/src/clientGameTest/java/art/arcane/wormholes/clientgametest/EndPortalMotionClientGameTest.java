package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftDyedItems;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

/**
 * A temporal renderer reprojects every pixel by the depth it finds, so a portal in the End must publish the depth of what
 * its aperture shows, sky included, or the view and the sky around it smear while the camera moves. From the End, a
 * portal onto a checker in the End and one onto a checker in the overworld: while the camera strafes with a portal on
 * screen, the checker through the aperture must keep its edge contrast, and the sky beside the aperture must keep as much
 * of its detail as it keeps when the camera turns away from the portals and strafes again.
 */
public final class EndPortalMotionClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int EDGE = 7;
    private static final BlockPos END_SOURCE = new BlockPos(1000, 100, 1000);
    private static final BlockPos END_DESTINATION = new BlockPos(1000, 100, 1200);
    private static final BlockPos OVERWORLD_SOURCE = new BlockPos(1300, 100, 1000);
    private static final BlockPos OVERWORLD_DESTINATION = new BlockPos(1300, 200, 1200);
    private static final int WALL_DISTANCE = 40;
    private static final int WALL_RADIUS = 40;
    private static final double VIEW_DISTANCE = 6.0D;
    private static final double EYE_HEIGHT = 1.62D;
    private static final double STRAFE_PER_TICK = 0.22D;
    private static final int STRAFE_TICKS = 12;
    private static final int TIMEOUT_TICKS = 600;
    private static final int SAMPLES = 6;
    private static final double EDGE_LUMA = 90.0D;
    private static final int MAXIMUM_CELL_PIXELS = 40;
    private static final int APERTURE_INSET = 6;
    private static final int SKY_X = 1560;
    private static final int SKY_Y = 60;
    private static final int SKY_WIDTH = 320;
    private static final int SKY_HEIGHT = 200;
    private static final double MINIMUM_DETAIL_SHARE = 0.6D;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FabricConnection connection = FabricConnection.of(singleplayer);
            FabricSeamlessClient client = new FabricSeamlessClient(context, connection);
            FabricSeamlessServer server = new FabricSeamlessServer(singleplayer.getServer(), connection);
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            List<UUID> portals = server.computeOnServer(minecraftServer -> build(minecraftServer, connection.getServerPlayer()));
            client.runOnClient(minecraft -> {
                if (!ClientGui.hudHidden(minecraft)) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            List<String> failures = new ArrayList<>();
            double[] endCase = measure(client, server, connection, "end", END_SOURCE);
            double[] overworldCase = measure(client, server, connection, "overworld", OVERWORLD_SOURCE);
            client.lookAt(0.0F, 0.0F);
            client.waitTicks(40);
            double awaySky = strafeAndMeasure(client, "away", false)[1];
            client.runOnClient(minecraft -> minecraft.player.setDeltaMovement(Vec3.ZERO));
            report("end", endCase, awaySky, failures);
            report("overworld", overworldCase, awaySky, failures);
            server.removePortals(portals);
            SeamlessScenario.assertTrue(failures.isEmpty(), String.join("; ", failures));
        }
    }

    /** Still and moving frames at a source: {checker detail still, sky detail still, checker detail moving, sky detail moving}. */
    private static double[] measure(FabricSeamlessClient client, FabricSeamlessServer server, FabricConnection connection, String name, BlockPos source) {
        place(client, server, connection, source);
        client.waitTicks(100);
        client.waitForChunksRender();
        FramebufferProbe stillFrame = capture(client, name + "-still");
        double stillChecker = checkerDetail(stillFrame, name + "-still");
        double stillSky = skyDetail(stillFrame);
        double[] moving = strafeAndMeasure(client, name + "-moving", true);
        client.runOnClient(minecraft -> minecraft.player.setDeltaMovement(Vec3.ZERO));
        return new double[] {stillChecker, stillSky, moving[0], moving[1]};
    }

    private static void report(String name, double[] measured, double awaySky, List<String> failures) {
        LOGGER.info("[end-motion] case={} checkerDetailStill={} checkerDetailMoving={} skyDetailStill={} skyDetailMoving={} skyDetailAway={}",
            name, String.format("%.2f", measured[0]), String.format("%.2f", measured[2]), String.format("%.2f", measured[1]),
            String.format("%.2f", measured[3]), String.format("%.2f", awaySky));
        if (measured[2] < measured[0] * MINIMUM_DETAIL_SHARE) {
            failures.add("the " + name + " view smears in motion: checker detail " + String.format("%.2f", measured[2]) + " moving against "
                + String.format("%.2f", measured[0]) + " standing still");
        }
        if (measured[3] < awaySky * MINIMUM_DETAIL_SHARE) {
            failures.add("the End sky loses its detail while the " + name + " portal is in view: " + String.format("%.2f", measured[3])
                + " moving against " + String.format("%.2f", awaySky) + " with the portals out of view");
        }
    }

    /** Strafes for a while, then averages the checker detail (when a portal is in view) and the sky detail over several frames. */
    private static double[] strafeAndMeasure(FabricSeamlessClient client, String name, boolean portalInView) {
        for (int tick = 0; tick < STRAFE_TICKS; tick++) {
            strafe(client);
            client.waitTicks(1);
        }
        double checker = 0.0D;
        double sky = 0.0D;
        for (int sample = 0; sample < SAMPLES; sample++) {
            strafe(client);
            client.waitTicks(1);
            FramebufferProbe frame = capture(client, name + "-" + sample);
            if (portalInView) {
                checker += checkerDetail(frame, name + "-" + sample) / SAMPLES;
            }
            sky += skyDetail(frame) / SAMPLES;
        }
        return new double[] {checker, sky};
    }

    private static List<UUID> build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime wormholes = runtime(server);
        ServerLevel end = server.getLevel(Level.END);
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        checker(end, END_DESTINATION);
        checker(overworld, OVERWORLD_DESTINATION);
        clear(end, END_SOURCE);
        clear(end, OVERWORLD_SOURCE);
        List<UUID> portals = new ArrayList<>();
        portals.addAll(pair(wormholes, actor, end, END_SOURCE, end, END_DESTINATION));
        portals.addAll(pair(wormholes, actor, end, OVERWORLD_SOURCE, overworld, OVERWORLD_DESTINATION));
        actor.setGameMode(GameType.CREATIVE);
        actor.getAbilities().mayfly = true;
        actor.getAbilities().flying = true;
        actor.onUpdateAbilities();
        return portals;
    }

    private static List<UUID> pair(WormholesModRuntime wormholes, ServerPlayer actor, ServerLevel sourceLevel, BlockPos sourceSite, ServerLevel destinationLevel,
                                   BlockPos destinationSite) {
        MinecraftPortal source = wormholes.portals().create(actor.getUUID(), sourceLevel, cells(sourceSite), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = wormholes.portals().create(actor.getUUID(), destinationLevel, cells(destinationSite), PortalType.PORTAL, new Vec3(0, 0, -1));
        SeamlessScenario.assertTrue(source != null && destination != null, "End motion portal creation rejected");
        source.setAmbientStyle(AmbientParticleStyle.OFF);
        destination.setAmbientStyle(AmbientParticleStyle.OFF);
        SeamlessScenario.assertTrue(wormholes.portals().link(actor, source.getId(), destination.getId()), "End motion link rejected");
        return List.of(source.getId(), destination.getId());
    }

    /** A white and black checker wall beyond a destination, and open air between. */
    private static void checker(ServerLevel level, BlockPos destination) {
        BlockState white = MinecraftDyedItems.concreteBlock(DyeColor.WHITE).defaultBlockState();
        BlockState black = MinecraftDyedItems.concreteBlock(DyeColor.BLACK).defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -WALL_RADIUS; x <= WALL_RADIUS; x++) {
            for (int y = -WALL_RADIUS; y <= WALL_RADIUS; y++) {
                level.setBlock(cursor.set(destination.getX() + x, destination.getY() + y, destination.getZ() - WALL_DISTANCE), ((x + y) & 1) == 0 ? white : black, 3);
                for (int z = 1 - WALL_DISTANCE; z <= 2; z++) {
                    if (Math.abs(x) <= EDGE + 2 && Math.abs(y) <= EDGE + 2) {
                        level.setBlock(cursor.set(destination.getX() + x, destination.getY() + y, destination.getZ() + z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static void clear(ServerLevel level, BlockPos source) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -WALL_RADIUS; x <= WALL_RADIUS; x++) {
            for (int y = -WALL_RADIUS; y <= WALL_RADIUS; y++) {
                level.setBlock(cursor.set(source.getX() + x, source.getY() + y, source.getZ() - 1), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static void place(FabricSeamlessClient client, FabricSeamlessServer server, FabricConnection connection, BlockPos source) {
        Vec3 eye = new Vec3(source.getX() + EDGE / 2.0D - 1.2D, source.getY() + EDGE / 2.0D, source.getZ() + 0.5D + VIEW_DISTANCE);
        ResourceKey<Level> dimension = Level.END;
        server.computeOnServer(minecraftServer -> {
            ServerPlayer player = connection.getServerPlayer();
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            return player.teleportTo(minecraftServer.getLevel(dimension), eye.x, eye.y - EYE_HEIGHT, eye.z, Set.of(), 180.0F, 0.0F, false);
        });
        client.waitFor(minecraft -> minecraft.player != null && minecraft.level != null && minecraft.level.dimension() == dimension
            && minecraft.player.position().distanceTo(new Vec3(eye.x, eye.y - EYE_HEIGHT, eye.z)) < 0.05D, TIMEOUT_TICKS);
        client.lookAt(180.0F, 0.0F);
        client.runOnClient(minecraft -> minecraft.player.getAbilities().flying = true);
    }

    private static void strafe(FabricSeamlessClient client) {
        client.runOnClient(minecraft -> minecraft.player.setDeltaMovement(STRAFE_PER_TICK, 0.0D, 0.0D));
    }

    private static FramebufferProbe capture(FabricSeamlessClient client, String name) {
        Path output = client.computeOnClient((Minecraft minecraft) -> minecraft.gameDirectory.toPath().resolve("screenshots/end-motion/" + name + ".png"));
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        client.runOnClient(minecraft -> FramebufferProbe.capture(minecraft, output, captured::set));
        client.waitFor(minecraft -> captured.get() != null, TIMEOUT_TICKS);
        return captured.get();
    }

    /** The mean absolute luma step between neighbouring pixels in a sky patch beside the aperture: smeared sky reads lower. */
    private static double skyDetail(FramebufferProbe frame) {
        return detail(frame, SKY_X, SKY_Y, SKY_X + SKY_WIDTH, SKY_Y + SKY_HEIGHT);
    }

    /**
     * The mean absolute luma step between neighbouring pixels over the checker seen through the aperture, found as the run of
     * close light-dark crossings along the middle row: a smeared checker reads lower than a still one.
     */
    private static double checkerDetail(FramebufferProbe frame, String name) {
        int minX = frame.width();
        int maxX = 0;
        int last = -1;
        for (int x = 1; x < frame.width(); x++) {
            if (crossing(frame, x, frame.height() / 2)) {
                if (last >= 0 && x - last < MAXIMUM_CELL_PIXELS) {
                    minX = Math.min(minX, last);
                    maxX = Math.max(maxX, x);
                }
                last = x;
            }
        }
        SeamlessScenario.assertTrue(maxX - minX > 4 * MAXIMUM_CELL_PIXELS, name + ": the checker never showed through the aperture");
        return detail(frame, minX + APERTURE_INSET, frame.height() / 4, maxX - APERTURE_INSET, frame.height() * 3 / 4);
    }

    private static double detail(FramebufferProbe frame, int left, int top, int right, int bottom) {
        double total = 0.0D;
        int count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left + 1; x < right; x++) {
                total += Math.abs(luma(frame.read(x, y)) - luma(frame.read(x - 1, y)));
                count++;
            }
        }
        return total / count;
    }

    private static boolean crossing(FramebufferProbe frame, int x, int y) {
        return luma(frame.read(x - 1, y)) > EDGE_LUMA != luma(frame.read(x, y)) > EDGE_LUMA;
    }

    private static double luma(int color) {
        return 0.2126D * ((color >> 16) & 0xFF) + 0.7152D * ((color >> 8) & 0xFF) + 0.0722D * (color & 0xFF);
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
}
