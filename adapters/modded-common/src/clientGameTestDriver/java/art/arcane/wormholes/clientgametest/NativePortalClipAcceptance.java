package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftDyedItems;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.cells;
import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

final class NativePortalClipAcceptance {
    private static final BlockPos SOURCE = new BlockPos(0, 70, 20);
    private static final BlockPos DESTINATION = new BlockPos(0, 70, 420);
    private static final Vec3 VIEWPOINT = new Vec3(SOURCE.getX() + 1.5D, SOURCE.getY(), SOURCE.getZ() + 6.5D);
    private static final int BACKDROP_DISTANCE = 4;
    private static final int DESTINATION_WALL_DISTANCE = 6;
    private static final int CLIPPED_WALL_DISTANCE = 3;
    private static final int SCENE_HALF_WIDTH = 15;
    private static final int SCENE_BELOW = 8;
    private static final int SCENE_ABOVE = 12;
    private static final int LIGHT_SPACING = 3;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int STRIDE = 4;
    private static final double INSIDE_INSET = 0.15D;
    private static final double OUTSIDE_GAP = 0.15D;
    private static final double OUTSIDE_REACH = 0.75D;
    private static final double MIN_EXPECTED_SHARE = 0.95D;
    private static final double MAX_LEAK_SHARE = 0.002D;
    private static final int VIEW_TIMEOUT_TICKS = 600;
    private static final int CAPTURE_ATTEMPTS = 30;
    private static final int CAPTURE_INTERVAL_TICKS = 10;
    private static final int TASK_TIMEOUT_SECONDS = 120;

    private NativePortalClipAcceptance() {
    }

    static void run(DriverClient client, String label) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        MinecraftServer server = Objects.requireNonNull(client.computeOnClient(Minecraft::getSingleplayerServer),
            "portal clip acceptance needs an owned integrated server");
        UUID playerId = client.computeOnClient(minecraft -> minecraft.player.getUUID());
        List<UUID> portals = computeOnServer(server, minecraftServer -> build(minecraftServer, playerId));
        boolean hidden = client.computeOnClient(ClientGui::hudHidden);
        try {
            client.runOnClient(minecraft -> {
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.bobView().set(false);
                if (!ClientGui.hudHidden(minecraft)) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            client.waitFor(minecraft -> minecraft.player.position().distanceTo(VIEWPOINT) < 0.1D, VIEW_TIMEOUT_TICKS);
            client.lookAt(180.0F, 0.0F);
            client.waitForChunksRender();
            client.waitFor(NativePortalClipAcceptance::viewReady, VIEW_TIMEOUT_TICKS);
            Result result = null;
            for (int attempt = 0; attempt < CAPTURE_ATTEMPTS; attempt++) {
                client.waitTicks(CAPTURE_INTERVAL_TICKS);
                result = inspect(client, label + "-" + attempt);
                if (result.passed()) {
                    break;
                }
            }
            Result last = Objects.requireNonNull(result);
            assertTrue(last.passed(), label + ": portal view clipping failed after " + CAPTURE_ATTEMPTS + " captures: " + last);
            System.out.println("WORMHOLES_PORTAL_CLIP_PASS fixture=" + label + " " + last);
        } finally {
            client.runOnClient(minecraft -> {
                if (ClientGui.hudHidden(minecraft) != hidden) {
                    ClientGui.toggleHud(minecraft);
                }
            });
            client.restoreDefaultGameOptions();
            runOnServer(server, minecraftServer -> {
                ServerPlayer player = Objects.requireNonNull(minecraftServer.getPlayerList().getPlayer(playerId));
                for (UUID portal : portals) {
                    runtime(minecraftServer).portals().remove(player, portal);
                }
            });
        }
    }

    private static List<UUID> build(MinecraftServer server, UUID playerId) {
        ServerPlayer player = Objects.requireNonNull(server.getPlayerList().getPlayer(playerId), "portal clip player");
        ServerLevel sourceLevel = server.overworld();
        ServerLevel destinationLevel = Objects.requireNonNull(server.getLevel(Level.END), "portal clip destination level");
        BlockState lime = MinecraftDyedItems.concreteBlock(DyeColor.LIME).defaultBlockState();
        BlockState red = MinecraftDyedItems.concreteBlock(DyeColor.RED).defaultBlockState();
        BlockState blue = MinecraftDyedItems.concreteBlock(DyeColor.BLUE).defaultBlockState();
        clear(sourceLevel, SOURCE);
        clear(destinationLevel, DESTINATION);
        wall(sourceLevel, SOURCE.offset(0, 0, -BACKDROP_DISTANCE), lime);
        fill(sourceLevel, SOURCE.offset(-2, -1, 2), 7, 1, 7, lime);
        wall(destinationLevel, DESTINATION.offset(0, 0, -DESTINATION_WALL_DISTANCE), red);
        lights(destinationLevel, DESTINATION.offset(0, 0, 1 - DESTINATION_WALL_DISTANCE));
        fill(destinationLevel, DESTINATION.offset(-3, -2, CLIPPED_WALL_DISTANCE), 9, 9, 1, blue);
        lights(destinationLevel, DESTINATION.offset(0, 0, CLIPPED_WALL_DISTANCE + 1));
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal source = portal(runtime, player, sourceLevel, SOURCE);
        MinecraftPortal destination = portal(runtime, player, destinationLevel, DESTINATION);
        assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "portal clip source link rejected");
        assertTrue(runtime.portals().link(player, destination.getId(), source.getId()), "portal clip return link rejected");
        player.teleportTo(sourceLevel, VIEWPOINT.x, VIEWPOINT.y, VIEWPOINT.z, Set.of(), 180.0F, 0.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
        return List.of(source.getId(), destination.getId());
    }

    private static MinecraftPortal portal(WormholesModRuntime runtime, ServerPlayer player, ServerLevel level, BlockPos min) {
        MinecraftPortal portal = Objects.requireNonNull(runtime.portals().create(player.getUUID(), level, cells(min), PortalType.PORTAL,
            new Vec3(0.0D, 0.0D, -1.0D)), "portal clip portal creation rejected at " + min);
        portal.setOrientation(OrientationPolicy.FRAME);
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setProjectionMode(ProjectionMode.ON);
        runtime.portals().save(portal);
        return portal;
    }

    private static void clear(ServerLevel level, BlockPos portal) {
        fill(level, portal.offset(-SCENE_HALF_WIDTH, -SCENE_BELOW, -DESTINATION_WALL_DISTANCE - 1), SCENE_HALF_WIDTH * 2 + 3,
            SCENE_BELOW + SCENE_ABOVE, DESTINATION_WALL_DISTANCE + 12, Blocks.AIR.defaultBlockState());
    }

    private static void lights(ServerLevel level, BlockPos origin) {
        BlockState light = Blocks.LIGHT.defaultBlockState();
        for (int x = -SCENE_HALF_WIDTH; x <= SCENE_HALF_WIDTH + 2; x += LIGHT_SPACING) {
            for (int y = -SCENE_BELOW; y < SCENE_ABOVE; y += LIGHT_SPACING) {
                level.setBlockAndUpdate(origin.offset(x, y, 0), light);
            }
        }
    }

    private static void wall(ServerLevel level, BlockPos origin, BlockState state) {
        fill(level, origin.offset(-SCENE_HALF_WIDTH, -SCENE_BELOW, 0), SCENE_HALF_WIDTH * 2 + 3, SCENE_BELOW + SCENE_ABOVE, 1, state);
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

    private static boolean viewReady(Minecraft minecraft) {
        return sourceView(minecraft) != null && !WormholesClient.instance().seamlessTravel().pending();
    }

    private static PortalView sourceView(Minecraft minecraft) {
        Vec3d opening = new Vec3d(SOURCE.getX() + 1.5D, SOURCE.getY() + 1.5D, SOURCE.getZ() + 0.5D);
        for (PortalView view : WormholesClient.instance().portalViews().current()) {
            if (view.source() != minecraft.level || !view.surface().area().contains(opening)) {
                continue;
            }
            Vec3d point = view.toDestination().point(view.surface().planePoint());
            if (view.destination().hasChunkAt(BlockPos.containing(point.x(), point.y(), point.z()))) {
                return view;
            }
        }
        return null;
    }

    private static Result inspect(DriverClient client, String name) {
        Rect aperture = client.computeOnClient(NativePortalClipAcceptance::aperture);
        Path image = screenshot(client, name);
        BufferedImage pixels;
        try {
            pixels = ImageIO.read(image.toFile());
        } catch (IOException failure) {
            throw new AssertionError("Unable to read portal clip capture " + image, failure);
        }
        assertTrue(pixels != null && pixels.getWidth() == WIDTH && pixels.getHeight() == HEIGHT,
            "Portal clip acceptance requires a native 1920x1080 capture: " + image);
        Rect inside = aperture.inset(INSIDE_INSET);
        Rect gap = aperture.inset(-OUTSIDE_GAP);
        Rect reach = aperture.inset(-OUTSIDE_REACH);
        Tally interior = new Tally();
        Tally exterior = new Tally();
        for (int y = Math.max(0, reach.top()); y < Math.min(HEIGHT, reach.bottom()); y += STRIDE) {
            for (int x = Math.max(0, reach.left()); x < Math.min(WIDTH, reach.right()); x += STRIDE) {
                if (inside.contains(x, y)) {
                    interior.add(classify(pixels.getRGB(x, y)));
                } else if (!gap.contains(x, y)) {
                    exterior.add(classify(pixels.getRGB(x, y)));
                }
            }
        }
        return new Result(image.getFileName().toString(), aperture, interior, exterior);
    }

    private static Rect aperture(Minecraft minecraft) {
        PortalView view = Objects.requireNonNull(sourceView(minecraft), "portal clip view disappeared");
        ApertureDescriptor geometry = view.surface().geometry();
        Box area = geometry.apertureArea();
        double plane = geometry.planeCoordinate();
        CameraRenderState camera = ClientRenderAccess.gameState(minecraft).levelRenderState.cameraRenderState;
        Matrix4f transform = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);
        double left = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (double x : new double[] {area.getXa(), area.getXb()}) {
            for (double y : new double[] {area.getYa(), area.getYb()}) {
                Vector4f corner = transform.transform(new Vector4f((float) (x - camera.pos.x), (float) (y - camera.pos.y),
                    (float) (plane - camera.pos.z), 1.0F));
                assertTrue(corner.w > 0.0F, "portal clip aperture corner is behind the camera");
                double screenX = (corner.x / corner.w * 0.5D + 0.5D) * WIDTH;
                double screenY = (0.5D - corner.y / corner.w * 0.5D) * HEIGHT;
                left = Math.min(left, screenX);
                right = Math.max(right, screenX);
                top = Math.min(top, screenY);
                bottom = Math.max(bottom, screenY);
            }
        }
        return new Rect((int) Math.round(left), (int) Math.round(top), (int) Math.round(right), (int) Math.round(bottom));
    }

    private static Tone classify(int argb) {
        int red = argb >> 16 & 255;
        int green = argb >> 8 & 255;
        int blue = argb & 255;
        if (red > 60 && red > green * 2 && red > blue * 2) {
            return Tone.RED;
        }
        if (blue > 60 && blue > red * 1.6D && blue > green * 1.6D) {
            return Tone.BLUE;
        }
        if (green > 60 && green > red * 1.3D && green > blue * 2) {
            return Tone.LIME;
        }
        return Tone.OTHER;
    }

    private static Path screenshot(DriverClient client, String name) {
        Path image = Path.of("screenshots", "portal-clip", name + ".png");
        CompletableFuture<Path> captured = new CompletableFuture<>();
        client.runOnClient(minecraft -> Screenshot.takeScreenshot(ClientRenderAccess.mainTarget(minecraft), framebuffer -> {
            try (framebuffer) {
                Files.createDirectories(image.getParent());
                framebuffer.writeToFile(image);
                captured.complete(image);
            } catch (IOException | RuntimeException | Error failure) {
                captured.completeExceptionally(failure);
            }
        }));
        return await(captured, "portal clip capture " + name);
    }

    private static void runOnServer(MinecraftServer server, Consumer<MinecraftServer> action) {
        Runnable task = () -> action.accept(server);
        await(server.submit(task), "portal clip server task");
    }

    private static <T> T computeOnServer(MinecraftServer server, Function<MinecraftServer, T> action) {
        Supplier<T> task = () -> action.apply(server);
        return await(server.submit(task), "portal clip server query");
    }

    private static <T> T await(CompletableFuture<T> task, String operation) {
        try {
            return task.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException error) {
                throw error;
            }
            throw new AssertionError(operation + " failed", cause);
        } catch (TimeoutException failure) {
            throw new AssertionError(operation + " exceeded " + TASK_TIMEOUT_SECONDS + " seconds", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(operation + " was interrupted", failure);
        }
    }

    private enum Tone {
        RED,
        LIME,
        BLUE,
        OTHER
    }

    private static final class Tally {
        private int total;
        private int red;
        private int lime;
        private int blue;

        void add(Tone tone) {
            total++;
            switch (tone) {
                case RED -> red++;
                case LIME -> lime++;
                case BLUE -> blue++;
                case OTHER -> {
                }
            }
        }

        double share(int count) {
            return total == 0 ? 0.0D : (double) count / total;
        }

        @Override
        public String toString() {
            return "total=" + total + " red=" + red + " lime=" + lime + " blue=" + blue;
        }
    }

    private record Rect(int left, int top, int right, int bottom) {
        Rect inset(double share) {
            int dx = (int) Math.round((right - left) * share);
            int dy = (int) Math.round((bottom - top) * share);
            return new Rect(left + dx, top + dy, right - dx, bottom - dy);
        }

        boolean contains(int x, int y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    private record Result(String capture, Rect aperture, Tally interior, Tally exterior) {
        boolean passed() {
            return interior.total > 0 && exterior.total > 0
                && interior.share(interior.red) >= MIN_EXPECTED_SHARE && interior.share(interior.blue + interior.lime) <= MAX_LEAK_SHARE
                && exterior.share(exterior.lime) >= MIN_EXPECTED_SHARE && exterior.share(exterior.red + exterior.blue) <= MAX_LEAK_SHARE;
        }

        @Override
        public String toString() {
            return "capture=" + capture + " aperture=" + aperture + " inside[" + interior + "] outside[" + exterior + "]";
        }
    }
}
