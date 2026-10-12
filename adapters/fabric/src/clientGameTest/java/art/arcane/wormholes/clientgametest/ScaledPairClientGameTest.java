package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftScaleAccess;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class ScaledPairClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final Square SMALL = new Square(new BlockPos(400, 100, 400), 3);
    private static final Square LARGE = new Square(new BlockPos(440, 100, 400), 9);
    private static final int BACKDROP_Z = 392;
    private static final int DEMO_RETURN_BACKDROP_Z = 409;
    private static final int BAND = 3;
    private static final double RATIO_FACTOR = 3.0D;
    private static final double APPROACH = 4.0D;
    private static final double ENTRY_OFFSET = 0.5D;
    private static final double EDGE_SAMPLE = 1.2D;
    private static final double OUTSIDE_SAMPLE = 2.5D;
    private static final double FACTOR_TOLERANCE = 1.0E-6D;
    private static final double OFFSET_TOLERANCE = 0.05D;
    private static final double CROSSING_JUMP = 4.0D;
    private static final double DOMINANCE = 1.15D;
    private static final int NEGOTIATION_TIMEOUT_TICKS = 400;
    private static final int ARRIVE_TIMEOUT_TICKS = 200;
    private static final int STREAM_TIMEOUT_TICKS = 600;
    private static final int PREPARE_TIMEOUT_TICKS = 600;
    private static final int CROSSING_TIMEOUT_TICKS = 160;
    private static final int CAPTURE_TIMEOUT_TICKS = 40;
    private static final int PROBE_ATTEMPTS = 60;
    private static final int PROBE_INTERVAL_TICKS = 10;
    private static final int SAMPLE_RADIUS = 2;
    private static final int SETTLE_TICKS = 30;
    private static final int OFF_SCREEN = 0;
    private static final ScaleRule RATIO = ScaleRule.ratio(0.25D, 4.0D);
    private static final BlockState LIME = Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState();
    private static final BlockState MAGENTA = Blocks.CONCRETE.pick(DyeColor.MAGENTA).defaultBlockState();
    private static final BlockState YELLOW = Blocks.CONCRETE.pick(DyeColor.YELLOW).defaultBlockState();
    private static final double MODEL_Z = BACKDROP_Z + 3.0D;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            run(context, singleplayer.getConnection(), singleplayer.getServer(), "singleplayer");
        }
    }

    static void demo(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, CameraType camera, String id) {
        SeamlessClient client = new FabricSeamlessClient(context, connection);
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        Scene scene = server.computeOnServer(minecraftServer -> buildDemo(minecraftServer, player));
        NativeDemoCapture capture = new NativeDemoCapture(client);
        boolean hiddenHud = context.computeOnClient(minecraft -> minecraft.gui.hud.isHidden());
        boolean recording = false;
        List<String> failures = new ArrayList<>();
        try {
            context.runOnClient(minecraft -> {
                assertHidden(minecraft);
                minecraft.options.setCameraType(camera);
                minecraft.options.bobView().set(true);
                minecraft.options.fovEffectScale().set(0.0D);
                minecraft.options.fov().set(70);
                hud(minecraft, true);
            });
            place(context, server, player, SMALL.approach(ENTRY_OFFSET), 180.0F);
            connection.waitForChunksRender();
            waitFor(context, "scale-demo-source-ready", minecraft -> WormholesClient.instance().seamlessTravel().armed(scene.portals()[0])
                && WormholesClient.instance().seamlessTravel().unprepared() == null, PREPARE_TIMEOUT_TICKS);
            capture.begin(id);
            recording = true;
            context.waitTicks(40);
            List<TravelTap.Frame> grown = walk(context, scene.portals()[0], SMALL, id + " grow");
            crossed(context, grown, RATIO_FACTOR, SMALL, LARGE, id + " grow", failures);
            validateDemo(context, server, player, camera, RATIO_FACTOR, id + " grow");
            context.waitTicks(40);
            context.getInput().holdKeyFor(options -> options.keyUp, 6);
            capture.turn(0.0F, 0.0F, 40);
            context.waitTicks(SETTLE_TICKS);
            List<TravelTap.Frame> shrunk = walk(context, scene.portals()[1], LARGE, id + " return");
            crossed(context, shrunk, 1.0D, LARGE, SMALL, id + " return", failures);
            validateDemo(context, server, player, camera, 1.0D, id + " return");
            context.getInput().holdKeyFor(options -> options.keyUp, 25);
            capture.turn(150.0F, 0.0F, 30);
            context.waitTicks(40);
            context.runOnClient(minecraft -> assertTrue(Math.abs(minecraft.gameRenderer.mainCamera().position().x - minecraft.player.getX()) < 8.0D,
                id + ": return camera remained across the portal instead of showing the restored-size traveller"));
            assertTrue(failures.isEmpty(), id + ": " + failures);
            LOGGER.info("WORMHOLES_NATIVE_DEMO_PASS: {} actual 3x3 to 9x9 traversal grew to 300% and returned to 100%", id);
        } finally {
            try {
                if (recording) {
                    capture.end(id);
                }
            } finally {
                client.releaseForward();
                context.runOnClient(minecraft -> hud(minecraft, hiddenHud));
                server.runOnServer(minecraftServer -> cleanupDemo(minecraftServer, player, scene));
                context.restoreDefaultGameOptions();
            }
        }
    }

    private static void validateDemo(ClientGameTestContext context, TestServerContext server, ServerPlayer player,
                                     CameraType camera, double scale, String label) {
        waitFor(context, label + " confirmed", minecraft -> !WormholesClient.instance().seamlessTravel().pending()
            && Math.abs(minecraft.player.getAttributeValue(Attributes.SCALE) - scale) < FACTOR_TOLERANCE, ARRIVE_TIMEOUT_TICKS);
        double authoritative = server.computeOnServer(minecraftServer -> player.getAttributeValue(Attributes.SCALE));
        assertTrue(Math.abs(authoritative - scale) < FACTOR_TOLERANCE,
            label + ": server scale is " + authoritative + " instead of " + scale);
        context.runOnClient(minecraft -> {
            assertHidden(minecraft);
            assertTrue(minecraft.options.getCameraType() == camera && minecraft.gui.hud.isHidden(),
                label + ": camera changed or HUD became visible");
            assertTrue(TravelTap.respawns() == 0 && TravelTap.positions() == 0 && TravelTap.accepts() == 0
                && !TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded(),
                label + ": vanilla teleport correction " + TravelTap.events());
            assertTrue(!TravelTap.frames().isEmpty(), label + ": native render frames did not advance");
        });
    }

    private static void cleanupDemo(MinecraftServer server, ServerPlayer player, Scene scene) {
        MinecraftScaleAccess.scaleAttribute().reset(player);
        runtime(server).portals().remove(player, scene.portals()[0]);
        runtime(server).portals().remove(player, scene.portals()[1]);
        for (UUID id : scene.models()) {
            Entity entity = server.overworld().getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        }
        BlockPos small = SMALL.min();
        BlockPos large = LARGE.min();
        fill(server.overworld(), new BlockPos(small.getX() - 6, small.getY() - 1, BACKDROP_Z - 4), 15, 14, 25,
            Blocks.AIR.defaultBlockState());
        fill(server.overworld(), new BlockPos(large.getX() - 8, large.getY() - 1, BACKDROP_Z - 4), 25, 20, 25,
            Blocks.AIR.defaultBlockState());
    }

    private void run(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, String label) {
        connection.waitForChunksDownload();
        waitFor(context, "scaled-session-negotiation", client -> WormholesClient.instance() != null
            && WormholesClient.instance().session().active(), NEGOTIATION_TIMEOUT_TICKS);
        ServerPlayer player = server.computeOnServer(minecraftServer -> connection.getServerPlayer());
        Scene scene = server.computeOnServer(minecraftServer -> build(minecraftServer, player));
        UUID[] portals = scene.portals();
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        CameraType camera = context.computeOnClient(client -> client.options.getCameraType());
        List<String> failures = new ArrayList<>();
        try {
            context.runOnClient(client -> {
                assertHidden(client);
                client.options.setCameraType(CameraType.FIRST_PERSON);
                client.options.bobView().set(false);
                client.options.fovEffectScale().set(0.0D);
                client.options.fov().set(70);
            });
            view(context, connection, server, player, label + " scaled view", failures);
            Measurement reduced = measuredView(context, connection, server, player, SMALL, LARGE, 2 * BAND + 1, "reduced", failures);
            Measurement enlarged = measuredView(context, connection, server, player, LARGE, SMALL, 1, "enlarged", failures);
            if (reduced != null && enlarged != null) {
                double actual = (double) enlarged.model().width() / reduced.model().width();
                double expected = enlarged.modelReferenceWidth() / reduced.modelReferenceWidth();
                assertTrue(Math.abs(actual / expected - 1.0D) <= 0.25D,
                    "Native model size did not follow the same inverse portal ratio as terrain: observed " + actual + ", expected " + expected);
            }
            place(context, server, player, SMALL.approach(ENTRY_OFFSET), 180.0F);
            List<TravelTap.Frame> grown = walk(context, portals[0], SMALL, label + " ratio");
            crossed(context, grown, RATIO_FACTOR, SMALL, LARGE, label + " ratio", failures);
            context.getInput().holdKeyFor(options -> options.keyUp, 6);
            context.getInput().lookAt(0.0F, 0.0F);
            context.waitTicks(SETTLE_TICKS);
            List<TravelTap.Frame> shrunk = walk(context, portals[1], LARGE, label + " ratio return");
            crossed(context, shrunk, 1.0D, LARGE, SMALL, label + " ratio return", failures);
            server.runOnServer(minecraftServer -> {
                rule(minecraftServer, player, portals[0], ScaleRule.motion());
                rule(minecraftServer, player, portals[1], ScaleRule.motion());
            });
            place(context, server, player, SMALL.approach(ENTRY_OFFSET), 180.0F);
            List<TravelTap.Frame> moved = walk(context, portals[0], SMALL, label + " motion");
            crossed(context, moved, 1.0D, SMALL, LARGE, label + " motion", failures);
        } finally {
            context.runOnClient(client -> {
                hud(client, hidden);
                client.options.setCameraType(camera);
            });
            server.runOnServer(minecraftServer -> {
                MinecraftScaleAccess.scaleAttribute().reset(player);
                runtime(minecraftServer).portals().remove(player, portals[0]);
                runtime(minecraftServer).portals().remove(player, portals[1]);
                for (UUID id : scene.models()) {
                    Entity entity = minecraftServer.overworld().getEntity(id);
                    if (entity != null) {
                        entity.discard();
                    }
                }
            });
            context.restoreDefaultGameOptions();
        }
        assertTrue(failures.isEmpty(), label + ": " + failures);
    }

    private static void view(ClientGameTestContext context, TestServerConnection connection, TestServerContext server, ServerPlayer player,
                             String label, List<String> failures) {
        place(context, server, player, SMALL.approach(0.0D), 180.0F);
        connection.waitForChunksRender();
        BlockPos marker = new BlockPos(LARGE.min().getX() + LARGE.edge() / 2, LARGE.min().getY() + LARGE.edge() / 2, BACKDROP_Z);
        waitFor(context, "scaled-reduced-destination-ready", client -> {
            if (nativeRendering()) {
                PortalView view = nativeView(SMALL);
                return view != null && PortalStencilRenderer.instance().claims(view.surface().geometry())
                    && YELLOW.equals(view.destination().getBlockState(marker));
            }
            int key = NativeClientViewAssertions.portalKey(SMALL.min());
            return NativeClientViewAssertions.ready(key) && YELLOW.equals(NativeClientViewAssertions.state(key, marker));
        }, STREAM_TIMEOUT_TICKS);
        double scale = context.computeOnClient(client -> {
            if (nativeRendering()) {
                PortalView view = nativeView(SMALL);
                return view == null ? Double.NaN : 1.0D / view.toDestination().scale();
            }
            EnvironmentState environment = WormholesClient.instance().session().environment(NativeClientViewAssertions.portalKey(SMALL.min()));
            return environment == null ? Double.NaN : (double) environment.scale();
        });
        LOGGER.info("[{}] the 3x3 destination view displays at scale {}", label, scale);
        if (!Double.isFinite(scale) || Math.abs(scale - 1.0D / RATIO_FACTOR) > FACTOR_TOLERANCE) {
            failures.add(label + ": the 3x3 destination view display scale is " + scale + " instead of 1/3");
        }
        context.runOnClient(client -> hud(client, true));
        context.waitTicks(SETTLE_TICKS);
        Vec3 middle = SMALL.center();
        Vec3 edge = middle.add(EDGE_SAMPLE, 0.0D, 0.0D);
        Vec3 outside = middle.add(-OUTSIDE_SAMPLE, 0.0D, 0.0D);
        String failure = null;
        for (int attempt = 0; attempt < PROBE_ATTEMPTS; attempt++) {
            AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
            context.runOnClient(client -> FramebufferProbe.capture(client, captured::set));
            waitFor(context, "scaled-aperture-framebuffer-" + attempt, client -> captured.get() != null, CAPTURE_TIMEOUT_TICKS);
            int[] colors = context.computeOnClient(client -> new int[] {sample(client, captured.get(), middle), sample(client, captured.get(), edge),
                sample(client, captured.get(), outside)});
            if (colors[0] == OFF_SCREEN || colors[1] == OFF_SCREEN || colors[2] == OFF_SCREEN) {
                failure = "a sample point is off screen";
                break;
            }
            LOGGER.info("[{}] attempt {}: center #{} (expected yellow), aperture edge #{} (expected lime), outside #{} (expected magenta)", label,
                attempt, hex(colors[0]), hex(colors[1]), hex(colors[2]));
            failure = yellow(colors[0]) && lime(colors[1]) && magenta(colors[2]) ? null
                : "center #" + hex(colors[0]) + ", aperture edge #" + hex(colors[1]) + ", outside #" + hex(colors[2]);
            if (failure == null) {
                break;
            }
            context.waitTicks(PROBE_INTERVAL_TICKS);
        }
        context.takeScreenshot("scaled-pair-view");
        context.runOnClient(client -> hud(client, false));
        if (failure != null) {
            failures.add(label + ": " + failure);
        }
    }

    private static Measurement measuredView(ClientGameTestContext context, TestServerConnection connection, TestServerContext server,
                                            ServerPlayer player, Square from, Square to, int bandWidth, String label, List<String> failures) {
        place(context, server, player, from.approach(0.0D), 180.0F);
        connection.waitForChunksRender();
        waitFor(context, "scaled-" + label + "-native-view-ready", client -> nativeView(from) != null
            && PortalStencilRenderer.instance().claims(nativeView(from).surface().geometry()),
            STREAM_TIMEOUT_TICKS);
        context.runOnClient(client -> hud(client, true));
        context.waitTicks(SETTLE_TICKS);
        double ratio = (double) to.edge() / from.edge();
        Similarity toward = Similarity.between(Frame.canonical(Face.N), point(from.center()), Frame.canonical(Face.N), point(to.center()), ratio);
        double middleX = to.min().getX() + to.edge() / 2;
        Vec3 markerLeft = new Vec3(middleX - (bandWidth - 1) * 0.5D, to.center().y + 2.0D, BACKDROP_Z + 1.0D);
        Vec3 markerRight = markerLeft.add(bandWidth, 0.0D, 0.0D);
        Vec3 modelCenter = new Vec3(to.center().x + 0.75D, to.center().y + 0.75D, MODEL_Z);
        String failure = "no framebuffer capture";
        Measurement measurement = null;
        for (int attempt = 0; attempt < PROBE_ATTEMPTS; attempt++) {
            AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
            context.runOnClient(client -> {
                assertHidden(client);
                FramebufferProbe.capture(client, captured::set);
            });
            waitFor(context, "scaled-" + label + "-framebuffer-" + attempt, client -> captured.get() != null, CAPTURE_TIMEOUT_TICKS);
            measurement = context.computeOnClient(client -> measure(client, captured.get(), toward, from, markerLeft, markerRight, modelCenter));
            failure = measurement == null ? "a projected marker or model sample is off screen" : measurement.failure();
            if (failure == null) {
                break;
            }
            context.waitTicks(PROBE_INTERVAL_TICKS);
        }
        context.takeScreenshot("scaled-pair-" + label + "-size");
        context.runOnClient(client -> hud(client, false));
        if (failure != null) {
            failures.add(label + ": " + failure + "; measurement=" + measurement);
            return null;
        }
        LOGGER.info("WORMHOLES_SCALED_PROJECTION_SIZE {} inverse ratio {}: {}", label, 1.0D / ratio, measurement);
        return measurement;
    }

    private static Measurement measure(Minecraft client, FramebufferProbe probe, Similarity toward, Square from, Vec3 markerLeft,
                                       Vec3 markerRight, Vec3 modelCenter) {
        Similarity back = toward.inverse();
        int[] left = screen(client, back, markerLeft);
        int[] right = screen(client, back, markerRight);
        int[] modelLeft = screen(client, back, modelCenter.add(-0.5D, 0.0D, 0.0D));
        int[] modelRight = screen(client, back, modelCenter.add(0.5D, 0.0D, 0.0D));
        int[] modelTop = screen(client, back, modelCenter.add(-0.7D, 0.8D, 0.0D));
        int[] modelBottom = screen(client, back, modelCenter.add(0.7D, -0.8D, 0.0D));
        int[] outside = FramebufferProbe.screen(client, new Vec3(from.min().getX() - 0.125D,
            client.gameRenderer.mainCamera().position().y, from.center().z));
        if (outside == null) {
            outside = FramebufferProbe.screen(client, new Vec3(from.center().x, from.min().getY() - 0.125D, from.center().z));
        }
        if (left == null || right == null || modelLeft == null || modelRight == null || modelTop == null || modelBottom == null || outside == null) {
            return null;
        }
        int expectedLeft = Math.min(left[0], right[0]);
        int expectedRight = Math.max(left[0], right[0]);
        int expectedWidth = expectedRight - expectedLeft;
        int row = (left[1] + right[1]) / 2;
        PixelSpan marker = span(probe, Math.max(0, expectedLeft - expectedWidth), Math.min(probe.width() - 1, expectedRight + expectedWidth),
            row, row, false);
        PixelSpan model = span(probe, Math.min(modelTop[0], modelBottom[0]), Math.max(modelTop[0], modelBottom[0]),
            Math.min(modelTop[1], modelBottom[1]), Math.max(modelTop[1], modelBottom[1]), true);
        String failure = marker == null ? "yellow marker was not drawn" : model == null ? "cyan native model equipment was not drawn" : null;
        if (failure == null && Math.abs((double) marker.width() / expectedWidth - 1.0D) > 0.20D) {
            failure = "marker pixel width differs from its inverse-ratio projection";
        }
        if (failure == null && Math.abs(marker.center() - (expectedLeft + expectedRight) * 0.5D) > Math.max(3.0D, expectedWidth * 0.1D)) {
            failure = "marker position differs from its portal-centred projection";
        }
        int exterior = probe.average(outside[0], outside[1], SAMPLE_RADIUS);
        if (failure == null && (yellow(exterior) || cyan(exterior))) {
            failure = "scaled destination geometry leaked outside the source aperture";
        }
        return new Measurement(marker, expectedWidth, model, Math.abs(modelRight[0] - modelLeft[0]), failure);
    }

    private static PixelSpan span(FramebufferProbe probe, int minX, int maxX, int minY, int maxY, boolean model) {
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        for (int y = Math.max(0, minY); y <= Math.min(probe.height() - 1, maxY); y++) {
            for (int x = Math.max(0, minX); x <= Math.min(probe.width() - 1, maxX); x++) {
                int color = probe.read(x, y);
                if (model ? cyan(color) : yellow(color)) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        return left <= right ? new PixelSpan(left, right) : null;
    }

    private static int[] screen(Minecraft client, Similarity back, Vec3 target) {
        Vec3d projected = back.point(point(target));
        return FramebufferProbe.screen(client, new Vec3(projected.x(), projected.y(), projected.z()));
    }

    private static Vec3d point(Vec3 value) {
        return new Vec3d(value.x, value.y, value.z);
    }

    private static void crossed(ClientGameTestContext context, List<TravelTap.Frame> frames, double expected, Square from, Square to, String label,
                                List<String> failures) {
        int crossing = context.computeOnClient(client -> TravelTap.bodyCrossingFrame(CROSSING_JUMP));
        if (crossing < 1) {
            failures.add(label + ": never crossed");
            return;
        }
        TravelTap.Frame before = frames.get(crossing - 1);
        TravelTap.Frame after = frames.get(crossing);
        firstCrossingPose(before, after, expected, label, failures);
        int corrections = context.computeOnClient(client -> TravelTap.positions() + TravelTap.respawns());
        if (corrections > 0) {
            failures.add(label + ": the crossing was corrected by " + corrections + " position or respawn packets; " + TravelTap.events());
        }
        double entry = before.pose().feet().x - from.center().x;
        double arrival = after.pose().feet().x - to.center().x;
        double ratio = (double) to.edge() / from.edge();
        if (after.firstPerson()) {
            int cameraCrossing = context.computeOnClient(client -> TravelTap.crossingFrame(CROSSING_JUMP));
            double cameraEntry = before.camera().x - from.center().x;
            double cameraArrival = after.camera().x - to.center().x;
            if (cameraCrossing != crossing || Math.abs(cameraArrival - cameraEntry * ratio) > OFFSET_TOLERANCE) {
                failures.add(label + ": first-person camera did not cross with the body: body frame=" + crossing + ", camera frame="
                    + cameraCrossing + ", camera entry=" + cameraEntry + ", camera arrival=" + cameraArrival + ", ratio=" + ratio);
            }
        }
        List<TravelTap.ScalePacket> packets = context.computeOnClient(client -> TravelTap.scalePackets());
        double settled = context.computeOnClient(client -> client.player.getAttributeValue(Attributes.SCALE));
        AttributeModifier modifier = context.computeOnClient(client -> client.player.getAttribute(Attributes.SCALE).getModifier(MinecraftScaleAccess.ID));
        LOGGER.info("[{}] crossing frame {}: scale {} before, {} on the crossing frame, {} settled; entry offset {}, arrival offset {}; "
                + "server scale packets {}; modifier {}", label, crossing, before.scale(), after.scale(), settled, String.format("%.4f", entry),
            String.format("%.4f", arrival), packets, modifier);
        if (Math.abs(after.scale() - expected) > FACTOR_TOLERANCE) {
            failures.add(label + ": the crossing frame scale is " + after.scale() + " instead of " + expected);
        }
        if (Math.abs(settled - expected) > FACTOR_TOLERANCE) {
            failures.add(label + ": the settled scale is " + settled + " instead of " + expected);
        }
        if (Math.abs(arrival - entry * ratio) > OFFSET_TOLERANCE) {
            failures.add(label + ": the arrival offset " + arrival + " is not " + ratio + " times the entry offset " + entry);
        }
        if (Math.abs(expected - before.scale()) > FACTOR_TOLERANCE) {
            predicted(packets, crossing, expected, label, failures);
            boolean present = Math.abs(expected - 1.0D) > FACTOR_TOLERANCE;
            if (present != (modifier != null)) {
                failures.add(label + ": the portal modifier is " + modifier + " after settling at scale " + expected);
            }
        }
        for (int index = 0; index < frames.size(); index++) {
            double scale = frames.get(index).scale();
            double allowed = index < crossing ? before.scale() : expected;
            if (Math.abs(scale - allowed) > FACTOR_TOLERANCE) {
                failures.add(label + ": frame " + index + " drew at scale " + scale + " instead of " + allowed);
                return;
            }
        }
    }

    private static void firstCrossingPose(TravelTap.Frame before, TravelTap.Frame after, double scale, String label, List<String> failures) {
        TravelTap.PlayerPose source = before.pose();
        TravelTap.PlayerPose destination = after.pose();
        double ratio = scale / before.scale();
        String state = "frame=" + after.index() + ", beforeScale=" + before.scale() + ", scale=" + after.scale()
            + ", expectedScale=" + scale + ", camera=" + after.renderedCamera() + ", pose=" + destination + ", sourcePose=" + source;
        LOGGER.info("WORMHOLES_SCALE_FIRST_FRAME {}: {}", label, state);
        if (Math.abs(destination.width() - source.width() * ratio) > 1.0E-5D
            || Math.abs(destination.height() - source.height() * ratio) > 1.0E-5D
            || Math.abs(destination.eyeHeight() - source.eyeHeight() * ratio) > 1.0E-5D) {
            failures.add(label + ": body dimensions did not scale on the first rendered crossing frame; " + state);
        }
        if (Math.abs(destination.width() - destination.dimensions().width()) > 1.0E-5D
            || Math.abs(destination.height() - destination.dimensions().height()) > 1.0E-5D
            || Math.abs(destination.eyeHeight() - destination.dimensions().eyeHeight()) > 1.0E-5D
            || destination.eye().subtract(destination.feet()).distanceTo(new Vec3(0.0D, destination.eyeHeight(), 0.0D)) > 1.0E-5D) {
            failures.add(label + ": body bounds and native eye disagreed on the first rendered crossing frame; " + state);
        }
        if (after.firstPerson() && after.renderedCamera().distanceTo(destination.eye()) > 1.0E-4D) {
            failures.add(label + ": first rendered camera did not match interpolated feet plus the native scaled eye; " + state);
        }
    }

    private static void predicted(List<TravelTap.ScalePacket> packets, int crossing, double expected, String label, List<String> failures) {
        for (TravelTap.ScalePacket packet : packets) {
            if (packet.frame() < crossing) {
                continue;
            }
            if (Math.abs(packet.before() - expected) > FACTOR_TOLERANCE) {
                failures.add(label + ": the server's scale packet arrived while the client was still at " + packet.before());
            }
            return;
        }
        failures.add(label + ": the server never synced the scale after the crossing");
    }

    private static List<TravelTap.Frame> walk(ClientGameTestContext context, UUID source, Square from, String label) {
        for (int tick = 0; tick < PREPARE_TIMEOUT_TICKS; tick++) {
            boolean ready = context.computeOnClient(client -> WormholesClient.instance().seamlessTravel().armed(source)
                && WormholesClient.instance().seamlessTravel().unprepared() == null);
            if (ready) {
                break;
            }
            context.waitTicks(1);
        }
        LOGGER.info("[{}] walking with travel {}", label, context.computeOnClient(client -> WormholesClient.instance().seamlessTravel().unprepared()));
        context.runOnClient(client -> {
            client.options.bobView().set(true);
            TravelTap.reset();
        });
        context.getInput().holdKey(options -> options.keyUp);
        try {
            for (int tick = 0; tick < CROSSING_TIMEOUT_TICKS && context.computeOnClient(client -> TravelTap.bodyCrossingFrame(CROSSING_JUMP)) < 0; tick++) {
                if (tick == 1) {
                    walkingProjection(context, from, label);
                }
                context.waitTicks(1);
            }
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
        }
        context.waitTicks(SETTLE_TICKS);
        return context.computeOnClient(client -> TravelTap.frames());
    }

    private static void walkingProjection(ClientGameTestContext context, Square from, String label) {
        Square to = from.equals(SMALL) ? LARGE : SMALL;
        context.runOnClient(client -> {
            assertHidden(client);
            PortalView view = nativeView(from);
            assertTrue(client.options.bobView().get(), label + ": walking bob is disabled");
            assertTrue(view != null && PortalStencilRenderer.instance().claims(view.surface().geometry()),
                label + ": native projection disappeared while approaching the portal");
            double ratio = (double) to.edge() / from.edge();
            assertTrue(Math.abs(view.toDestination().scale() - ratio) < FACTOR_TOLERANCE,
                label + ": projection scale did not match the portal ratio during walking");
            BlockPos marker = new BlockPos(to.min().getX() + to.edge() / 2, to.min().getY() + to.edge() / 2, BACKDROP_Z);
            assertTrue(YELLOW.equals(view.destination().getBlockState(marker)), label + ": destination marker is unavailable during walking");
            LOGGER.info("WORMHOLES_SCALED_WALKING_PROJECTION {}: position={}, camera={}, velocity={}, bodyScale={}, projectionRatio={}, marker={}",
                label, client.player.position(), client.gameRenderer.mainCamera().position(), client.player.getDeltaMovement(),
                client.player.getScale(), ratio, marker);
        });
        context.takeScreenshot(label.replace(' ', '-') + "-walking-bob");
    }

    private static void place(ClientGameTestContext context, TestServerContext server, ServerPlayer player, Vec3 position, float yaw) {
        server.runOnServer(minecraftServer -> {
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.teleportTo(player.level(), position.x, position.y, position.z, Set.of(), yaw, 0.0F, false);
            player.setDeltaMovement(Vec3.ZERO);
        });
        waitFor(context, "scaled-hover-" + position, client -> client.player.position().distanceTo(position) < 0.05D
            && client.player.getAbilities().flying, ARRIVE_TIMEOUT_TICKS);
        BlockPos support = BlockPos.containing(position).below();
        waitFor(context, "scaled-support-" + support, client -> client.level.hasChunkAt(support)
            && client.level.getBlockState(support).is(Blocks.STONE), PREPARE_TIMEOUT_TICKS);
        server.runOnServer(minecraftServer -> {
            player.getAbilities().flying = false;
            player.setDeltaMovement(Vec3.ZERO);
            player.onUpdateAbilities();
        });
        waitFor(context, "scaled-grounded-" + position, client -> !client.player.getAbilities().flying && client.player.onGround()
            && client.player.position().distanceTo(position) < 0.05D, ARRIVE_TIMEOUT_TICKS);
        context.getInput().lookAt(yaw, 0.0F);
        waitFor(context, "scaled-look-" + yaw, client -> Math.abs(Math.IEEEremainder(client.player.getYRot() - yaw, 360.0D)) < 0.1D
            && Math.abs(client.player.getXRot()) < 0.1D, ARRIVE_TIMEOUT_TICKS);
        context.waitTicks(SETTLE_TICKS);
    }

    private static Scene buildDemo(MinecraftServer server, ServerPlayer actor) {
        Scene scene = build(server, actor);
        ServerLevel level = server.overworld();
        demoBackdrop(level, SMALL, new DemoBackdrop(6, 15, 13, 0, Blocks.CONCRETE.pick(DyeColor.BLUE).defaultBlockState(),
            Blocks.CONCRETE.pick(DyeColor.ORANGE).defaultBlockState()));
        demoBackdrop(level, LARGE, new DemoBackdrop(8, 25, 19, BAND, Blocks.CONCRETE.pick(DyeColor.CYAN).defaultBlockState(),
            Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState()));
        demoFrame(level, SMALL, Blocks.SANDSTONE.defaultBlockState());
        demoFrame(level, LARGE, Blocks.GOLD_BLOCK.defaultBlockState());
        return scene;
    }

    private static void demoBackdrop(ServerLevel level, Square square, DemoBackdrop backdrop) {
        int middle = square.min().getX() + square.edge() / 2;
        for (int x = square.min().getX() - backdrop.margin(); x < square.min().getX() - backdrop.margin() + backdrop.width(); x++) {
            fill(level, new BlockPos(x, square.min().getY(), DEMO_RETURN_BACKDROP_Z), 1, backdrop.height(), 1,
                Math.abs(x - middle) <= backdrop.band() ? backdrop.marker() : backdrop.background());
        }
    }

    private static void demoFrame(ServerLevel level, Square square, BlockState material) {
        int outer = square.edge() + 2;
        BlockPos min = square.min().offset(-1, -1, 0);
        fill(level, min, outer, 1, 1, material);
        fill(level, min.offset(0, outer - 1, 0), outer, 1, 1, material);
        fill(level, min.offset(0, 1, 0), 1, square.edge(), 1, material);
        fill(level, min.offset(outer - 1, 1, 0), 1, square.edge(), 1, material);
    }

    private static Scene build(MinecraftServer server, ServerPlayer actor) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockPos smallMin = SMALL.min();
        BlockPos largeMin = LARGE.min();
        fill(level, new BlockPos(smallMin.getX() - 6, smallMin.getY() - 1, BACKDROP_Z - 4), 15, 14, 25, air);
        fill(level, new BlockPos(smallMin.getX() - 6, smallMin.getY() - 1, BACKDROP_Z - 4), 15, 1, 25, stone);
        fill(level, new BlockPos(smallMin.getX() - 6, smallMin.getY(), BACKDROP_Z), 15, 13, 1, MAGENTA);
        fill(level, new BlockPos(smallMin.getX() + SMALL.edge() / 2, smallMin.getY(), BACKDROP_Z), 1, 13, 1, YELLOW);
        fill(level, new BlockPos(largeMin.getX() - 8, largeMin.getY() - 1, BACKDROP_Z - 4), 25, 20, 25, air);
        fill(level, new BlockPos(largeMin.getX() - 8, largeMin.getY() - 1, BACKDROP_Z - 4), 25, 1, 25, stone);
        int middle = largeMin.getX() + LARGE.edge() / 2;
        for (int x = largeMin.getX() - 8; x < largeMin.getX() + 17; x++) {
            fill(level, new BlockPos(x, largeMin.getY(), BACKDROP_Z), 1, 19, 1, Math.abs(x - middle) <= BAND ? YELLOW : LIME);
        }
        MinecraftPortal small = portal(runtime, actor, level, SMALL);
        MinecraftPortal large = portal(runtime, actor, level, LARGE);
        assertTrue(runtime.portals().link(actor, small.getId(), large.getId()), "3x3 link rejected");
        assertTrue(runtime.portals().link(actor, large.getId(), small.getId()), "9x9 link rejected");
        rule(server, actor, small.getId(), RATIO);
        assertTrue(RATIO.equals(small.getScaleRule()) && RATIO.equals(large.getScaleRule()),
            "Settings Sync did not propagate the scale rule from the 3x3 portal to its 9x9 counterpart");
        return new Scene(new UUID[] {small.getId(), large.getId()}, List.of(model(level, SMALL), model(level, LARGE)));
    }

    private static UUID model(ServerLevel level, Square square) {
        ArmorStand stand = new ArmorStand(level, square.center().x + 0.75D, square.center().y - 1.0D, MODEL_Z);
        stand.setNoGravity(true);
        stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Blocks.CONCRETE.pick(DyeColor.CYAN)));
        assertTrue(level.addFreshEntity(stand), "scaled native model spawn rejected");
        return stand.getUUID();
    }

    private static MinecraftPortal portal(WormholesModRuntime runtime, ServerPlayer actor, ServerLevel level, Square square) {
        List<BlockPos> cells = new ArrayList<>(square.edge() * square.edge());
        for (int x = 0; x < square.edge(); x++) {
            for (int y = 0; y < square.edge(); y++) {
                cells.add(square.min().offset(x, y, 0));
            }
        }
        MinecraftPortal portal = runtime.portals().create(actor.getUUID(), level, cells, PortalType.PORTAL, new Vec3(0, 0, -1));
        assertTrue(portal != null, "portal creation rejected at " + square.min());
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setOrientation(OrientationPolicy.FRAME);
        return portal;
    }

    private static void rule(MinecraftServer server, ServerPlayer actor, UUID portal, ScaleRule rule) {
        assertTrue(runtime(server).portals().update(actor, portal, target -> target.setScaleRule(rule)), "scale rule " + rule + " rejected");
    }

    private static int sample(Minecraft client, FramebufferProbe probe, Vec3 world) {
        int[] pixel = FramebufferProbe.screen(client, world);
        return pixel == null ? OFF_SCREEN : probe.average(pixel[0], pixel[1], SAMPLE_RADIUS);
    }

    private static boolean nativeRendering() {
        return WormholesClient.instance().session().has(ClientViewExtensions.SEAMLESS_TRAVEL);
    }

    private static PortalView nativeView(Square source) {
        BlockPos min = source.min();
        for (PortalView view : WormholesClient.instance().portalViews().current()) {
            ApertureDescriptor geometry = view.surface().geometry();
            if (view.kind() == PortalView.Kind.ARM && geometry.originX() == min.getX()
                && geometry.originY() == min.getY() && geometry.originZ() == min.getZ()) {
                return view;
            }
        }
        return null;
    }

    private static boolean lime(int color) {
        return green(color) > red(color) * DOMINANCE && green(color) > blue(color) * DOMINANCE;
    }

    private static boolean magenta(int color) {
        return red(color) > green(color) * DOMINANCE && blue(color) > green(color) * DOMINANCE;
    }

    private static boolean yellow(int color) {
        return red(color) > blue(color) * DOMINANCE && green(color) > blue(color) * DOMINANCE && !lime(color);
    }

    private static boolean cyan(int color) {
        return green(color) > red(color) * 1.5D && blue(color) > red(color) * 1.5D;
    }

    private static void assertHidden(Minecraft client) {
        assertTrue(Boolean.parseBoolean(System.getProperty("automator.hidden", "true")) && NativeTestWindow.hiddenAndUnfocused(client)
            && NativeTestWindow.mouseReleased(client), "Scaled projection requires a hidden unfocused window with the mouse released");
    }

    private static void waitFor(ClientGameTestContext context, String stage, Predicate<Minecraft> condition, int ticks) {
        try {
            context.waitFor(condition::test, ticks);
        } catch (AssertionError failure) {
            String state = context.computeOnClient(client -> {
                WormholesClient mod = WormholesClient.instance();
                return "position=" + (client.player == null ? null : client.player.position())
                    + ", velocity=" + (client.player == null ? null : client.player.getDeltaMovement())
                    + ", flying=" + (client.player != null && client.player.getAbilities().flying)
                    + ", grounded=" + (client.player != null && client.player.onGround())
                    + ", scale=" + (client.player == null ? Double.NaN : client.player.getScale())
                    + ", screen=" + (client.gui.screen() == null ? "none" : client.gui.screen().getClass().getSimpleName())
                    + ", arms=" + (mod == null ? 0 : mod.seamlessTravel().arms().size())
                    + ", unprepared=" + (mod == null ? null : mod.seamlessTravel().unprepared())
                    + ", nativeViews=" + (mod == null ? 0 : mod.portalViews().current().size());
            });
            throw new AssertionError(stage + " timed out after " + ticks + " ticks: " + state, failure);
        }
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

    private static void hud(Minecraft client, boolean hidden) {
        if (client.gui.hud.isHidden() != hidden) {
            client.gui.hud.toggle();
        }
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

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Square(BlockPos min, int edge) {
        Vec3 center() {
            return new Vec3(min.getX() + edge / 2.0D, min.getY() + edge / 2.0D, min.getZ() + 0.5D);
        }

        Vec3 approach(double offset) {
            return new Vec3(min.getX() + edge / 2.0D + offset, min.getY(), min.getZ() + 0.5D + APPROACH);
        }
    }

    private record DemoBackdrop(int margin, int width, int height, int band, BlockState background, BlockState marker) {
    }

    private record Scene(UUID[] portals, List<UUID> models) {
    }

    private record PixelSpan(int left, int right) {
        int width() {
            return right - left + 1;
        }

        double center() {
            return (left + right) * 0.5D;
        }
    }

    private record Measurement(PixelSpan marker, double expectedMarkerWidth, PixelSpan model, double modelReferenceWidth, String failure) {
    }
}
