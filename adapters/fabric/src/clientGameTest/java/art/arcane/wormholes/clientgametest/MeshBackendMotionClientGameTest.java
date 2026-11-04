package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftDyedItems;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

/**
 * A temporal renderer reprojects last frame from the depth buffer, so a portal view holds still in motion only if that depth
 * describes the scene behind the aperture. The edges of a checker far beyond the aperture must stay straight while the camera strafes.
 */
public final class MeshBackendMotionClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int EDGE = 7;
    private static final BlockPos SOURCE = new BlockPos(1000, 100, 1000);
    private static final BlockPos DESTINATION = new BlockPos(1000, 100, 1200);
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
    private static final int APERTURE_INSET = 4;
    private static final double MAXIMUM_EDGE_SHIFT = 0.12D;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enable();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FabricConnection connection = FabricConnection.of(singleplayer);
            FabricSeamlessClient client = new FabricSeamlessClient(context, connection);
            FabricSeamlessServer server = new FabricSeamlessServer(singleplayer.getServer(), connection);
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            List<java.util.UUID> portals = server.computeOnServer(minecraftServer -> build(minecraftServer.overworld(), connection.getServerPlayer(), runtime(minecraftServer), minecraftServer));
            client.runOnClient(minecraft -> {
                if (!ClientGui.hudHidden(minecraft)) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            place(client, server, connection, 0.0D);
            client.waitTicks(100);
            client.waitForChunksRender();
            double still = edgeShift(client, "still");
            for (int tick = 0; tick < STRAFE_TICKS; tick++) {
                strafe(client);
                client.waitTicks(1);
            }
            double moving = 0.0D;
            for (int sample = 0; sample < SAMPLES; sample++) {
                strafe(client);
                client.waitTicks(1);
                moving += edgeShift(client, "moving-" + sample) / SAMPLES;
            }
            client.runOnClient(minecraft -> minecraft.player.setDeltaMovement(Vec3.ZERO));
            LOGGER.info("[mesh-motion] mean edge shift still {} moving {}", still, moving);
            server.removePortals(portals);
            SeamlessScenario.assertTrue(moving <= MAXIMUM_EDGE_SHIFT, "the view wobbles in motion: edges shift " + moving + " px between checker rows, " + still + " standing still");
        }
    }

    private static List<java.util.UUID> build(ServerLevel level, ServerPlayer actor, WormholesModRuntime wormholes, net.minecraft.server.MinecraftServer server) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        BlockState white = MinecraftDyedItems.concreteBlock(DyeColor.WHITE).defaultBlockState();
        BlockState black = MinecraftDyedItems.concreteBlock(DyeColor.BLACK).defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -WALL_RADIUS; x <= WALL_RADIUS; x++) {
            for (int y = -WALL_RADIUS; y <= WALL_RADIUS; y++) {
                level.setBlockAndUpdate(cursor.set(DESTINATION.getX() + x, DESTINATION.getY() + y, DESTINATION.getZ() - WALL_DISTANCE), ((x + y) & 1) == 0 ? white : black);
                level.setBlockAndUpdate(cursor.set(SOURCE.getX() + x, SOURCE.getY() + y, SOURCE.getZ() - 1), Blocks.AIR.defaultBlockState());
            }
        }
        MinecraftPortal source = wormholes.portals().create(actor.getUUID(), level, cells(SOURCE), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = wormholes.portals().create(actor.getUUID(), level, cells(DESTINATION), PortalType.PORTAL, new Vec3(0, 0, -1));
        SeamlessScenario.assertTrue(source != null && destination != null, "motion portal creation rejected");
        source.setAmbientStyle(AmbientParticleStyle.OFF);
        destination.setAmbientStyle(AmbientParticleStyle.OFF);
        SeamlessScenario.assertTrue(wormholes.portals().link(actor, source.getId(), destination.getId()), "motion source link rejected");
        SeamlessScenario.assertTrue(wormholes.portals().link(actor, destination.getId(), source.getId()), "motion return link rejected");
        actor.setGameMode(GameType.CREATIVE);
        actor.getAbilities().mayfly = true;
        actor.getAbilities().flying = true;
        actor.onUpdateAbilities();
        return List.of(source.getId(), destination.getId());
    }

    private static void place(FabricSeamlessClient client, FabricSeamlessServer server, FabricConnection connection, double offset) {
        Vec3 eye = new Vec3(SOURCE.getX() + EDGE / 2.0D + offset - 1.2D, SOURCE.getY() + EDGE / 2.0D, SOURCE.getZ() + 0.5D + VIEW_DISTANCE);
        server.computeOnServer(minecraftServer -> connection.getServerPlayer().teleportTo(minecraftServer.overworld(), eye.x, eye.y - EYE_HEIGHT, eye.z, Set.of(), 180.0F, 0.0F, false));
    }

    private static void strafe(FabricSeamlessClient client) {
        client.runOnClient(minecraft -> minecraft.player.setDeltaMovement(STRAFE_PER_TICK, 0.0D, 0.0D));
    }

    /** How far the checker's vertical edges wander between rows two cells apart: a flat wall facing the camera keeps them straight. */
    private static double edgeShift(FabricSeamlessClient client, String name) {
        Path output = client.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots/mesh-motion/" + name + ".png"));
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        client.runOnClient(minecraft -> FramebufferProbe.capture(minecraft, output, captured::set));
        client.waitFor(minecraft -> captured.get() != null, TIMEOUT_TICKS);
        FramebufferProbe frame = captured.get();
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
        minX += APERTURE_INSET;
        maxX -= APERTURE_INSET;
        List<Double> center = edges(frame, frame.height() / 2, minX, maxX);
        SeamlessScenario.assertTrue(center.size() > 10, name + ": the checker never showed through the aperture");
        int pitch = (int) Math.round((center.getLast() - center.getFirst()) / (center.size() - 1));
        double total = 0.0D;
        int count = 0;
        for (int y = frame.height() / 4; y + 2 * pitch < frame.height() * 3 / 4; y++) {
            List<Double> below = edges(frame, y + 2 * pitch, minX, maxX);
            for (double edge : edges(frame, y, minX, maxX)) {
                double nearest = Double.MAX_VALUE;
                for (double other : below) {
                    nearest = Math.min(nearest, Math.abs(edge - other));
                }
                if (nearest < pitch / 2.0D) {
                    total += nearest;
                    count++;
                }
            }
        }
        return total / count;
    }

    private static boolean crossing(FramebufferProbe frame, int x, int y) {
        return luma(frame.read(x - 1, y)) > EDGE_LUMA != luma(frame.read(x, y)) > EDGE_LUMA;
    }

    private static List<Double> edges(FramebufferProbe frame, int y, int minX, int maxX) {
        List<Double> edges = new ArrayList<>();
        for (int x = minX + 1; x < maxX; x++) {
            if (crossing(frame, x, y)) {
                double before = luma(frame.read(x - 1, y));
                edges.add(x - 1 + (EDGE_LUMA - before) / (luma(frame.read(x, y)) - before));
            }
        }
        return edges;
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
