package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalBackends;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.portal.PortalType;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ShaderOcclusionClientGameTest implements FabricClientGameTest {
    private static final int TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = 80;
    private static final int CHECK_FRAMES = 6;
    private static final BlockPos NEAR_SOURCE = new BlockPos(48, 70, 20);
    private static final BlockPos FAR_SOURCE = new BlockPos(48, 70, 16);
    private static final BlockPos NEAR_DESTINATION = new BlockPos(72, 70, 20);
    private static final BlockPos FAR_DESTINATION = new BlockPos(96, 70, 20);
    private static final BlockPos FOREGROUND = new BlockPos(46, 70, 24);
    private static final Vec3 CAMERA_POSITION = new Vec3(49.5D, 70.0D, 27.5D);
    private static final Vec3 APERTURE_CENTER = new Vec3(49.5D, 71.5D, 20.5D);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        assertTrue(context.computeOnClient(client -> PortalBackends.pipeline().deferred()),
            "shader occlusion requires an enabled shader pack");
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksDownload();
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), TIMEOUT_TICKS);
            TestServerContext server = singleplayer.getServer();
            ServerPlayer player = server.computeOnServer(minecraftServer -> singleplayer.getConnection().getServerPlayer());
            List<UUID> created = new ArrayList<>();
            ClientOptions options = context.computeOnClient(client -> new ClientOptions(client.gui.hud.isHidden(),
                client.options.getCameraType(), WormholesClient.instance().config().clientRecursion));
            try {
                Scene scene = server.computeOnServer(minecraftServer -> build(minecraftServer, player, created));
                context.runOnClient(client -> {
                    client.options.setCameraType(CameraType.FIRST_PERSON);
                    WormholesClient.instance().config().clientRecursion = false;
                    if (!client.gui.hud.isHidden()) {
                        client.gui.hud.toggle();
                    }
                });
                server.runOnServer(minecraftServer -> {
                    player.setGameMode(GameType.CREATIVE);
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                    player.teleportTo(player.level(), CAMERA_POSITION.x, CAMERA_POSITION.y, CAMERA_POSITION.z, Set.of(), 180.0F, 0.0F, false);
                    player.setDeltaMovement(Vec3.ZERO);
                });
                context.waitFor(client -> client.player.position().distanceTo(CAMERA_POSITION) < 0.05D, TIMEOUT_TICKS);
                context.getInput().lookAt(180.0F, 0.0F);
                singleplayer.getConnection().waitForChunksRender();
                awaitView(context, FAR_SOURCE, true);
                Crop crop = context.computeOnClient(client -> {
                    int[] center = FramebufferProbe.screen(client, APERTURE_CENTER);
                    assertTrue(center != null, "the overlapping portal apertures must be visible");
                    int radius = Math.max(3, client.gameRenderer.mainRenderTarget().width / 160);
                    return new Crop(center[0] - radius, center[1] - radius, radius * 2 + 1);
                });
                int[] far = capture(context, crop);
                context.takeScreenshot("shader-occlusion-far-calibration");
                remove(server, player, scene.farSource());
                awaitView(context, FAR_SOURCE, false);
                UUID near = server.computeOnServer(minecraftServer -> source(minecraftServer, player, NEAR_SOURCE, scene.nearDestination(), created));
                awaitView(context, NEAR_SOURCE, true);
                int[] baseline = capture(context, crop);
                double contrast = difference(baseline, far);
                assertTrue(contrast >= 18.0D, "near and far destination markers are not distinguishable: " + contrast);
                context.takeScreenshot("shader-occlusion-near-baseline");
                UUID distant = server.computeOnServer(minecraftServer -> source(minecraftServer, player, FAR_SOURCE, scene.farDestination(), created));
                awaitView(context, FAR_SOURCE, true);
                assertTrue(context.computeOnClient(client -> view(NEAR_SOURCE) && view(FAR_SOURCE)
                    && PortalStencilRenderer.instance().deferredActive()), "both native portal views must use the deferred shader renderer");
                double tolerance = Math.max(10.0D, contrast * 0.25D);
                for (int frame = 0; frame < CHECK_FRAMES; frame++) {
                    int[] actual = capture(context, crop);
                    double nearDifference = difference(baseline, actual);
                    double farDifference = difference(far, actual);
                    if (nearDifference > tolerance || nearDifference >= farDifference * 0.5D) {
                        context.takeScreenshot("shader-occlusion-overlap-failed");
                    }
                    assertTrue(nearDifference <= tolerance && nearDifference < farDifference * 0.5D,
                        "farther portal replaced the nearer marker at frame " + frame + ": near=" + nearDifference
                            + ", far=" + farDifference + ", tolerance=" + tolerance + ", crop=" + crop);
                    context.waitTicks(2);
                }
                context.takeScreenshot("shader-occlusion-overlap");
                server.runOnServer(minecraftServer -> fill(minecraftServer.overworld(), FOREGROUND, 7, 5, 1,
                    Blocks.CONCRETE.pick(DyeColor.GREEN).defaultBlockState()));
                singleplayer.getConnection().waitForChunksRender();
                context.waitTicks(SETTLE_TICKS);
                int[] blocked = capture(context, crop);
                context.takeScreenshot("shader-occlusion-foreground-active");
                remove(server, player, near);
                remove(server, player, distant);
                awaitView(context, NEAR_SOURCE, false);
                awaitView(context, FAR_SOURCE, false);
                int[] wall = capture(context, crop);
                double wallContrast = Math.min(difference(wall, baseline), difference(wall, far));
                assertTrue(wallContrast >= 18.0D, "foreground wall is not distinguishable from either portal marker: " + wallContrast);
                double wallDifference = difference(blocked, wall);
                assertTrue(wallDifference <= Math.max(10.0D, wallContrast * 0.25D),
                    "portal content drew through the opaque foreground wall: difference=" + wallDifference + ", contrast=" + wallContrast);
                context.takeScreenshot("shader-occlusion-foreground-baseline");
            } finally {
                context.runOnClient(client -> {
                    client.options.setCameraType(options.camera());
                    if (WormholesClient.instance() != null) {
                        WormholesClient.instance().config().clientRecursion = options.recursion();
                    }
                    if (client.gui.hud.isHidden() != options.hidden()) {
                        client.gui.hud.toggle();
                    }
                });
                server.runOnServer(minecraftServer -> {
                    WormholesModRuntime runtime = runtime(minecraftServer);
                    for (UUID id : created) {
                        if (runtime.portals().get(id) != null) {
                            runtime.portals().remove(player, id);
                        }
                    }
                });
            }
        }
    }

    private static Scene build(MinecraftServer server, ServerPlayer player, List<UUID> created) {
        ServerLevel level = server.overworld();
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        fill(level, new BlockPos(42, 70, 0), 15, 9, 34, Blocks.AIR.defaultBlockState());
        fill(level, new BlockPos(42, 69, 0), 15, 1, 34, Blocks.SEA_LANTERN.defaultBlockState());
        fill(level, new BlockPos(42, 70, 0), 15, 9, 1, Blocks.STONE.defaultBlockState());
        markerRoom(level, NEAR_DESTINATION, DyeColor.RED);
        markerRoom(level, FAR_DESTINATION, DyeColor.BLUE);
        UUID nearDestination = portal(server, player, NEAR_DESTINATION, created).getId();
        UUID farDestination = portal(server, player, FAR_DESTINATION, created).getId();
        UUID farSource = source(server, player, FAR_SOURCE, farDestination, created);
        return new Scene(nearDestination, farDestination, farSource);
    }

    private static void markerRoom(ServerLevel level, BlockPos origin, DyeColor color) {
        fill(level, origin.offset(-5, 0, -12), 13, 9, 25, Blocks.AIR.defaultBlockState());
        fill(level, origin.offset(-5, -1, -12), 13, 1, 25, Blocks.SEA_LANTERN.defaultBlockState());
        fill(level, origin.offset(-5, 0, -10), 13, 9, 1, Blocks.CONCRETE.pick(color).defaultBlockState());
        fill(level, origin.offset(-5, 0, 6), 13, 9, 1, Blocks.CONCRETE.pick(color).defaultBlockState());
    }

    private static MinecraftPortal portal(MinecraftServer server, ServerPlayer player, BlockPos origin, List<UUID> created) {
        MinecraftPortal portal = runtime(server).portals().create(player.getUUID(), server.overworld(), cells(origin), PortalType.PORTAL,
            new Vec3(0.0D, 0.0D, -1.0D));
        created.add(portal.getId());
        return portal;
    }

    private static UUID source(MinecraftServer server, ServerPlayer player, BlockPos origin, UUID destination, List<UUID> created) {
        MinecraftPortal portal = portal(server, player, origin, created);
        assertTrue(runtime(server).portals().link(player, portal.getId(), destination), "marker portal link rejected at " + origin);
        return portal.getId();
    }

    private static void remove(TestServerContext server, ServerPlayer player, UUID id) {
        server.runOnServer(minecraftServer -> runtime(minecraftServer).portals().remove(player, id));
    }

    private static void awaitView(ClientGameTestContext context, BlockPos origin, boolean present) {
        context.waitFor(client -> view(origin) == present && (!present || PortalStencilRenderer.instance().deferredActive()), TIMEOUT_TICKS);
        context.waitTicks(SETTLE_TICKS);
    }

    private static boolean view(BlockPos origin) {
        for (PortalView view : WormholesClient.instance().portalViews().current()) {
            ApertureDescriptor geometry = view.surface().geometry();
            if (view.kind() == PortalView.Kind.ARM && geometry.originX() == origin.getX() && geometry.originY() == origin.getY()
                && geometry.originZ() == origin.getZ() && PortalStencilRenderer.instance().claims(geometry)) {
                return true;
            }
        }
        return false;
    }

    private static int[] capture(ClientGameTestContext context, Crop crop) {
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        context.runOnClient(client -> FramebufferProbe.capture(client, captured::set));
        context.waitFor(client -> captured.get() != null, TIMEOUT_TICKS);
        FramebufferProbe probe = captured.get();
        assertTrue(crop.x() >= 0 && crop.y() >= 0 && crop.x() + crop.size() <= probe.width()
            && crop.y() + crop.size() <= probe.height(), "portal crop extends beyond the framebuffer: " + crop);
        int[] pixels = new int[crop.size() * crop.size()];
        for (int y = 0; y < crop.size(); y++) {
            for (int x = 0; x < crop.size(); x++) {
                pixels[y * crop.size() + x] = probe.read(crop.x() + x, crop.y() + y);
            }
        }
        return pixels;
    }

    private static double difference(int[] first, int[] second) {
        long distance = 0L;
        for (int index = 0; index < first.length; index++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                distance += Math.abs(((first[index] >> shift) & 255) - ((second[index] >> shift) & 255));
            }
        }
        return distance / (first.length * 3.0D);
    }

    private static void fill(ServerLevel level, BlockPos origin, int sizeX, int sizeY, int sizeZ, BlockState state) {
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z), state);
                }
            }
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Scene(UUID nearDestination, UUID farDestination, UUID farSource) {
    }

    private record ClientOptions(boolean hidden, CameraType camera, boolean recursion) {
    }

    private record Crop(int x, int y, int size) {
    }
}
