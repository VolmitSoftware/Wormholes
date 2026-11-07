package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.render.stencil.PortalBackends;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

/**
 * How long an Overworld-to-Nether crossing takes to settle on this renderer: the player walks through a portal pair and
 * back, twice, and after each level switch the fixture counts, tick by tick, the chunk columns the client holds within
 * the server's tracking disc of its render distance, and the sections the world renderer has built, until every column
 * has arrived or a timeout passes. The run log carries one `[arrival-time]` line per crossing with the columns held
 * before and at the switch, samples of columns and built sections over the following ticks, the tick every column was
 * in, the server's view of the player's chunk tracking, and the first-person pose before and after the switch (the
 * position and look the client kept across it, and the server's view of them a tick later). The player must arrive in
 * the right level and the held terrain must build; the column count is reported, not gated: a return to the departed
 * level is known to leave a ring of columns between the route window's disc and its square unsent.
 */
public final class CrossingArrivalTimeClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String LINE = "[arrival-time]";
    private static final BlockPos OVERWORLD_PORTAL = new BlockPos(-20, 78, 146);
    private static final BlockPos NETHER_PORTAL = new BlockPos(14, 61, 18);
    private static final int APERTURE_WIDTH = 9;
    private static final int APERTURE_HEIGHT = 7;
    private static final int RENDER_DISTANCE = 20;
    private static final int TRIPS = 2;
    private static final double START_DISTANCE = 6.0D;
    private static final double EYE_HEIGHT = 1.62D;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int TERRAIN_TIMEOUT_TICKS = 3600;
    private static final int SETTLE_TICKS = 100;
    private static final int POST_SETTLE_TICKS = 60;
    private static final int[] SAMPLE_TICKS = {1, 20, 60, 100, 200, 400, 800, 1200};
    private static final int FAR_CHUNKS = 15;

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
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.renderDistance().set(RENDER_DISTANCE);
                minecraft.options.broadcastOptions();
                if (!ClientGui.hudHidden(minecraft)) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            server.computeOnServer(minecraftServer -> {
                minecraftServer.getPlayerList().setViewDistance(RENDER_DISTANCE);
                return null;
            });
            place(client, server, connection, Level.OVERWORLD, OVERWORLD_PORTAL);
            client.waitTicks(SETTLE_TICKS);
            client.waitFor(MainRendererSections::terrainComplete, TERRAIN_TIMEOUT_TICKS);
            List<String> failures = new ArrayList<>();
            for (int trip = 1; trip <= TRIPS; trip++) {
                cross(client, server, connection, trip + "-to-nether", 90.0F, Level.NETHER, failures);
                client.waitTicks(POST_SETTLE_TICKS);
                cross(client, server, connection, trip + "-to-overworld", 270.0F, Level.OVERWORLD, failures);
                client.waitTicks(POST_SETTLE_TICKS);
            }
            server.removePortals(portals);
            SeamlessScenario.assertTrue(failures.isEmpty(), String.join("; ", failures));
        }
    }

    /** Walks through the portal ahead and measures the arrival. */
    private static void cross(FabricSeamlessClient client, FabricSeamlessServer server, FabricConnection connection, String label, float yaw,
                              ResourceKey<Level> arrival, List<String> failures) {
        client.lookAt(yaw, 0.0F);
        client.waitTicks(5);
        int departure = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.level));
        String before = client.computeOnClient(minecraft -> pose(minecraft));
        int[] columnsBefore = client.computeOnClient(minecraft -> columns(minecraft));
        int renderedBefore = client.computeOnClient(minecraft -> MainRendererSections.rendered(minecraft));
        String trackingBefore = server.computeOnServer(minecraftServer -> tracking(connection.getServerPlayer()));
        client.holdForward();
        int switched;
        try {
            client.waitFor(minecraft -> System.identityHashCode(minecraft.level) != departure, TIMEOUT_TICKS);
            switched = client.computeOnClient(minecraft -> (int) minecraft.level.getGameTime());
        } finally {
            client.releaseForward();
        }
        String after = client.computeOnClient(minecraft -> pose(minecraft));
        int[] columnsAtSwitch = client.computeOnClient(minecraft -> columns(minecraft));
        boolean terrainAtSwitch = client.computeOnClient(MainRendererSections::terrainComplete);
        client.waitTicks(1);
        String serverPose = server.computeOnServer(minecraftServer -> {
            ServerPlayer player = connection.getServerPlayer();
            return String.format("%.3f %.3f %.3f yaw=%.2f pitch=%.2f", player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        });
        int terrainTicks = -1;
        int columnsTicks = -1;
        int ticks = 0;
        int[] columns = columnsAtSwitch;
        StringBuilder samples = new StringBuilder();
        while (ticks < TIMEOUT_TICKS && (terrainTicks < 0 || columnsTicks < 0 || ticks < SAMPLE_TICKS[SAMPLE_TICKS.length - 1])) {
            client.waitTicks(1);
            ticks++;
            columns = client.computeOnClient(minecraft -> columns(minecraft));
            if (columnsTicks < 0 && columns[0] >= columns[1]) {
                columnsTicks = ticks;
            }
            if (terrainTicks < 0 && client.computeOnClient(MainRendererSections::terrainComplete)) {
                terrainTicks = ticks;
            }
            for (int sample : SAMPLE_TICKS) {
                if (sample == ticks) {
                    int rendered = client.computeOnClient(minecraft -> MainRendererSections.rendered(minecraft));
                    samples.append(' ').append(ticks).append(':').append(columns[0]).append('/').append(rendered);
                }
            }
        }
        boolean inArrival = client.computeOnClient(minecraft -> minecraft.level.dimension() == arrival);
        String trackingAfter = server.computeOnServer(minecraftServer -> tracking(connection.getServerPlayer()));
        LOGGER.info("{} crossing={} arrivalLevel={} switchedAtGameTime={} columnsBefore={}/{} renderedBefore={} columnsAtSwitch={}/{} terrainCompleteAtSwitch={} terrainCompleteTicks={} columnsCompleteTicks={} columnsEnd={}/{} samples[tick:columns/renderedSections]={} trackingBefore=[{}] trackingAfter=[{}] poseBefore=[{}] poseAfter=[{}] serverPose=[{}] path={}",
            LINE, label, inArrival, switched, columnsBefore[0], columnsBefore[1], renderedBefore, columnsAtSwitch[0], columnsAtSwitch[1], terrainAtSwitch, terrainTicks,
            columnsTicks, columns[0], columns[1], samples, trackingBefore, trackingAfter, before, after, serverPose,
            client.computeOnClient(minecraft -> PortalBackends.describe()));
        if (!inArrival) {
            failures.add(label + ": the player is not in " + arrival.identifier());
        }
        if (terrainTicks < 0) {
            failures.add(label + ": the arrival terrain was still building after " + TIMEOUT_TICKS + " ticks");
        }
    }

    /** The server's view of the player's chunk tracking: its view distance, and whether a column 15 chunks east is tracked for the player. */
    private static String tracking(ServerPlayer player) {
        ServerLevel level = player.level();
        ChunkPos far = new ChunkPos(SectionPos.blockToSectionCoord(player.getBlockX()) + FAR_CHUNKS, SectionPos.blockToSectionCoord(player.getBlockZ()));
        boolean tracked = level.getChunkSource().chunkMap.getPlayers(far, false).contains(player);
        return "level=" + level.dimension().identifier() + " viewDistance=" + level.getServer().getPlayerList().getViewDistance()
            + " requested=" + player.requestedViewDistance() + " farColumnTracked=" + tracked + " farColumnLoaded=" + (level.getChunkSource().getChunkNow(far.x(), far.z()) != null);
    }

    private static String pose(Minecraft minecraft) {
        Vec3 position = minecraft.player.position();
        return String.format("%.3f %.3f %.3f yaw=%.2f pitch=%.2f", position.x, position.y, position.z, minecraft.player.getYRot(), minecraft.player.getXRot());
    }

    /** The chunk columns held within the server's tracking disc of the render distance around the player, and how many that disc has. */
    private static int[] columns(Minecraft minecraft) {
        int centerX = SectionPos.blockToSectionCoord(minecraft.player.getBlockX());
        int centerZ = SectionPos.blockToSectionCoord(minecraft.player.getBlockZ());
        int radius = minecraft.options.getEffectiveRenderDistance();
        int held = 0;
        int total = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (!ChunkTrackingView.isWithinDistance(centerX, centerZ, radius, centerX + x, centerZ + z, true)) {
                    continue;
                }
                total++;
                if (minecraft.level.getChunkSource().getChunk(centerX + x, centerZ + z, ChunkStatus.FULL, false) != null) {
                    held++;
                }
            }
        }
        return new int[] {held, total};
    }

    private static List<UUID> build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime wormholes = runtime(server);
        ServerLevel overworld = server.overworld();
        ServerLevel nether = server.getLevel(Level.NETHER);
        ground(overworld, OVERWORLD_PORTAL, Blocks.STONE.defaultBlockState());
        ground(nether, NETHER_PORTAL, Blocks.NETHERRACK.defaultBlockState());
        MinecraftPortal source = wormholes.portals().create(actor.getUUID(), overworld, cells(OVERWORLD_PORTAL), PortalType.PORTAL, new Vec3(-1, 0, 0));
        MinecraftPortal destination = wormholes.portals().create(actor.getUUID(), nether, cells(NETHER_PORTAL), PortalType.PORTAL, new Vec3(-1, 0, 0));
        SeamlessScenario.assertTrue(source != null && destination != null, "crossing pair creation rejected");
        source.setAmbientStyle(AmbientParticleStyle.OFF);
        destination.setAmbientStyle(AmbientParticleStyle.OFF);
        SeamlessScenario.assertTrue(wormholes.portals().link(actor, source.getId(), destination.getId()), "crossing pair link rejected");
        SeamlessScenario.assertTrue(wormholes.portals().link(actor, destination.getId(), source.getId()), "crossing pair return link rejected");
        actor.setGameMode(GameType.CREATIVE);
        return List.of(source.getId(), destination.getId());
    }

    /** Open air over a floor on both sides of a portal site, enough to walk through and back. */
    private static void ground(ServerLevel level, BlockPos site, BlockState floor) {
        int centerZ = site.getZ() + APERTURE_WIDTH / 2;
        fill(level, site.getX() - 16, site.getY() - 2, centerZ - 10, site.getX() + 16, site.getY() + 12, centerZ + 10, Blocks.AIR.defaultBlockState());
        fill(level, site.getX() - 16, site.getY() - 2, centerZ - 10, site.getX() + 16, site.getY() - 1, centerZ + 10, floor);
    }

    private static void fill(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = y1; y <= y2; y++) {
                    level.setBlock(cursor.set(x, y, z), state, 3);
                }
            }
        }
    }

    private static void place(FabricSeamlessClient client, FabricSeamlessServer server, FabricConnection connection, ResourceKey<Level> dimension, BlockPos site) {
        Vec3 feet = new Vec3(site.getX() + START_DISTANCE, site.getY(), site.getZ() + APERTURE_WIDTH / 2 + 0.5D);
        server.computeOnServer(minecraftServer -> {
            ServerPlayer player = connection.getServerPlayer();
            return player.teleportTo(minecraftServer.getLevel(dimension), feet.x, feet.y, feet.z, Set.of(), 90.0F, 0.0F, false);
        });
        client.waitFor(minecraft -> minecraft.player != null && minecraft.level != null && minecraft.level.dimension() == dimension
            && minecraft.player.position().distanceTo(feet) < 0.05D, TIMEOUT_TICKS);
        client.lookAt(90.0F, 0.0F);
    }

    private static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(APERTURE_WIDTH * APERTURE_HEIGHT);
        for (int z = 0; z < APERTURE_WIDTH; z++) {
            for (int y = 0; y < APERTURE_HEIGHT; y++) {
                cells.add(min.offset(0, y, z));
            }
        }
        return cells;
    }
}
