package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftDyedItems;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

/**
 * Checks where streamed mesh views land on a backend without stencil views: a destination wall with four colored
 * quadrants must show through the aperture exactly where the portal transform puts it. Run with gametest.py --metal.
 */
public final class MeshBackendViewClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int EDGE = 3;
    private static final BlockPos SOURCE = new BlockPos(-108, 64, 192);
    private static final BlockPos DESTINATION = new BlockPos(-12, 76, 173);
    private static final int WALL_DISTANCE = 6;
    private static final int WALL_RADIUS = 6;
    private static final int TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 10;
    private static final double EYE_HEIGHT = 1.62D;
    private static final int PROBE_ATTEMPTS = 40;
    private static final int PROBE_INTERVAL_TICKS = 5;
    private static final double DOMINANCE = 1.2D;
    private static final double MAGENTA_DOMINANCE = 2.0D;
    private static final double EDGE_MARGIN = 0.15D;
    private static final int[] QUADRANT_COLORS = {0xFF0000, 0x0000FF, 0xFFFF00, 0xFFFFFF};
    private static final Pose[] POSES = {new Pose(0.0D, 0.0D, 8.0D), new Pose(0.0D, 0.0D, 3.0D), new Pose(1.0D, 0.5D, 4.0D), new Pose(-1.0D, -0.5D, 4.0D)};

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
                if (!ClientGui.hudHidden(minecraft)) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            List<String> failures = new ArrayList<>();
            for (Pose pose : POSES) {
                view(client, server, connection, pose);
                probe(client, pose, failures);
            }
            view(client, server, connection, POSES[0]);
            client.runOnClient(minecraft -> minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            client.waitTicks(SETTLE_TICKS);
            capture(client, output(client, "third-person"));
            client.runOnClient(minecraft -> minecraft.options.setCameraType(CameraType.FIRST_PERSON));
            server.removePortals(portals);
            SeamlessScenario.assertTrue(failures.isEmpty(), String.join("; ", failures));
        }
    }

    private static List<UUID> build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        scene(level, SOURCE, MinecraftDyedItems.concreteBlock(DyeColor.MAGENTA).defaultBlockState(), MinecraftDyedItems.concreteBlock(DyeColor.MAGENTA).defaultBlockState(),
            MinecraftDyedItems.concreteBlock(DyeColor.MAGENTA).defaultBlockState(), MinecraftDyedItems.concreteBlock(DyeColor.MAGENTA).defaultBlockState());
        scene(level, DESTINATION, MinecraftDyedItems.concreteBlock(DyeColor.RED).defaultBlockState(), MinecraftDyedItems.concreteBlock(DyeColor.BLUE).defaultBlockState(),
            MinecraftDyedItems.concreteBlock(DyeColor.YELLOW).defaultBlockState(), MinecraftDyedItems.concreteBlock(DyeColor.WHITE).defaultBlockState());
        MinecraftPortal source = runtime.portals().create(actor.getUUID(), level, cells(SOURCE), PortalType.WORMHOLE, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(actor.getUUID(), level, cells(DESTINATION), PortalType.WORMHOLE, new Vec3(0, 0, -1));
        SeamlessScenario.assertTrue(source != null && destination != null, "mesh backend portal creation rejected");
        source.setAmbientStyle(AmbientParticleStyle.OFF);
        destination.setAmbientStyle(AmbientParticleStyle.OFF);
        SeamlessScenario.assertTrue(runtime.portals().link(actor, source.getId(), destination.getId()), "mesh backend source link rejected");
        SeamlessScenario.assertTrue(runtime.portals().link(actor, destination.getId(), source.getId()), "mesh backend return link rejected");
        actor.setGameMode(GameType.CREATIVE);
        actor.getAbilities().mayfly = true;
        actor.getAbilities().flying = true;
        actor.onUpdateAbilities();
        return List.of(source.getId(), destination.getId());
    }

    private static void scene(ServerLevel level, BlockPos min, BlockState lowerLeft, BlockState lowerRight, BlockState upperLeft, BlockState upperRight) {
        int wallZ = min.getZ() - WALL_DISTANCE;
        int centerX = min.getX() + EDGE / 2;
        int centerY = min.getY() + EDGE / 2;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -WALL_RADIUS; x <= WALL_RADIUS; x++) {
            for (int y = -WALL_RADIUS; y <= WALL_RADIUS; y++) {
                for (int z = wallZ - 1; z <= min.getZ() + 16; z++) {
                    level.setBlockAndUpdate(cursor.set(centerX + x, centerY + y, z), Blocks.AIR.defaultBlockState());
                }
                BlockState color = y < 0 ? (x < 0 ? lowerLeft : lowerRight) : (x < 0 ? upperLeft : upperRight);
                level.setBlockAndUpdate(cursor.set(centerX + x, centerY + y, wallZ), color);
            }
        }
    }

    private static void view(FabricSeamlessClient client, FabricSeamlessServer server, FabricConnection connection, Pose pose) {
        Vec3 eye = pose.eye();
        server.computeOnServer(minecraftServer -> {
            ServerLevel level = minecraftServer.overworld();
            ServerPlayer player = connection.getServerPlayer();
            level.setBlockAndUpdate(BlockPos.containing(eye.x, eye.y - EYE_HEIGHT - 0.5D, eye.z), Blocks.BARRIER.defaultBlockState());
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            return player.teleportTo(level, eye.x, eye.y - EYE_HEIGHT, eye.z, Set.of(), 180.0F, 0.0F, false);
        });
        client.waitFor(minecraft -> minecraft.player.position().distanceTo(new Vec3(eye.x, eye.y - EYE_HEIGHT, eye.z)) < 0.05D, TIMEOUT_TICKS);
        client.lookAt(180.0F, 0.0F);
        client.waitForChunksRender();
        client.waitFor(minecraft -> ClientPortalRenderer.instance().drawnViews() > 0, TIMEOUT_TICKS);
        client.waitTicks(SETTLE_TICKS);
    }

    private static void probe(FabricSeamlessClient client, Pose pose, List<String> failures) {
        Path output = output(client, "dx" + pose.dx() + "-dy" + pose.dy() + "-d" + pose.distance());
        List<String> attempt = new ArrayList<>();
        for (int tries = 0; tries < PROBE_ATTEMPTS; tries++) {
            attempt.clear();
            FramebufferProbe frame = capture(client, output);
            Vec3 eye = client.computeOnClient(minecraft -> ClientRenderAccess.mainCamera(minecraft).position());
            edges(client, frame, output.getFileName().toString(), attempt);
            for (int quadrant = 0; quadrant < QUADRANT_COLORS.length; quadrant++) {
                Vec3 throughAperture = aperturePoint(eye, quadrant);
                if (throughAperture == null) {
                    continue;
                }
                int[] pixel = client.computeOnClient(minecraft -> FramebufferProbe.screen(minecraft, throughAperture));
                int color = pixel == null ? 0 : frame.average(pixel[0], pixel[1], 2);
                if (pixel == null || !matches(color, QUADRANT_COLORS[quadrant])) {
                    attempt.add(output.getFileName() + " quadrant " + quadrant + " read #" + Integer.toHexString(color & 0xFFFFFF));
                }
            }
            if (attempt.isEmpty()) {
                return;
            }
            LOGGER.info("[{}] try {}: eye {}: {}", output.getFileName(), tries, client.computeOnClient(minecraft -> ClientRenderAccess.mainCamera(minecraft).position()), attempt);
            client.waitTicks(PROBE_INTERVAL_TICKS);
        }
        failures.addAll(attempt);
    }

    private static void edges(FabricSeamlessClient client, FramebufferProbe frame, String name, List<String> failures) {
        double z = SOURCE.getZ() + 0.5D;
        double[][] points = {{SOURCE.getX() - EDGE_MARGIN, SOURCE.getY() + 1.5D, 0}, {SOURCE.getX() + EDGE_MARGIN, SOURCE.getY() + 1.5D, 1},
            {SOURCE.getX() + EDGE + EDGE_MARGIN, SOURCE.getY() + 1.5D, 0}, {SOURCE.getX() + EDGE - EDGE_MARGIN, SOURCE.getY() + 1.5D, 1},
            {SOURCE.getX() + 1.5D, SOURCE.getY() - EDGE_MARGIN, 0}, {SOURCE.getX() + 1.5D, SOURCE.getY() + EDGE_MARGIN, 1},
            {SOURCE.getX() + 1.5D, SOURCE.getY() + EDGE + EDGE_MARGIN, 0}, {SOURCE.getX() + 1.5D, SOURCE.getY() + EDGE - EDGE_MARGIN, 1}};
        for (double[] point : points) {
            int[] pixel = client.computeOnClient(minecraft -> FramebufferProbe.screen(minecraft, new Vec3(point[0], point[1], z)));
            if (pixel == null) {
                continue;
            }
            int color = frame.average(pixel[0], pixel[1], 1);
            int red = (color >> 16) & 0xFF;
            int blue = color & 0xFF;
            boolean magenta = Math.min(red, blue) > ((color >> 8) & 0xFF) * MAGENTA_DOMINANCE;
            if (magenta == (point[2] == 1)) {
                failures.add(name + " aperture edge at " + point[0] + "," + point[1] + (point[2] == 1 ? " inside" : " outside") + " read #" + Integer.toHexString(color & 0xFFFFFF));
            }
        }
    }

    /** The aperture point on the eye's line to a quadrant of the destination wall, or null when that line misses the aperture. */
    private static Vec3 aperturePoint(Vec3 eye, int quadrant) {
        double centerX = SOURCE.getX() + EDGE / 2 + 0.5D;
        double centerY = SOURCE.getY() + EDGE / 2 + 0.5D;
        double wallZ = SOURCE.getZ() - WALL_DISTANCE + 1.0D;
        Vec3 wall = new Vec3(centerX + ((quadrant & 1) == 0 ? -3.0D : 3.0D), centerY + (quadrant < 2 ? -3.0D : 3.0D), wallZ);
        double plane = SOURCE.getZ() + 0.5D;
        double t = (plane - eye.z) / (wall.z - eye.z);
        Vec3 hit = eye.add(wall.subtract(eye).scale(t));
        boolean inside = hit.x > SOURCE.getX() + 0.1D && hit.x < SOURCE.getX() + EDGE - 0.1D && hit.y > SOURCE.getY() + 0.1D && hit.y < SOURCE.getY() + EDGE - 0.1D;
        return inside ? hit : null;
    }

    private static boolean matches(int color, int expected) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        return switch (expected) {
            case 0xFF0000 -> red > green * DOMINANCE && red > blue * DOMINANCE;
            case 0x0000FF -> blue > red * DOMINANCE && blue > green * DOMINANCE;
            case 0xFFFF00 -> red > blue * DOMINANCE && green > blue * DOMINANCE;
            default -> red > 150 && green > 150 && blue > 150;
        };
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

    private static Path output(FabricSeamlessClient client, String name) {
        return client.computeOnClient((Minecraft minecraft) -> minecraft.gameDirectory.toPath().resolve("screenshots/mesh-backend/" + name.replace('.', '_') + ".png"));
    }

    private static FramebufferProbe capture(FabricSeamlessClient client, Path output) {
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        client.runOnClient(minecraft -> FramebufferProbe.capture(minecraft, output, captured::set));
        client.waitFor(minecraft -> captured.get() != null, TIMEOUT_TICKS);
        return captured.get();
    }

    private record Pose(double dx, double dy, double distance) {
        Vec3 eye() {
            return new Vec3(SOURCE.getX() + EDGE / 2.0D + dx, SOURCE.getY() + EDGE / 2.0D + dy, SOURCE.getZ() + 0.5D + distance);
        }
    }
}
