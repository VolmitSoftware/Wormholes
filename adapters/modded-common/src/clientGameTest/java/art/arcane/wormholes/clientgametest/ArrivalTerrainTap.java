package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.PortalShaderScope;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.network.client.TravelMessage;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class ArrivalTerrainTap {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int GRID = 8;
    private static final int ARRIVAL_FRAMES = 8;
    private static final int MAX_SOURCE_FRAMES = 256;
    private static final int REQUIRED_SOURCE_FRAMES = 3;
    private static final int CAPACITY = 8;
    private static final int MAX_ERRORS = 8;
    private static final String SCOPE = "Original native final-frame RGB/white presence and local contrast in a fully block-lit concrete landmark room with vanilla ambient occlusion disabled on an eight-pixel grid, "
        + "0.18-block tile margins and matching cardinal-neighbor ray hits. Initial source-only readiness retains every sampled image and oracle, "
        + "requires three consecutive passing main frames within 256 source captures, and stops new source sampling when ready. "
        + "Outstanding source readbacks remain warmup evidence. Four same-player native held-W crossings, "
        + "first eight actual destination main frames without grace frames; left/right outer quarters and ground on both halves "
        + "must have samples beyond the recorded eight-block departure-approach aperture footprint. This reference footprint is "
        + "the approach aperture, not the immediately preceding near-plane aperture which can cover the entire viewport. "
        + "Shader packs must preserve red/green/blue dominance, near-neutral white and at least 24 RGB units of local contrast; "
        + "packs that recolor these landmarks fail rather than receiving relaxed or calibrated acceptance. "
        + "No certification of unsampled pixels, sky/ceiling, other shader packs, sound or temporal image parity; "
        + "capture intervals are not performance evidence. Negative controls erase the full left quarter, ground half or entire real first frame only on copies.";
    private static final List<Box> FRAME = List.of(new Box(new Point(-1, 69, 0), new Point(4, 70, 1)),
        new Box(new Point(-1, 73, 0), new Point(4, 74, 1)),
        new Box(new Point(-1, 70, 0), new Point(0, 73, 1)),
        new Box(new Point(3, 70, 0), new Point(4, 73, 1)));
    private static final double[] ROOM_LOW = {-14.0D, 70.0D, -18.0D};
    private static final double[] ROOM_HIGH = {17.0D, 78.0D, 19.0D};
    private static final List<Crossing> CROSSINGS = new ArrayList<>();
    private static final List<Capture> CAPTURES = new ArrayList<>();
    private static final List<Control> CONTROLS = new ArrayList<>();
    private static final ArrayBlockingQueue<Work> QUEUE = new ArrayBlockingQueue<>(CAPACITY);
    private static Path directory;
    private static Thread worker;
    private static Throwable failure;
    private static boolean active;
    private static boolean closing;
    private static boolean sourceReady;
    private static boolean shaders;
    private static String shaderPack;
    private static int initialPlayer;
    private static int sessionResets;
    private static long protocolFailures;
    private static int frame;
    private static int issued;
    private static int completed;
    private static int sourceIssued;
    private static int sourceInspected;
    private static int sourceReadinessFrame;
    private static int arrivalFrames;
    private static Pose pose;
    private static Pose lastPose;
    private static Expected expected;

    private ArrivalTerrainTap() {
    }

    static synchronized void open(Minecraft minecraft) {
        if (active || worker != null && worker.isAlive() || issued != completed) {
            throw new AssertionError("Previous arrival framebuffer work is still active");
        }
        directory = minecraft.gameDirectory.toPath().resolve("screenshots/arrival-terrain");
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            throw new AssertionError("Cannot create arrival terrain evidence", error);
        }
        CROSSINGS.clear();
        CAPTURES.clear();
        CONTROLS.clear();
        QUEUE.clear();
        initialPlayer = System.identityHashCode(minecraft.player);
        sessionResets = WormholesClient.instance().session().resets();
        protocolFailures = WormholesClient.instance().session().protocolFailures();
        shaders = PortalShaderScope.shaders();
        shaderPack = shaders ? Iris.getCurrentPackName() : "OFF";
        String required = System.getProperty("wormholes.clientgametest.arrivalTerrain.shaders");
        if (required != null && !required.equals("true") && !required.equals("false")) {
            throw new AssertionError("arrivalTerrain.shaders must be true or false");
        }
        if (required != null && Boolean.parseBoolean(required) != shaders) {
            throw new AssertionError("Arrival shader mode differs from required mode: required=" + required + " actual=" + shaders);
        }
        frame = 0;
        issued = 0;
        completed = 0;
        sourceIssued = 0;
        sourceInspected = 0;
        sourceReadinessFrame = 0;
        arrivalFrames = 0;
        pose = null;
        lastPose = null;
        expected = null;
        failure = null;
        sourceReady = false;
        closing = false;
        active = true;
        worker = new Thread(ArrivalTerrainTap::consume, "wormholes-arrival-terrain-capture");
        worker.setDaemon(true);
        worker.start();
    }

    public static synchronized void beginFrame() {
        pose = null;
        if (active && ++frame > 3000) {
            fail(new AssertionError("Arrival terrain exceeded 3000 native frames"));
        }
    }

    public static synchronized void projection(Matrix4f projection) {
        if (!active || PortalStencilRenderer.instance().nested()) {
            return;
        }
        if (pose != null) {
            fail(new AssertionError("Multiple main projections in arrival frame " + frame));
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        CameraRenderState camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        pose = new Pose(frame, minecraft.level.dimension().identifier().toString(), System.identityHashCode(minecraft.level),
            System.identityHashCode(minecraft.player), minecraft.player.level().dimension().identifier().toString(),
            System.identityHashCode(minecraft.player.level()), new Point(camera.pos.x, camera.pos.y, camera.pos.z),
            new Matrix4f(projection).mul(camera.viewRotationMatrix).get(new float[16]),
            new Matrix4f(projection).get(new float[16]), camera.viewRotationMatrix.get(new float[16]),
            CROSSINGS.size(), shaders);
    }

    public static synchronized Attempt entering(Minecraft minecraft, LocalPlayer player, TravelMessage.TravelBegin arm,
                                               Vec3 previous, Vec3 eye) {
        if (!active) {
            return null;
        }
        return new Attempt(arm.sourcePortal(), arm.token(), arm.generation(), frame, System.identityHashCode(player),
            System.identityHashCode(player.level()), player.level().dimension().identifier().toString(),
            minecraft.options.keyUp.isDown(), previous.distanceTo(eye),
            arm.sourceGeometry().signedDistance(previous.x, previous.y, previous.z),
            arm.sourceGeometry().signedDistance(eye.x, eye.y, eye.z));
    }

    public static synchronized void returned(Attempt attempt, Minecraft minecraft, Vec3 result) {
        if (attempt == null || result == null) {
            return;
        }
        Expected target = expected;
        if (target == null || !target.portal().equals(attempt.portal()) || !attempt.forward() || attempt.motion() < 0.00001D
            || attempt.player() != initialPlayer || System.identityHashCode(minecraft.player) != initialPlayer
            || System.identityHashCode(minecraft.player.level()) == attempt.sourceLevel()
            || minecraft.level != minecraft.player.level()
            || !minecraft.level.dimension().identifier().toString().equals(target.world())) {
            fail(new AssertionError("Unexpected arrival native crossing: " + attempt + " expected=" + target));
            return;
        }
        CROSSINGS.add(new Crossing(CROSSINGS.size() + 1, attempt, frame, target.world(),
            System.identityHashCode(minecraft.level), target.approach(), target.aperture()));
        expected = null;
        arrivalFrames = 0;
        minecraft.options.keyUp.setDown(false);
    }

    public static synchronized void endFrame() {
        if (!active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (pose == null || minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null
            || pose.player() != initialPlayer || pose.level() != pose.playerLevel() || !pose.world().equals(pose.playerWorld())
            || System.identityHashCode(minecraft.player) != initialPlayer || !WormholesClient.instance().session().active()
            || WormholesClient.instance().session().resets() != sessionResets
            || WormholesClient.instance().session().protocolFailures() != protocolFailures
            || !NativeTestWindow.hiddenAndUnfocused(minecraft) || !NativeTestWindow.mouseReleased(minecraft)
            || minecraft.gameRenderer.mainRenderTarget().width != WIDTH || minecraft.gameRenderer.mainRenderTarget().height != HEIGHT
            || PortalShaderScope.shaders() != shaders || shaders && !shaderPack.equals(Iris.getCurrentPackName())
            || !minecraft.gui.hud.isHidden()
            || minecraft.options.bobView().get() || minecraft.options.ambientOcclusion().get() || minecraft.options.fovEffectScale().get() != 0.0D
            || minecraft.options.fov().get() != 70) {
            fail(new AssertionError("Missing connected hidden 1920x1080 arrival main frame or final camera matrices: " + frame));
            return;
        }
        lastPose = pose;
        if (CROSSINGS.isEmpty()) {
            if (!sourceReady && sourceIssued < MAX_SOURCE_FRAMES) {
                capture(minecraft, pose, 0, null);
            }
            return;
        }
        Crossing crossing = CROSSINGS.getLast();
        if (!pose.world().equals(crossing.world()) || pose.level() != crossing.level()) {
            if (pose.frame() > crossing.returnFrame()) {
                fail(new AssertionError("Main render remained in departed world after native crossing " + crossing.ordinal()));
            }
            return;
        }
        if (pose.ordinal() != crossing.ordinal()) {
            fail(new AssertionError("Arrival projection preceded its native crossing in frame " + pose.frame()));
            return;
        }
        if (arrivalFrames < ARRIVAL_FRAMES) {
            capture(minecraft, pose, ++arrivalFrames, crossing);
        }
    }

    static synchronized void expect(UUID portal, String world) {
        check();
        if (!sourceReady || expected != null || lastPose == null || CROSSINGS.size() >= 4
            || !CROSSINGS.isEmpty() && !arrivalReady(CROSSINGS.size())) {
            throw new AssertionError("Arrival crossing armed before prior GPU evidence completed");
        }
        if (Math.abs(Math.abs(lastPose.camera().z() - 0.5D) - 7.5D) > 1.0D) {
            throw new AssertionError("Arrival departure approach must be eight blocks from aperture: " + lastPose);
        }
        Rect aperture = aperture(lastPose);
        if (aperture == null || aperture.right() - aperture.left() > WIDTH / 2 || aperture.bottom() - aperture.top() > HEIGHT * 0.75D) {
            throw new AssertionError("Arrival approach aperture does not leave measurable surrounding FOV: " + aperture);
        }
        expected = new Expected(portal, world, lastPose, aperture);
    }

    static synchronized boolean sourceReady() {
        check();
        return sourceReady;
    }

    static synchronized int crossings() {
        check();
        return CROSSINGS.size();
    }

    static synchronized boolean arrivalReady(int ordinal) {
        check();
        int count = 0;
        for (Capture capture : CAPTURES) {
            if (capture.crossing() == ordinal && capture.oracle().passed()) {
                count++;
            }
        }
        return count == ARRIVAL_FRAMES;
    }

    static synchronized void stop() {
        active = false;
        closing = true;
    }

    static synchronized boolean complete() {
        return !active && issued == completed && (worker == null || !worker.isAlive());
    }

    static synchronized void persist() {
        List<Capture> captures = new ArrayList<>(CAPTURES);
        captures.sort(Comparator.comparingInt(Capture::index));
        Evidence evidence = new Evidence(shaders, shaderPack, GRID, MAX_ERRORS, ARRIVAL_FRAMES, MAX_SOURCE_FRAMES, REQUIRED_SOURCE_FRAMES,
            sourceIssued, sourceInspected, sourceReadinessFrame, CAPTURES.size() - sourceInspected, issued, completed, SCOPE,
            failure == null ? null : failure.toString(), List.copyOf(CROSSINGS), List.copyOf(captures), List.copyOf(CONTROLS));
        try {
            Files.writeString(directory.resolve("arrival-terrain.json"), new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        } catch (IOException error) {
            throw new AssertionError("Cannot save arrival terrain evidence", error);
        }
    }

    static synchronized void verify() {
        check();
        List<Capture> sources = sourceCaptures();
        if (!complete() || !sourceReady || sourceIssued < REQUIRED_SOURCE_FRAMES || sourceIssued > MAX_SOURCE_FRAMES
            || sources.size() != sourceIssued || sourceInspected != sourceIssued || sourceReadiness(sources) != sourceReadinessFrame
            || CROSSINGS.size() != 4 || issued != sourceIssued + 4 * ARRIVAL_FRAMES
            || CAPTURES.size() != issued || CONTROLS.size() != 4 * 3 || expected != null) {
            throw new AssertionError("Arrival terrain incomplete: crossings=" + CROSSINGS.size() + " issued=" + issued
                + " completed=" + completed + " sourceWarmupImages=" + sources.size()
                + " arrivalImages=" + (CAPTURES.size() - sources.size()) + " negativeControls=" + CONTROLS.size());
        }
        for (Crossing crossing : CROSSINGS) {
            List<Capture> arrivals = new ArrayList<>();
            for (Capture capture : CAPTURES) {
                if (capture.crossing() == crossing.ordinal()) {
                    arrivals.add(capture);
                }
            }
            arrivals.sort(Comparator.comparingInt(Capture::arrivalFrame));
            if (arrivals.size() != ARRIVAL_FRAMES) {
                throw new AssertionError("Missing arrival frame coverage for crossing " + crossing.ordinal());
            }
            for (int index = 0; index < ARRIVAL_FRAMES; index++) {
                Capture capture = arrivals.get(index);
                if (capture.arrivalFrame() != index + 1 || !capture.oracle().passed()
                    || capture.pose().frame() != arrivals.getFirst().pose().frame() + index
                    || capture.pose().level() != crossing.level() || capture.pose().player() != initialPlayer) {
                    throw new AssertionError("Skipped or invalid first destination main frame: " + capture);
                }
            }
            if (arrivals.getFirst().pose().frame() < crossing.returnFrame()
                || arrivals.getFirst().pose().frame() > crossing.returnFrame() + 1) {
                throw new AssertionError("Arrival capture delayed beyond first destination main frame: " + crossing);
            }
        }
        if (TravelTap.respawns() != 0 || TravelTap.positions() != 0 || TravelTap.accepts() != 0
            || TravelTap.loadingScreenShown() || TravelTap.clientUnloaded()) {
            throw new AssertionError("Arrival native continuity failed: " + TravelTap.events());
        }
        System.out.println("WORMHOLES_ARRIVAL_TERRAIN_PASS crossings=4 firstMainFrames=4 arrivalFrames=32 originalColors=true shaders="
            + shaders + " sourceWarmupImages=" + sourceIssued + " sourceConsecutivePasses=" + REQUIRED_SOURCE_FRAMES
            + " sourceReadinessFrame=" + sourceReadinessFrame + " absentLandmarkControls=12 captureTimesArePerformance=false");
    }

    public static int tile(String world, int column, int row) {
        return Math.floorMod(Math.floorDiv(row, 2), 2) == 1 ? 3
            : Math.floorMod(Math.floorDiv(column, 2) + (world.equals("minecraft:the_nether") ? 1 : 0), 3);
    }

    private static void capture(Minecraft minecraft, Pose rendered, int arrival, Crossing crossing) {
        if (issued - completed >= CAPACITY) {
            fail(new AssertionError("Arrival async readback capacity exceeded " + CAPACITY));
            return;
        }
        int index = issued++;
        if (crossing == null) {
            sourceIssued++;
        }
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> {
            synchronized (ArrivalTerrainTap.class) {
                if (worker == null || !worker.isAlive() || !QUEUE.offer(new Work(index, rendered, arrival, crossing, image))) {
                    image.close();
                    completed++;
                    fail(new AssertionError("Arrival readback worker unavailable at capture " + index));
                }
            }
        });
    }

    private static void consume() {
        long lastWork = System.nanoTime();
        try {
            while (true) {
                Work work = QUEUE.poll(100L, TimeUnit.MILLISECONDS);
                if (work != null) {
                    try (NativeImage image = work.image()) {
                        inspect(work, image);
                    } catch (Throwable error) {
                        synchronized (ArrivalTerrainTap.class) {
                            fail(error);
                        }
                    } finally {
                        synchronized (ArrivalTerrainTap.class) {
                            completed++;
                        }
                    }
                    lastWork = System.nanoTime();
                }
                synchronized (ArrivalTerrainTap.class) {
                    if (closing && issued == completed) {
                        return;
                    }
                    if (issued > completed && System.nanoTime() - lastWork > TimeUnit.SECONDS.toNanos(20L)) {
                        fail(new AssertionError("Arrival asynchronous readback timed out: " + completed + "/" + issued));
                        return;
                    }
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (ArrivalTerrainTap.class) {
                fail(error);
            }
        }
    }

    private static void inspect(Work work, NativeImage image) throws IOException {
        if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
            throw new AssertionError("Arrival framebuffer dimensions changed");
        }
        List<Probe> probes = probes(work.pose());
        Rect reference = work.crossing() == null ? aperture(work.pose()) : work.crossing().aperture();
        if (reference == null) {
            throw new AssertionError("No measurable departure aperture reference");
        }
        Oracle oracle = oracle(image, probes, reference);
        String file = "frame-" + String.format("%03d", work.index()) + ".png";
        NetherCapturePngWriter.write(image, directory.resolve(file));
        Capture capture = new Capture(work.index(), work.crossing() == null ? 0 : work.crossing().ordinal(), work.arrival(),
            work.pose(), reference, file, probes, oracle);
        synchronized (ArrivalTerrainTap.class) {
            CAPTURES.add(capture);
            if (work.crossing() == null) {
                sourceInspected++;
                if (!sourceReady) {
                    sourceReadinessFrame = sourceReadiness(sourceCaptures());
                    sourceReady = sourceReadinessFrame != 0;
                    if (!sourceReady && sourceInspected == MAX_SOURCE_FRAMES) {
                        throw new AssertionError("Initial source terrain never reached " + REQUIRED_SOURCE_FRAMES
                            + " consecutive passing main frames within " + MAX_SOURCE_FRAMES + " source captures");
                    }
                }
                return;
            }
        }
        if (!oracle.passed()) {
            throw new AssertionError("Arrival ORIGINAL color/contrast/presence failed at crossing=" + capture.crossing()
                + " destinationMainFrame=" + work.arrival() + " " + oracle + "; shader recoloring receives no threshold relaxation");
        }
        if (work.arrival() == 1) {
            rejectAbsent(image, probes, reference, capture, "left-landmarks", 0);
            rejectAbsent(image, probes, reference, capture, "ground-landmarks", 1);
            rejectAbsent(image, probes, reference, capture, "all-destination-landmarks", 2);
        }
    }

    private static List<Capture> sourceCaptures() {
        List<Capture> sources = new ArrayList<>(sourceInspected);
        for (Capture capture : CAPTURES) {
            if (capture.crossing() == 0) {
                sources.add(capture);
            }
        }
        sources.sort(Comparator.comparingInt(Capture::index));
        return sources;
    }

    private static int sourceReadiness(List<Capture> sources) {
        int expectedIndex = 0;
        int previousFrame = 0;
        int passing = 0;
        for (Capture capture : sources) {
            if (capture.index() != expectedIndex++ || capture.arrivalFrame() != 0 || capture.pose().ordinal() != 0) {
                return 0;
            }
            if (capture.oracle().passed()) {
                passing = capture.pose().frame() == previousFrame + 1 ? passing + 1 : 1;
            } else {
                passing = 0;
            }
            previousFrame = capture.pose().frame();
            if (passing >= REQUIRED_SOURCE_FRAMES) {
                return previousFrame;
            }
        }
        return 0;
    }

    private static List<Probe> probes(Pose rendered) {
        Matrix4f viewProjection = new Matrix4f().set(rendered.matrix());
        if (!Float.isFinite(viewProjection.determinant()) || Math.abs(viewProjection.determinant()) < 0.000001F) {
            throw new AssertionError("Arrival view projection is singular or nonfinite");
        }
        Matrix4f inverse = viewProjection.invert();
        Vector4f center = inverse.transform(new Vector4f(0.0F, 0.0F, 1.0F, 0.0F));
        if (!Float.isFinite(center.w) || Math.abs(center.w) < 0.00001F) {
            throw new AssertionError("Invalid arrival optical projection origin");
        }
        Point eye = rendered.camera().add(new Point(center.x / center.w, center.y / center.w, center.z / center.w));
        List<Probe> result = new ArrayList<>(WIDTH * HEIGHT / (GRID * GRID));
        for (int y = GRID; y < HEIGHT - GRID; y += GRID) {
            for (int x = GRID; x < WIDTH - GRID; x += GRID) {
                Hit hit = sample(rendered, inverse, eye, x, y);
                if (hit != null && hit.equals(sample(rendered, inverse, eye, x - 1, y))
                    && hit.equals(sample(rendered, inverse, eye, x + 1, y))
                    && hit.equals(sample(rendered, inverse, eye, x, y - 1))
                    && hit.equals(sample(rendered, inverse, eye, x, y + 1))) {
                    result.add(new Probe(x, y, hit.color(), hit.surface(), hit.column(), hit.row()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static Hit sample(Pose pose, Matrix4f inverse, Point eye, int x, int y) {
        Vector4f near = inverse.transform(new Vector4f(2.0F * (x + 0.5F) / WIDTH - 1.0F,
            1.0F - 2.0F * (y + 0.5F) / HEIGHT, 0.0F, 1.0F));
        if (!Float.isFinite(near.w) || Math.abs(near.w) < 0.00001F) {
            return null;
        }
        Point direction = pose.camera().add(new Point(near.x / near.w, near.y / near.w, near.z / near.w)).subtract(eye);
        double nearest = Double.POSITIVE_INFINITY;
        int surface = -1;
        for (int axis = 0; axis < 3; axis++) {
            double delta = direction.axis(axis);
            if (Math.abs(delta) < 0.000001D) {
                continue;
            }
            double boundary = delta > 0.0D ? ROOM_HIGH[axis] : ROOM_LOW[axis];
            double distance = (boundary - eye.axis(axis)) / delta;
            if (distance > 0.0D && distance < nearest) {
                nearest = distance;
                surface = axis * 2 + (delta > 0.0D ? 1 : 0);
            }
        }
        if (surface < 0 || surface == 3 || !Double.isFinite(nearest)) {
            return null;
        }
        double blocked = portalIntersection(eye, direction);
        if (blocked > 0.0D && blocked <= nearest + 0.000001D) {
            return null;
        }
        Point hit = eye.add(direction.scale(nearest));
        int axis = surface / 2;
        double column = axis == 0 ? hit.z() : hit.x();
        double row = axis == 1 ? hit.z() : hit.y();
        if (edge(column) < 0.18D || edge(row) < 0.18D) {
            return null;
        }
        int u = (int) Math.floor(column);
        int v = (int) Math.floor(row);
        return new Hit(tile(pose.world(), u, v), surface, u, v);
    }

    private static double portalIntersection(Point eye, Point direction) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Box frame : FRAME) {
            double distance = intersection(eye, direction, frame);
            if (distance > 0.0D) {
                nearest = Math.min(nearest, distance);
            }
        }
        if (Math.abs(direction.z()) > 0.000001D) {
            double distance = (0.5D - eye.z()) / direction.z();
            Point aperture = eye.add(direction.scale(distance));
            if (distance > 0.0D && aperture.x() >= 0.0D && aperture.x() <= 3.0D && aperture.y() >= 70.0D && aperture.y() <= 73.0D) {
                nearest = Math.min(nearest, distance);
            }
        }
        return Double.isFinite(nearest) ? nearest : -1.0D;
    }

    private static double intersection(Point eye, Point direction, Box box) {
        double entry = 0.0D;
        double exit = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            double delta = direction.axis(axis);
            if (Math.abs(delta) < 0.000001D) {
                if (eye.axis(axis) < box.low().axis(axis) || eye.axis(axis) > box.high().axis(axis)) {
                    return -1.0D;
                }
            } else {
                double first = (box.low().axis(axis) - eye.axis(axis)) / delta;
                double second = (box.high().axis(axis) - eye.axis(axis)) / delta;
                entry = Math.max(entry, Math.min(first, second));
                exit = Math.min(exit, Math.max(first, second));
            }
        }
        return exit >= entry && exit > 0.0D ? Math.max(entry, 0.000001D) : -1.0D;
    }

    private static double edge(double coordinate) {
        double fraction = coordinate - Math.floor(coordinate);
        return Math.min(fraction, 1.0D - fraction);
    }

    private static Oracle oracle(NativeImage image, List<Probe> probes, Rect reference) {
        int errors = 0;
        int[] counts = new int[4];
        int[] outside = new int[4];
        double[][] sums = new double[4][3];
        int[] regions = new int[4];
        for (Probe probe : probes) {
            int pixel = image.getPixel(probe.x(), probe.y());
            if (palette(pixel) != probe.color()) {
                errors++;
            }
            counts[probe.color()]++;
            sums[probe.color()][0] += pixel >> 16 & 255;
            sums[probe.color()][1] += pixel >> 8 & 255;
            sums[probe.color()][2] += pixel & 255;
            if (!reference.contains(probe.x(), probe.y(), 16)) {
                outside[probe.color()]++;
                if (probe.surface() == 2) {
                    regions[probe.x() < WIDTH / 2 ? 2 : 3]++;
                } else if (probe.x() < WIDTH / 4) {
                    regions[0]++;
                } else if (probe.x() > WIDTH * 3 / 4) {
                    regions[1]++;
                }
            }
        }
        double minimumContrast = Double.POSITIVE_INFINITY;
        for (int color = 0; color < 3; color++) {
            if (counts[color] == 0 || counts[3] == 0) {
                minimumContrast = 0.0D;
                break;
            }
            double squared = 0.0D;
            for (int component = 0; component < 3; component++) {
                double difference = sums[color][component] / counts[color] - sums[3][component] / counts[3];
                squared += difference * difference;
            }
            minimumContrast = Math.min(minimumContrast, Math.sqrt(squared));
        }
        int localPairs = 0;
        int localFailures = 0;
        for (int index = 0; index < probes.size(); index++) {
            Probe colored = probes.get(index);
            if (colored.color() == 3) {
                continue;
            }
            for (int next = index + 1; next < Math.min(probes.size(), index + WIDTH / GRID * 16); next++) {
                Probe white = probes.get(next);
                if (white.y() - colored.y() > 96) {
                    break;
                }
                if (white.color() != 3 || white.surface() != colored.surface() || Math.abs(white.x() - colored.x()) > 48
                    || Math.abs(white.row() - colored.row()) > 3 || Math.abs(white.column() - colored.column()) > 2) {
                    continue;
                }
                localPairs++;
                if (contrast(image.getPixel(colored.x(), colored.y()), image.getPixel(white.x(), white.y())) < 24.0D) {
                    localFailures++;
                }
                break;
            }
        }
        boolean coverage = probes.size() >= 1000 && localPairs >= 64;
        for (int color = 0; color < 4; color++) {
            coverage &= counts[color] >= 64 && outside[color] >= 32 && regions[color] >= 64;
        }
        return new Oracle(probes.size(), errors, counts, outside, regions, minimumContrast, localPairs, localFailures,
            coverage && errors <= MAX_ERRORS && minimumContrast >= 24.0D && localFailures <= MAX_ERRORS);
    }

    private static int palette(int argb) {
        int red = argb >> 16 & 255;
        int green = argb >> 8 & 255;
        int blue = argb & 255;
        if (red > 45 && red > green * 1.5D + 10 && red > blue * 1.5D + 10) {
            return 0;
        }
        if (green > 35 && green > red * 1.2D + 2 && green > blue * 1.4D + 6) {
            return 1;
        }
        if (blue > 24 && blue > red * 1.4D + 2 && blue > green * 1.3D + 2) {
            return 2;
        }
        return red > 90 && green > 90 && blue > 90 && Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) < 70 ? 3 : -1;
    }

    private static double contrast(int first, int second) {
        double red = (first >> 16 & 255) - (second >> 16 & 255);
        double green = (first >> 8 & 255) - (second >> 8 & 255);
        double blue = (first & 255) - (second & 255);
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private static void rejectAbsent(NativeImage original, List<Probe> probes, Rect reference, Capture capture,
                                     String name, int region) throws IOException {
        try (NativeImage erased = new NativeImage(WIDTH, HEIGHT, false)) {
            erased.copyFrom(original);
            Rect erasedRegion = switch (region) {
                case 0 -> new Rect(0, 0, WIDTH / 4, HEIGHT);
                case 1 -> new Rect(0, HEIGHT / 2, WIDTH, HEIGHT);
                default -> new Rect(0, 0, WIDTH, HEIGHT);
            };
            int removed = 0;
            for (Probe probe : probes) {
                if (erasedRegion.contains(probe.x(), probe.y(), 0)) {
                    removed++;
                }
            }
            for (int y = (int) erasedRegion.top(); y < erasedRegion.bottom(); y++) {
                for (int x = (int) erasedRegion.left(); x < erasedRegion.right(); x++) {
                    erased.setPixel(x, y, 0xff000000);
                }
            }
            Oracle rejected = oracle(erased, probes, reference);
            if (removed < 64 || rejected.passed() || rejected.mismatched() <= MAX_ERRORS) {
                throw new AssertionError("Arrival presence oracle accepted deliberately absent " + name + ": " + rejected);
            }
            String file = "negative-crossing-" + capture.crossing() + "-" + name + ".png";
            NetherCapturePngWriter.write(erased, directory.resolve(file));
            synchronized (ArrivalTerrainTap.class) {
                CONTROLS.add(new Control(capture.crossing(), capture.index(), name, erasedRegion, removed, file, rejected));
            }
        }
    }

    private static Rect aperture(Pose pose) {
        Matrix4f matrix = new Matrix4f().set(pose.matrix());
        double left = WIDTH;
        double top = HEIGHT;
        double right = 0.0D;
        double bottom = 0.0D;
        for (double x : new double[] {0.0D, 3.0D}) {
            for (double y : new double[] {70.0D, 73.0D}) {
                Vector4f clip = matrix.transform(new Vector4f((float) (x - pose.camera().x()),
                    (float) (y - pose.camera().y()), (float) (0.5D - pose.camera().z()), 1.0F));
                if (!Float.isFinite(clip.w) || clip.w <= 0.0F) {
                    return null;
                }
                double pixelX = (clip.x / clip.w + 1.0D) * WIDTH * 0.5D;
                double pixelY = (1.0D - clip.y / clip.w) * HEIGHT * 0.5D;
                left = Math.min(left, pixelX);
                right = Math.max(right, pixelX);
                top = Math.min(top, pixelY);
                bottom = Math.max(bottom, pixelY);
            }
        }
        return new Rect(left, top, right, bottom);
    }

    private static void check() {
        if (failure != null) {
            throw new AssertionError("Arrival terrain GPU acceptance failed", failure);
        }
    }

    private static void fail(Throwable error) {
        if (failure == null) {
            failure = error;
            error.printStackTrace();
        }
    }

    public record Attempt(UUID portal, UUID token, long generation, int frame, int player, int sourceLevel, String sourceWorld,
                          boolean forward, double motion, double previousSignedDistance, double currentSignedDistance) {
    }

    private record Expected(UUID portal, String world, Pose approach, Rect aperture) {
    }

    private record Crossing(int ordinal, Attempt attempt, int returnFrame, String world, int level, Pose approach, Rect aperture) {
    }

    private record Pose(int frame, String world, int level, int player, String playerWorld, int playerLevel, Point camera,
                        float[] matrix, float[] projection, float[] viewRotation, int ordinal, boolean shaders) {
    }

    private record Point(double x, double y, double z) {
        double axis(int axis) {
            return switch (axis) {
                case 0 -> x;
                case 1 -> y;
                default -> z;
            };
        }

        Point add(Point point) {
            return new Point(x + point.x, y + point.y, z + point.z);
        }

        Point subtract(Point point) {
            return new Point(x - point.x, y - point.y, z - point.z);
        }

        Point scale(double factor) {
            return new Point(x * factor, y * factor, z * factor);
        }
    }

    private record Box(Point low, Point high) {
    }

    private record Hit(int color, int surface, int column, int row) {
    }

    private record Probe(int x, int y, int color, int surface, int column, int row) {
    }

    private record Rect(double left, double top, double right, double bottom) {
        boolean contains(int x, int y, int margin) {
            return x >= left - margin && x <= right + margin && y >= top - margin && y <= bottom + margin;
        }
    }

    private record Work(int index, Pose pose, int arrival, Crossing crossing, NativeImage image) {
    }

    private record Capture(int index, int crossing, int arrivalFrame, Pose pose, Rect approachAperture, String file,
                           List<Probe> probes, Oracle oracle) {
    }

    private record Oracle(int checked, int mismatched, int[] colors, int[] outsideApproachAperture, int[] outerWallAndGroundRegions,
                          double minimumColorContrast, int localContrastPairs, int localContrastFailures, boolean passed) {
    }

    private record Control(int crossing, int originalCapture, String name, Rect erasedRegion, int erasedSamples, String file, Oracle rejected) {
    }

    private record Evidence(boolean shaders, String shaderPack, int pixelGrid, int maximumMismatches, int framesPerArrival,
                            int maximumSourceFrames, int requiredSourceFrames, int sourceWarmupImages, int sourceWarmupInspected,
                            int sourceReadinessFrame, int arrivalImages, int issued, int completed,
                            String oracleScope, String failure, List<Crossing> crossings, List<Capture> captures, List<Control> controls) {
    }
}
