package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.MinecraftDyedItems;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencil;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
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
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

final class PortalStencilResize {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String DECLARED_FRAME_SIZE = "automator.declaredFrameSize";
    private static final int EDGE = 7;
    private static final BlockPos SOURCE_MIN = new BlockPos(1000, 81, 1000);
    private static final BlockPos DESTINATION_MIN = SOURCE_MIN.offset(0, 0, 60);
    private static final int FLOOR_Y = 75;
    private static final int WALL_OFFSET = 4;
    private static final int CLEARED_MARGIN = 12;
    private static final int WALL_X = SOURCE_MIN.getX() - CLEARED_MARGIN;
    private static final int CLEARED_LENGTH = 90;
    private static final int WALL_WIDTH = 32;
    private static final int WALL_HEIGHT = 25;
    private static final int FULL_SKY_LIGHT = 15;
    private static final double VIEW_DISTANCE = 9.0D;
    private static final double EYE_HEIGHT = 1.62D;
    private static final double OUTSIDE_OFFSET = 2.0D;
    private static final Vec3 VIEW_POSITION = new Vec3(SOURCE_MIN.getX() + EDGE / 2.0D, SOURCE_MIN.getY() + EDGE / 2.0D - EYE_HEIGHT,
        SOURCE_MIN.getZ() + 0.5D + VIEW_DISTANCE);
    private static final double[] CENTER = {0.0D, 0.0D};
    private static final double[] CORNER = {0.5D / (EDGE / 2.0D) - 1.0D, 0.5D / (EDGE / 2.0D) - 1.0D};
    private static final int VIEW_TIMEOUT_TICKS = 600;
    private static final int RESIZE_TIMEOUT_TICKS = 100;
    private static final int CLAIM_TIMEOUT_TICKS = 100;
    private static final int CAPTURE_TIMEOUT_TICKS = 40;
    private static final int PROBE_ATTEMPTS = 30;
    private static final int PROBE_INTERVAL_TICKS = 5;
    private static final int SETTLE_TICKS = 10;
    private static final int SAMPLE_RADIUS = 2;
    private static final double DOMINANCE = 1.15D;
    private static final ShapeDescriptor CIRCLE = ShapeDescriptor.parse("circle");
    private static final BlockState LIME = MinecraftDyedItems.concreteBlock(DyeColor.LIME).defaultBlockState();
    private static final BlockState MAGENTA = MinecraftDyedItems.concreteBlock(DyeColor.MAGENTA).defaultBlockState();

    private PortalStencilResize() {
    }

    static void run(SeamlessClient client, SeamlessServer server, String label) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        List<UUID> portals = server.buildStencilResize();
        ClientOptions options = client.computeOnClient(minecraft -> new ClientOptions(ClientGui.hudHidden(minecraft),
            minecraft.options.getCameraType()));
        Size original = client.computeOnClient(minecraft -> new Size(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight()));
        Size shrunk = new Size(original.width() * 3 / 4, original.height() * 3 / 4);
        try {
            client.runOnClient(minecraft -> {
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                hud(minecraft, true);
            });
            view(client);
            verify(client, label, "initial", original);
            System.setProperty(DECLARED_FRAME_SIZE, shrunk.width() + "x" + shrunk.height());
            client.resizeWindow(shrunk.width(), shrunk.height());
            verify(client, label, "shrunk", shrunk);
            client.resizeWindow(original.width(), original.height());
            verify(client, label, "restored", original);
        } finally {
            restore(client, original);
            client.runOnClient(minecraft -> {
                hud(minecraft, options.hudHidden());
                minecraft.options.setCameraType(options.camera());
            });
            server.removePortals(portals);
        }
    }

    static List<UUID> build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        fill(level, new BlockPos(WALL_X, FLOOR_Y + 1, SOURCE_MIN.getZ() - CLEARED_MARGIN), WALL_WIDTH, WALL_HEIGHT + 1, CLEARED_LENGTH, Blocks.AIR.defaultBlockState());
        fill(level, new BlockPos(WALL_X, FLOOR_Y, SOURCE_MIN.getZ() - CLEARED_MARGIN), WALL_WIDTH, 1, CLEARED_LENGTH, Blocks.STONE.defaultBlockState());
        fill(level, new BlockPos(WALL_X, FLOOR_Y + 1, SOURCE_MIN.getZ() - WALL_OFFSET), WALL_WIDTH, WALL_HEIGHT, 1, MAGENTA);
        fill(level, new BlockPos(WALL_X, FLOOR_Y + 1, DESTINATION_MIN.getZ() - WALL_OFFSET), WALL_WIDTH, WALL_HEIGHT, 1, LIME);
        MinecraftPortal source = runtime.portals().create(actor.getUUID(), level, cells(SOURCE_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(actor.getUUID(), level, cells(DESTINATION_MIN), PortalType.PORTAL, new Vec3(0, 0, -1));
        SeamlessScenario.assertTrue(source != null && destination != null, "stencil resize portal creation rejected");
        source.setAmbientStyle(AmbientParticleStyle.OFF);
        destination.setAmbientStyle(AmbientParticleStyle.OFF);
        SeamlessScenario.assertTrue(runtime.portals().link(actor, source.getId(), destination.getId()), "stencil resize source link rejected");
        SeamlessScenario.assertTrue(runtime.portals().link(actor, destination.getId(), source.getId()), "stencil resize return link rejected");
        boolean[] shaped = new boolean[1];
        SeamlessScenario.assertTrue(runtime.portals().update(actor, source.getId(), target -> shaped[0] = target.setApertureShape(CIRCLE)) && shaped[0],
            "stencil resize circle shape refused");
        actor.setGameMode(GameType.CREATIVE);
        actor.teleportTo(level, VIEW_POSITION.x, VIEW_POSITION.y, VIEW_POSITION.z, Set.of(), 180.0F, 0.0F, false);
        actor.getAbilities().mayfly = true;
        actor.getAbilities().flying = true;
        actor.onUpdateAbilities();
        return List.of(source.getId(), destination.getId());
    }

    private static void view(SeamlessClient client) {
        client.waitFor(minecraft -> minecraft.player.position().distanceTo(VIEW_POSITION) < 0.05D && minecraft.player.getAbilities().flying,
            VIEW_TIMEOUT_TICKS);
        client.lookAt(180.0F, 0.0F);
        client.waitFor(minecraft -> Math.abs(Math.IEEEremainder(minecraft.player.getYRot() - 180.0D, 360.0D)) < 0.1D
            && Math.abs(minecraft.player.getXRot()) < 0.1D, VIEW_TIMEOUT_TICKS);
        client.waitForChunksRender();
        BlockPos wall = new BlockPos(DESTINATION_MIN.getX() + EDGE / 2, DESTINATION_MIN.getY() + EDGE / 2, DESTINATION_MIN.getZ() - WALL_OFFSET);
        client.waitFor(minecraft -> {
            PortalView view = sourceView();
            return view != null && view.shaped() && view.surface().geometry().shape().equals(CIRCLE)
                && LIME.equals(view.destination().getBlockState(wall));
        }, VIEW_TIMEOUT_TICKS);
        awaitTicks(client, minecraft -> {
            PortalView view = sourceView();
            return view != null && litWall(minecraft.level, SOURCE_MIN.getZ() - WALL_OFFSET + 1)
                && litWall(view.destination(), DESTINATION_MIN.getZ() - WALL_OFFSET + 1);
        }, VIEW_TIMEOUT_TICKS, "the backdrop walls never reached full sky light on the client");
        client.waitTicks(SETTLE_TICKS);
    }

    private static boolean litWall(Level level, int z) {
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int x = WALL_X; x < WALL_X + WALL_WIDTH; x++) {
            for (int y = FLOOR_Y + 1; y < FLOOR_Y + 1 + WALL_HEIGHT; y++) {
                if (level.getBrightness(LightLayer.SKY, position.set(x, y, z)) < FULL_SKY_LIGHT) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void verify(SeamlessClient client, String label, String phase, Size size) {
        String name = label + " " + phase + " " + size.width() + "x" + size.height();
        awaitTicks(client, minecraft -> mainTargetIs(minecraft, size), RESIZE_TIMEOUT_TICKS,
            name + ": the main render target never reached the window size");
        Path output = output(client, label + "-" + phase);
        boolean stencil = client.computeOnClient(minecraft -> PortalStencil.available(ClientRenderAccess.mainTarget(minecraft)));
        if (!stencil) {
            client.waitTicks(SETTLE_TICKS);
            capture(client, output);
        }
        SeamlessScenario.assertTrue(stencil, name + ": the main render target has no stencil attachment (capture " + output + ")");
        awaitTicks(client, minecraft -> {
            PortalView view = sourceView();
            return view != null && PortalStencilRenderer.instance().claims(view.surface().geometry());
        }, CLAIM_TIMEOUT_TICKS, name + ": no stencil portal view rendered ("
            + client.computeOnClient(minecraft -> PortalStencilRenderer.instance().debugLine()) + ")");
        client.waitTicks(SETTLE_TICKS);
        probe(client, name, output, size);
    }

    private static void restore(SeamlessClient client, Size original) {
        client.resizeWindow(original.width(), original.height());
        for (int tick = 0; tick < RESIZE_TIMEOUT_TICKS && !client.computeOnClient(minecraft -> mainTargetIs(minecraft, original)); tick++) {
            client.waitTicks(1);
        }
        System.clearProperty(DECLARED_FRAME_SIZE);
    }

    private static boolean mainTargetIs(Minecraft minecraft, Size size) {
        RenderTarget main = ClientRenderAccess.mainTarget(minecraft);
        return main.width == size.width() && main.height == size.height();
    }

    private static void probe(SeamlessClient client, String name, Path output, Size size) {
        String failure = null;
        for (int attempt = 0; attempt < PROBE_ATTEMPTS; attempt++) {
            FramebufferProbe probe = capture(client, output);
            if (probe.width() != size.width() || probe.height() != size.height()) {
                failure = "the capture is " + probe.width() + "x" + probe.height();
                client.waitTicks(PROBE_INTERVAL_TICKS);
                continue;
            }
            int[] colors = client.computeOnClient(minecraft -> samples(minecraft, probe));
            if (colors == null) {
                failure = "a sample point is off screen";
                break;
            }
            String reading = "aperture center #" + hex(colors[0]) + ", circle corner #" + hex(colors[1]) + ", beside the aperture #" + hex(colors[2]);
            LOGGER.info("[{}] attempt {}: {}", name, attempt, reading);
            if (lime(colors[0])) {
                failure = magenta(colors[1]) && magenta(colors[2]) ? null
                    : "the destination is not clipped to the circular aperture: " + reading;
                break;
            }
            failure = "the destination never appeared through the aperture: " + reading;
            client.waitTicks(PROBE_INTERVAL_TICKS);
        }
        SeamlessScenario.assertTrue(failure == null, name + ": " + failure + " (capture " + output + ")");
        LOGGER.info("[{}] stencil portal view clipped, capture {}", name, output);
    }

    private static Path output(SeamlessClient client, String file) {
        return client.computeOnClient(minecraft -> minecraft.gameDirectory.toPath()
            .resolve("screenshots/stencil-resize/" + file.replaceAll("[^a-zA-Z0-9-]", "-") + ".png"));
    }

    private static FramebufferProbe capture(SeamlessClient client, Path output) {
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        client.runOnClient(minecraft -> FramebufferProbe.capture(minecraft, output, captured::set));
        client.waitFor(minecraft -> captured.get() != null, CAPTURE_TIMEOUT_TICKS);
        return captured.get();
    }

    private static int[] samples(Minecraft minecraft, FramebufferProbe probe) {
        PortalView view = sourceView();
        if (view == null) {
            return null;
        }
        ApertureDescriptor geometry = view.surface().geometry();
        Vec3 beside = new Vec3(SOURCE_MIN.getX() - OUTSIDE_OFFSET, SOURCE_MIN.getY() + EDGE / 2.0D, SOURCE_MIN.getZ() + 0.5D);
        List<Vec3> points = List.of(world(geometry, CENTER), world(geometry, CORNER), beside);
        int[] colors = new int[points.size()];
        for (int index = 0; index < colors.length; index++) {
            int[] pixel = FramebufferProbe.screen(minecraft, points.get(index));
            if (pixel == null) {
                return null;
            }
            colors[index] = probe.average(pixel[0], pixel[1], SAMPLE_RADIUS);
        }
        return colors;
    }

    private static PortalView sourceView() {
        for (PortalView view : WormholesClient.instance().portalViews().current()) {
            ApertureDescriptor geometry = view.surface().geometry();
            if (view.kind() == PortalView.Kind.ARM && geometry.originX() == SOURCE_MIN.getX()
                && geometry.originY() == SOURCE_MIN.getY() && geometry.originZ() == SOURCE_MIN.getZ()) {
                return view;
            }
        }
        return null;
    }

    private static Vec3 world(ApertureDescriptor geometry, double[] unit) {
        AperturePolygon aperture = AperturePolygon.from(geometry);
        double[] cell = new double[2];
        aperture.planeShape().placement().pointInto(unit[0], unit[1], cell);
        Vec3d point = aperture.point(cell[0], cell[1]);
        return new Vec3(point.x(), point.y(), point.z());
    }

    private static void awaitTicks(SeamlessClient client, Predicate<Minecraft> condition, int timeoutTicks, String failure) {
        for (int tick = 0; tick < timeoutTicks; tick++) {
            if (client.computeOnClient(condition::test)) {
                return;
            }
            client.waitTicks(1);
        }
        throw new AssertionError(failure);
    }

    private static void hud(Minecraft minecraft, boolean hidden) {
        if (ClientGui.hudHidden(minecraft) != hidden) {
            ClientGui.toggleHud(minecraft);
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

    private static void fill(ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ, BlockState state) {
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    level.setBlockAndUpdate(min.offset(x, y, z), state);
                }
            }
        }
    }

    private static boolean lime(int color) {
        return green(color) > red(color) * DOMINANCE && green(color) > blue(color) * DOMINANCE;
    }

    private static boolean magenta(int color) {
        return red(color) > green(color) * DOMINANCE && blue(color) > green(color) * DOMINANCE;
    }

    private static String hex(int color) {
        return Integer.toHexString(color & 0xFFFFFF);
    }

    private static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    private static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    private static int blue(int color) {
        return color & 0xFF;
    }

    private record Size(int width, int height) {
    }

    private record ClientOptions(boolean hudHidden, CameraType camera) {
    }
}
