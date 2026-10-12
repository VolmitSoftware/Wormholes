package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import com.google.gson.GsonBuilder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class NestedHorizontalPortalClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos SOURCE = new BlockPos(200, 100, 20);
    private static final BlockPos DESTINATION = new BlockPos(300, 100, 20);
    private static final BlockPos MIRROR = new BlockPos(300, 100, 36);
    private static final BlockPos FLOOR = new BlockPos(300, 100, 22);
    private static final BlockPos CEILING = new BlockPos(300, 108, 22);
    private static final Vec3 ORDINARY_POSITION = new Vec3(204.5D, 102.0D, 12.0D);
    private static final Vec3 MIRROR_POSITION = new Vec3(304.5D, 102.0D, 28.0D);
    private static final int TIMEOUT_TICKS = 600;
    private static final int CROP_RADIUS = 12;
    private static final int MIN_LANDMARK_PIXELS = 100;
    private static final int OUTSIDE_RADIUS = 4;
    private static final int MAX_OUTSIDE_PIXELS = 4;

    private NestedHorizontalPortalClientGameTest() {
    }

    static Scene build(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        room(level, 200);
        room(level, 300);
        WormholesModRuntime runtime = runtime(server);
        List<UUID> portals = new ArrayList<>(5);
        MinecraftPortal source = portal(runtime, level, player, wall(level, SOURCE), new Vec3(0, 0, -1), portals);
        MinecraftPortal destination = portal(runtime, level, player, wall(level, DESTINATION), new Vec3(0, 0, -1), portals);
        MinecraftPortal mirror = portal(runtime, level, player, wall(level, MIRROR), new Vec3(0, 0, -1), portals);
        mirror.setMirrorMode(true);
        runtime.portals().save(mirror);
        MinecraftPortal floor = portal(runtime, level, player, horizontal(level, FLOOR), new Vec3(0, -1, 0), portals);
        MinecraftPortal ceiling = portal(runtime, level, player, horizontal(level, CEILING), new Vec3(0, 1, 0), portals);
        assertTrue(floor.getFrame().equals(ceiling.getFrame()), "Nested floor and ceiling frames must preserve the physical shaft orientation");
        assertTrue(runtime.portals().link(player, source.getId(), destination.getId())
            && runtime.portals().link(player, destination.getId(), source.getId()), "Nested fixture ordinary wall parent link rejected");
        assertTrue(runtime.portals().link(player, floor.getId(), ceiling.getId())
            && runtime.portals().link(player, ceiling.getId(), floor.getId()), "Nested fixture horizontal child link rejected");
        for (int z : List.of(22, 34)) {
            for (int x = 294; x <= 314; x++) {
                level.setBlockAndUpdate(new BlockPos(x, 105, z), Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x, 103, z), Blocks.CONCRETE.pick(DyeColor.CYAN).defaultBlockState());
            }
            int lightZ = z == 22 ? 21 : 35;
            level.setBlockAndUpdate(new BlockPos(304, 105, lightZ), Blocks.SEA_LANTERN.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(304, 103, lightZ), Blocks.SEA_LANTERN.defaultBlockState());
        }
        return new Scene(List.copyOf(portals), source.getId(), floor.getId(), ceiling.getId());
    }

    static void place(MinecraftServer server, ServerPlayer player, Viewpoint viewpoint) {
        if (viewpoint.parent().equals(MIRROR)) {
            ServerLevel level = server.overworld();
            for (int x = 294; x <= 314; x++) {
                level.setBlockAndUpdate(new BlockPos(x, 103, 34), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x, 105, 34), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(new BlockPos(304, 103, 35), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(304, 105, 35), Blocks.AIR.defaultBlockState());
        }
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(server.overworld(), viewpoint.position().x, viewpoint.position().y, viewpoint.position().z,
            Set.of(), 0.0F, 0.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
    }

    static void remove(MinecraftServer server, ServerPlayer player, Scene scene) {
        for (UUID id : scene.portals()) {
            runtime(server).portals().remove(player, id);
        }
    }

    static void run(SeamlessClient client, Scene scene, Consumer<Viewpoint> place) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        boolean recursion = client.computeOnClient(minecraft -> WormholesClient.instance().config().clientRecursion);
        try {
            client.runOnClient(minecraft -> {
                hidden(minecraft);
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.fov().set(70);
                minecraft.options.bobView().set(false);
                minecraft.options.fovEffectScale().set(0.0D);
                WormholesClient.instance().config().clientRecursion = true;
                assertTrue(WormholesClient.instance().session().nativeSelected()
                    && WormholesClient.instance().session().has(ViewStreamCapability.MESH_RENDER)
                    && WormholesClient.instance().session().has(ViewStreamCapability.CLIENT_RECURSION),
                    "Nested acceptance requires negotiated native mesh and recursion capabilities");
            });
            for (boolean mirror : List.of(false, true)) {
                for (boolean ceiling : List.of(false, true)) {
                    Viewpoint viewpoint = viewpoint(mirror, ceiling);
                    int setupPositions = client.computeOnClient(minecraft -> TravelTap.positions());
                    place.accept(viewpoint);
                    client.waitFor(minecraft -> minecraft.player != null && minecraft.player.getAbilities().flying
                        && minecraft.player.position().distanceTo(viewpoint.position()) < 0.05D
                        && TravelTap.positions() > setupPositions, TIMEOUT_TICKS);
                    Vec3 eye = client.computeOnClient(minecraft -> minecraft.player.getEyePosition());
                    Vec3 direction = viewpoint.landmark().subtract(eye);
                    float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
                    float pitch = (float) Math.toDegrees(Math.atan2(-direction.y, Math.hypot(direction.x, direction.z)));
                    client.lookAt(yaw, pitch);
                    client.waitForChunksDownload();
                    client.waitForChunksRender();
                    client.waitFor(minecraft -> (mirror ? WormholesClient.instance().seamlessTravel().armed(scene.floor())
                        && WormholesClient.instance().seamlessTravel().armed(scene.ceiling()) : WormholesClient.instance().seamlessTravel().armed(scene.source()))
                        && parent(minecraft, viewpoint.parent()) != null, TIMEOUT_TICKS);
                    client.runOnClient(minecraft -> {
                        TravelTap.reset();
                        PortalEntityRenderTap.start();
                    });
                    client.waitTicks(20);
                    Capture diagnostic = capture(client, viewpoint);
                    client.runOnClient(minecraft -> persist(minecraft, viewpoint, diagnostic));
                    client.waitFor(minecraft -> PortalEntityRenderTap.failure() != null || horizontalChildDrawn(minecraft, viewpoint), TIMEOUT_TICKS);
                    client.waitFor(minecraft -> PortalEntityRenderTap.failure() != null || parentDrawn(minecraft, viewpoint), TIMEOUT_TICKS);
                    client.runOnClient(minecraft -> {
                        assertTrue(PortalEntityRenderTap.failure() == null, "Nested parent render scopes failed: " + PortalEntityRenderTap.failure());
                        assertTrue(parentDrawn(minecraft, viewpoint), viewpoint.name() + ": no scoped native parent draw");
                        assertTrue(horizontalChildAnnounced(minecraft, viewpoint), viewpoint.name() + ": no announced horizontal child of the native parent");
                        assertTrue(horizontalChildDrawn(minecraft, viewpoint), viewpoint.name() + ": no scoped native draw of the intended horizontal child");
                        assertTrue(WormholesClient.instance().config().clientRecursion, "Nested acceptance requires enabled recursion");
                        hidden(minecraft);
                    });
                    Capture capture = capture(client, viewpoint);
                    client.runOnClient(minecraft -> {
                        persist(minecraft, viewpoint, capture);
                        assertTrue(capture.pixels() >= MIN_LANDMARK_PIXELS, viewpoint.name() + ": missing visible nested destination landmark: "
                            + capture.pixels() + " matching pixels, require " + MIN_LANDMARK_PIXELS + ", layers " + PortalEntityRenderTap.samples());
                        assertTrue(capture.outsidePixels() <= MAX_OUTSIDE_PIXELS, viewpoint.name() + ": destination landmark leaked outside the horizontal child aperture: "
                            + capture.outsidePixels() + " matching pixels, allow at most " + MAX_OUTSIDE_PIXELS);
                        assertTrue(TravelTap.frames().size() > 1, viewpoint.name() + ": hidden native frames did not advance");
                        assertTrue(minecraft.player.position().distanceTo(viewpoint.position()) < 0.05D
                            && TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0,
                            viewpoint.name() + ": static composed image unexpectedly moved or crossed the player: position "
                                + minecraft.player.position() + ", " + TravelTap.events() + ", accepts " + TravelTap.accepts());
                    });
                    LOGGER.info("[{}] nested {} landmark: {} pixels in frame {}", viewpoint.name(), ceiling ? "ceiling" : "floor",
                        capture.pixels(), capture.frame());
                    client.runOnClient(minecraft -> PortalEntityRenderTap.stop());
                }
            }
            LOGGER.info("WORMHOLES_NESTED_HORIZONTAL_PORTAL_PASS ordinary and mirror parents each showed both horizontal child destinations");
        } finally {
            client.runOnClient(minecraft -> {
                PortalEntityRenderTap.stop();
                WormholesClient.instance().config().clientRecursion = recursion;
            });
            client.restoreDefaultGameOptions();
        }
    }

    private static Viewpoint viewpoint(boolean mirror, boolean ceiling) {
        String name = (mirror ? "mirror" : "ordinary") + (ceiling ? "-ceiling" : "-floor");
        return new Viewpoint(name, mirror ? MIRROR_POSITION : ORDINARY_POSITION, mirror ? MIRROR : SOURCE,
            new Vec3(mirror ? 304.5D : 204.5D, ceiling ? 111.5D : 97.5D, mirror ? 50.5D : 34.5D), ceiling);
    }

    private static PortalView parent(Minecraft minecraft, BlockPos minimum) {
        for (PortalView view : WormholesClient.instance().portalViews().current()) {
            ApertureDescriptor geometry = view.surface().geometry();
            if (view.source() == minecraft.level && geometry.originX() == minimum.getX() && geometry.originY() == minimum.getY()
                && geometry.originZ() == minimum.getZ()) {
                return view;
            }
        }
        return null;
    }

    private static boolean parentDrawn(Minecraft minecraft, Viewpoint viewpoint) {
        PortalView parent = parent(minecraft, viewpoint.parent());
        if (parent == null || parent.recursion() < 2 || parent.surface().geometry().mirror() != viewpoint.name().startsWith("mirror")) {
            return false;
        }
        for (PortalEntityRenderTap.Sample sample : PortalEntityRenderTap.samples()) {
            if (!sample.path().isEmpty() && sample.path().getFirst().equals(parent.key())) {
                return true;
            }
        }
        return false;
    }

    private static Capture capture(SeamlessClient client, Viewpoint viewpoint) {
        AtomicReference<Capture> result = new AtomicReference<>();
        client.runOnClient(minecraft -> {
            int[] pixel = FramebufferProbe.screen(minecraft, viewpoint.landmark());
            int[] outside = FramebufferProbe.screen(minecraft, viewpoint.landmark().add(-10.0D, 0.0D, 0.0D));
            assertTrue(pixel != null && outside != null, viewpoint.name() + ": nested landmark/clipping projection is outside the framebuffer");
            int frame = TravelTap.frameIndex();
            Screenshot.grab(minecraft.gameDirectory, "nested-horizontal-" + viewpoint.name() + ".png", minecraft.gameRenderer.mainRenderTarget(), 1,
                message -> LOGGER.info("Nested horizontal frame sample: {}", message.getString()));
            FramebufferProbe.capture(minecraft, probe -> result.set(sample(probe, viewpoint, pixel, outside, frame)));
        });
        client.waitFor(minecraft -> result.get() != null, TIMEOUT_TICKS);
        return Objects.requireNonNull(result.get(), "nested horizontal framebuffer sample");
    }

    private static boolean horizontalChildAnnounced(Minecraft minecraft, Viewpoint viewpoint) {
        return horizontalChild(minecraft, viewpoint) != null;
    }

    private static PortalView horizontalChild(Minecraft minecraft, Viewpoint viewpoint) {
        PortalView parent = parent(minecraft, viewpoint.parent());
        if (parent == null) {
            return null;
        }
        int parentKey = WormholesClient.instance().portalViews().streamKey(parent);
        if (parentKey == 0) {
            return null;
        }
        BlockPos expected = viewpoint.ceiling() ? CEILING : FLOOR;
        for (PortalView child : WormholesClient.instance().portalViews().children(parent)) {
            ApertureDescriptor geometry = child.surface().geometry();
            int key = WormholesClient.instance().portalViews().streamKey(child);
            if (geometry.parentPortalKey() == parentKey && geometry.facingDirection().getAxis() == Face.U.getAxis()
                && geometry.originX() == expected.getX() && geometry.originY() == expected.getY() && geometry.originZ() == expected.getZ()
                && key != 0 && WormholesClient.instance().session().environment(key) != null) {
                return child;
            }
        }
        return null;
    }

    private static boolean horizontalChildDrawn(Minecraft minecraft, Viewpoint viewpoint) {
        PortalView parent = parent(minecraft, viewpoint.parent());
        PortalView child = horizontalChild(minecraft, viewpoint);
        if (parent == null || child == null) {
            return false;
        }
        for (PortalEntityRenderTap.Sample sample : PortalEntityRenderTap.samples()) {
            if (sample.path().size() > 1 && sample.path().getFirst().equals(parent.key()) && sample.path().get(1).equals(child.key())) {
                return true;
            }
        }
        return false;
    }

    private static Capture sample(FramebufferProbe probe, Viewpoint viewpoint, int[] pixel, int[] outside, int frame) {
        assertTrue(probe.width() == 1920 && probe.height() == 1080, "Nested acceptance requires the actual hidden 1920x1080 framebuffer");
        return new Capture(frame, pixel[0], pixel[1], colors(probe, viewpoint, pixel, CROP_RADIUS), outside[0], outside[1],
            colors(probe, viewpoint, outside, OUTSIDE_RADIUS), probe.average(pixel[0], pixel[1], CROP_RADIUS));
    }

    private static int colors(FramebufferProbe probe, Viewpoint viewpoint, int[] pixel, int radius) {
        int count = 0;
        for (int y = Math.max(0, pixel[1] - radius); y <= Math.min(probe.height() - 1, pixel[1] + radius); y++) {
            for (int x = Math.max(0, pixel[0] - radius); x <= Math.min(probe.width() - 1, pixel[0] + radius); x++) {
                int color = probe.read(x, y);
                int red = color >> 16 & 255;
                int green = color >> 8 & 255;
                int blue = color & 255;
                boolean match = viewpoint.ceiling() ? green > 50 && blue > 50 && green > red * 1.5D && blue > red * 1.5D
                    : red > 50 && blue > 50 && red > green * 1.5D && blue > green * 1.5D;
                if (match) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void persist(Minecraft minecraft, Viewpoint viewpoint, Capture capture) {
        List<Layer> layers = new ArrayList<>();
        for (PortalEntityRenderTap.Sample sample : PortalEntityRenderTap.samples()) {
            layers.add(new Layer(sample.frame(), sample.depth(), sample.path().toString(), sample.eye()));
        }
        List<Stream> streams = new ArrayList<>();
        for (ClientPortal portal : WormholesClient.instance().session().portals().values()) {
            ApertureDescriptor geometry = portal.geometry();
            streams.add(new Stream(portal.portalKey(), geometry.parentPortalKey(), geometry.originX(), geometry.originY(), geometry.originZ(),
                geometry.facing(), geometry.mirror(), portal.ready()));
        }
        PortalView parent = parent(minecraft, viewpoint.parent());
        PortalView child = horizontalChild(minecraft, viewpoint);
        Evidence evidence = new Evidence(viewpoint, MIN_LANDMARK_PIXELS, CROP_RADIUS, MAX_OUTSIDE_PIXELS, OUTSIDE_RADIUS,
            WormholesClient.instance().session().caps(), WormholesClient.instance().session().nativeSelected(),
            parent == null ? 0 : WormholesClient.instance().portalViews().streamKey(parent),
            child == null ? 0 : WormholesClient.instance().portalViews().streamKey(child), capture,
            List.copyOf(layers), List.copyOf(streams), TravelTap.frames());
        Path path = minecraft.gameDirectory.toPath().resolve("screenshots/nested-horizontal/" + viewpoint.name() + ".json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        } catch (IOException failure) {
            throw new AssertionError("Could not save nested horizontal acceptance evidence", failure);
        }
    }

    private static MinecraftPortal portal(WormholesModRuntime runtime, ServerLevel level, ServerPlayer player, List<BlockPos> cells,
                                          Vec3 look, List<UUID> portals) {
        MinecraftPortal portal = Objects.requireNonNull(runtime.portals().create(player.getUUID(), level, cells, PortalType.PORTAL, look),
            "nested fixture portal");
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setProjectionMode(ProjectionMode.ON);
        portal.setNetworkViewDepth(64);
        portal.setBlackoutBackground(false);
        runtime.portals().save(portal);
        portals.add(portal.getId());
        return portal;
    }

    private static List<BlockPos> wall(ServerLevel level, BlockPos minimum) {
        List<BlockPos> cells = new ArrayList<>(72);
        for (int x = -1; x <= 9; x++) {
            for (int y = -1; y <= 8; y++) {
                BlockPos cell = minimum.offset(x, y, 0);
                boolean border = x == -1 || x == 9 || y == -1 || y == 8;
                level.setBlockAndUpdate(cell, border ? Blocks.POLISHED_DEEPSLATE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                if (!border) {
                    cells.add(cell);
                }
            }
        }
        return cells;
    }

    private static List<BlockPos> horizontal(ServerLevel level, BlockPos minimum) {
        List<BlockPos> cells = new ArrayList<>(117);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 13; z++) {
                BlockPos cell = minimum.offset(x, 0, z);
                level.setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState());
                cells.add(cell);
            }
        }
        return cells;
    }

    private static void room(ServerLevel level, int offset) {
        for (int x = -4; x <= 12; x++) {
            for (int y = 99; y <= 109; y++) {
                for (int z = 8; z <= 44; z++) {
                    boolean floor = y == 99 || y == 100 || y == 108 || y == 109;
                    boolean light = (x == -4 || x == 12) && y == 104;
                    level.setBlockAndUpdate(new BlockPos(offset + x, y, z), light ? Blocks.SEA_LANTERN.defaultBlockState()
                        : floor ? Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void hidden(Minecraft minecraft) {
        assertTrue(Boolean.parseBoolean(System.getProperty("automator.hidden", "true"))
            && NativeTestWindow.hiddenAndUnfocused(minecraft) && NativeTestWindow.mouseReleased(minecraft),
            "Nested horizontal acceptance requires a hidden, unfocused native renderer with the mouse released");
    }

    record Scene(List<UUID> portals, UUID source, UUID floor, UUID ceiling) {
    }

    record Viewpoint(String name, Vec3 position, BlockPos parent, Vec3 landmark, boolean ceiling) {
    }

    private record Capture(int frame, int x, int y, int pixels, int outsideX, int outsideY, int outsidePixels, int averageArgb) {
    }

    private record Layer(long frame, int depth, String path, Vec3 eye) {
    }

    private record Stream(int key, int parent, int x, int y, int z, int facing, boolean mirror, boolean ready) {
    }

    private record Evidence(Viewpoint viewpoint, int minimumLandmarkPixels, int cropRadius, int maximumOutsidePixels, int outsideRadius,
                            long capabilities, boolean nativeSelected, int parentStreamKey, int childStreamKey,
                            Capture capture, List<Layer> nativeLayers, List<Stream> announcedStreams, List<TravelTap.Frame> renderedFrames) {
    }
}
