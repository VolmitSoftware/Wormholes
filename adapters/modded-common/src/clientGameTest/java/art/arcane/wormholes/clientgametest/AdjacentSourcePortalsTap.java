package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.network.client.TravelMessage;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class AdjacentSourcePortalsTap {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int GRID = 8;
    private static final int MAX_ERRORS = 12;
    private static final int CAPACITY = 32;
    private static final List<Frame> FRAMES = new ArrayList<>();
    private static final List<Crossing> CROSSINGS = new ArrayList<>();
    private static final List<Capture> CAPTURES = new ArrayList<>();
    private static final List<Injection> INJECTIONS = new ArrayList<>();
    private static final TreeMap<Integer, Work> QUEUE = new TreeMap<>();
    private static AdjacentSourcePortalsScene scene;
    private static UUID expectedPortal;
    private static Path directory;
    private static Matrix4f projection;
    private static Thread worker;
    private static Throwable failure;
    private static boolean active;
    private static boolean capture;
    private static boolean closing;
    private static boolean frameInProgress;
    private static long closingAt;
    private static boolean overviewReady;
    private static boolean firstSourceRendered;
    private static boolean secondSourceRendered;
    private static int frame;
    private static int issued;
    private static int received;
    private static int processed;
    private static int maximumOutstanding;
    private static int meshSelections;
    private static int sessionResets;
    private static long protocolFailures;
    private static int initialPlayer;
    private static long started;
    private static long previousEnd;
    private static NativeImage firstCrop;
    private static NativeImage secondCrop;

    private AdjacentSourcePortalsTap() {
    }

    static synchronized void open(Minecraft minecraft, AdjacentSourcePortalsScene layout, boolean images) {
        if (active || worker != null && worker.isAlive()) {
            throw new AssertionError("Previous adjacent portal capture still active");
        }
        scene = layout;
        capture = images;
        directory = minecraft.gameDirectory.toPath().resolve("screenshots/adjacent-source-portals");
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            throw new AssertionError("Cannot create adjacent portal evidence directory", error);
        }
        FRAMES.clear();
        CROSSINGS.clear();
        CAPTURES.clear();
        INJECTIONS.clear();
        QUEUE.clear();
        expectedPortal = null;
        failure = null;
        frame = 0;
        issued = 0;
        received = 0;
        processed = 0;
        meshSelections = 0;
        sessionResets = WormholesClient.instance().session().resets();
        protocolFailures = WormholesClient.instance().session().protocolFailures();
        initialPlayer = System.identityHashCode(minecraft.player);
        maximumOutstanding = 0;
        previousEnd = 0L;
        overviewReady = false;
        firstSourceRendered = false;
        secondSourceRendered = false;
        closing = false;
        frameInProgress = false;
        firstCrop = null;
        secondCrop = null;
        active = true;
        if (capture) {
            worker = new Thread(AdjacentSourcePortalsTap::consume, "wormholes-adjacent-capture");
            worker.start();
        }
    }

    public static synchronized void beginFrame() {
        frameInProgress = active;
        if (!active) {
            return;
        }
        frame++;
        started = System.nanoTime();
        projection = null;
        firstSourceRendered = false;
        secondSourceRendered = false;
        if (frame > 4000) {
            fail(new AssertionError("Adjacent capture exceeded 4000 rendered frames"));
        }
    }

    public static synchronized void projection(Matrix4f matrix) {
        if (active && frameInProgress && !PortalStencilRenderer.instance().nested()) {
            if (projection != null) {
                fail(new AssertionError("Multiple main projections in adjacent crossing frame " + frame));
            }
            projection = new Matrix4f(matrix);
        }
    }

    public static synchronized void layer(PortalView view, int depth) {
        if (!active || depth != 1) {
            return;
        }
        if (view.source() != Minecraft.getInstance().level) {
            fail(new AssertionError("Portal layer selected from departed resident level: " + view.key()));
        }
        markSource(view.surface().geometry());
    }

    public static synchronized void mesh(ApertureDescriptor geometry, int depth) {
        if (!active || depth != 0 || geometry.parentPortalKey() != 0) {
            return;
        }
        meshSelections++;
        String world = Minecraft.getInstance().level.dimension().identifier().toString();
        for (AdjacentSourcePortalsScene.Endpoint endpoint : scene.endpoints()) {
            if (geometry.containsCell(endpoint.minimum().getX(), endpoint.minimum().getY(), endpoint.minimum().getZ())
                && !endpoint.world().equals(world)) {
                fail(new AssertionError("Departed-world aperture mesh eligible in " + world + ": " + endpoint.id()
                    + " geometry=" + geometry));
            }
        }
        markSource(geometry);
    }

    private static void markSource(ApertureDescriptor geometry) {
        if (!Minecraft.getInstance().level.dimension().identifier().toString().equals("minecraft:overworld")) {
            return;
        }
        AdjacentSourcePortalsScene.Endpoint first = scene.endpoints().get(0);
        AdjacentSourcePortalsScene.Endpoint second = scene.endpoints().get(1);
        firstSourceRendered |= geometry.containsCell(first.minimum().getX(), first.minimum().getY(), first.minimum().getZ());
        secondSourceRendered |= geometry.containsCell(second.minimum().getX(), second.minimum().getY(), second.minimum().getZ());
    }

    public static synchronized Attempt entering(Minecraft minecraft, LocalPlayer player, TravelMessage.TravelBegin arm,
                                               Vec3 previous, Vec3 eye) {
        if (!active) {
            return null;
        }
        return new Attempt(arm.sourcePortal(), arm.token(), arm.generation(), System.nanoTime(), frame,
            System.identityHashCode(player), System.identityHashCode(player.level()), player.level().dimension().identifier().toString(),
            minecraft.options.keyUp.isDown(), previous.distanceTo(eye),
            arm.sourceGeometry().signedDistance(previous.x, previous.y, previous.z),
            arm.sourceGeometry().signedDistance(eye.x, eye.y, eye.z));
    }

    public static synchronized void returned(Attempt attempt, Minecraft minecraft, Vec3 result) {
        if (attempt == null || result == null) {
            return;
        }
        if (!attempt.portal().equals(expectedPortal) || !attempt.forward() || attempt.motion() < 0.00001D
            || System.identityHashCode(minecraft.player) != attempt.player()) {
            fail(new AssertionError("Unexpected or non-walking accepted crossing: " + attempt + " expected=" + expectedPortal));
        }
        AdjacentSourcePortalsScene.Endpoint destination = scene.endpoint(scene.endpoint(attempt.portal()).destination());
        String world = minecraft.player.level().dimension().identifier().toString();
        if (!destination.world().equals(world) || System.identityHashCode(minecraft.player.level()) == attempt.sourceLevel()) {
            fail(new AssertionError("Accepted adjacent crossing did not reach intended resident destination: " + attempt));
        }
        CROSSINGS.add(new Crossing(CROSSINGS.size() + 1, attempt, System.nanoTime(),
            System.identityHashCode(minecraft.player.level()), world));
        expectedPortal = null;
        minecraft.options.keyUp.setDown(false);
    }

    public static synchronized void endFrame() {
        if (!active || !frameInProgress) {
            return;
        }
        frameInProgress = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || System.identityHashCode(minecraft.player) != initialPlayer
            || !WormholesClient.instance().session().active() || WormholesClient.instance().session().resets() != sessionResets
            || WormholesClient.instance().session().protocolFailures() != protocolFailures || !NativeTestWindow.hiddenAndUnfocused(minecraft)
            || !NativeTestWindow.mouseReleased(minecraft) || minecraft.gameRenderer.mainRenderTarget().width != WIDTH
            || minecraft.gameRenderer.mainRenderTarget().height != HEIGHT || projection == null) {
            fail(new AssertionError("Missing connected hidden 1920x1080 adjacent frame or final projection at frame " + frame));
            return;
        }
        long end = System.nanoTime();
        String world = minecraft.level.dimension().identifier().toString();
        assertMeshEligibility(world);
        CameraRenderState camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        FRAMES.add(new Frame(frame, started, end, previousEnd == 0L ? 0L : end - previousEnd,
            world, System.identityHashCode(minecraft.level), CROSSINGS.size()));
        previousEnd = end;
        Matrix4f finalMatrix = new Matrix4f(projection).mul(camera.viewRotationMatrix);
        Pose pose = new Pose(frame, world, System.identityHashCode(minecraft.level),
            minecraft.player.level().dimension().identifier().toString(), System.identityHashCode(minecraft.player.level()),
            CROSSINGS.size(), new Vec3d(camera.pos.x, camera.pos.y, camera.pos.z), finalMatrix.get(new float[16]),
            firstSourceRendered, secondSourceRendered);
        if (!capture) {
            overviewReady |= CROSSINGS.isEmpty() && firstSourceRendered && secondSourceRendered;
            return;
        }
        if (issued - processed >= CAPACITY) {
            fail(new AssertionError("Adjacent ordered readback capacity exceeded at frame " + frame));
            return;
        }
        int index = issued++;
        maximumOutstanding = Math.max(maximumOutstanding, issued - processed);
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> enqueue(index, pose, image));
    }

    static synchronized void expect(UUID portal) {
        check();
        if (expectedPortal != null || CROSSINGS.size() >= 4) {
            throw new AssertionError("Adjacent crossing order was not consumed");
        }
        expectedPortal = portal;
    }

    static synchronized int crossings() {
        check();
        return CROSSINGS.size();
    }

    static synchronized int framesAfterCrossing() {
        check();
        if (CROSSINGS.isEmpty()) {
            return 0;
        }
        Crossing crossing = CROSSINGS.getLast();
        int count = 0;
        for (Frame candidate : FRAMES) {
            if (candidate.ended() >= crossing.returned() && candidate.level() == crossing.destinationLevel()) {
                count++;
            }
        }
        return count;
    }

    static synchronized boolean overviewReady() {
        check();
        return overviewReady;
    }

    static synchronized void stop() {
        active = false;
        frameInProgress = false;
        if (!closing) {
            closingAt = System.nanoTime();
        }
        closing = true;
        AdjacentSourcePortalsTap.class.notifyAll();
    }

    static synchronized boolean complete() {
        return processed == issued && (worker == null || !worker.isAlive());
    }

    static synchronized void persist() {
        writeEvidence();
    }

    static synchronized void verify() {
        writeEvidence();
        check();
        if (active || issued != processed || CROSSINGS.size() != 4 || !overviewReady || expectedPortal != null
            || capture && (INJECTIONS.size() != 3 || CAPTURES.size() != FRAMES.size())) {
            throw new AssertionError("Incomplete adjacent acceptance: crossings=" + CROSSINGS.size() + " images="
                + processed + '/' + issued + " frames=" + FRAMES.size() + " overview=" + overviewReady
                + " corruptionControls=" + INJECTIONS.size());
        }
        for (int index = 0; index < FRAMES.size(); index++) {
            if (FRAMES.get(index).index() != index + 1) {
                throw new AssertionError("Missing adjacent rendered frame " + (index + 1));
            }
        }
        for (Crossing crossing : CROSSINGS) {
            Timing row = timing(crossing);
            System.out.println("WORMHOLES_ADJACENT_FIRST4_TIMING " + row);
            if (capture) {
                long arrivals = CAPTURES.stream().filter(value -> value.pose().ordinal() == crossing.ordinal()
                    && value.pose().level() == crossing.destinationLevel() && value.oracle().checked() - value.oracle().portalSamples().values().stream().mapToInt(Integer::intValue).sum() >= 64).count();
                if (arrivals < 40) {
                    throw new AssertionError("Missing first 40 destination frames for crossing " + crossing.ordinal());
                }
            }
        }
        System.out.println("WORMHOLES_ADJACENT_SOURCE_PORTALS_PASS crossings=4 capture=" + capture
            + " images=" + processed + " meshSelections=" + meshSelections + " corruptionControls=" + INJECTIONS.size()
            + " captureTimesArePerformance=false");
    }

    private static void assertMeshEligibility(String world) {
        for (ClientPortal portal : WormholesClient.instance().session().portals().values()) {
            if (portal.nested()) {
                continue;
            }
            for (AdjacentSourcePortalsScene.Endpoint endpoint : scene.endpoints()) {
                if (!endpoint.world().equals(world) && portal.geometry().containsCell(endpoint.minimum().getX(),
                    endpoint.minimum().getY(), endpoint.minimum().getZ()) && ClientPortalRenderer.instance().available(portal.portalKey())) {
                    fail(new AssertionError("Departed source mesh remains eligible after arrival: key=" + portal.portalKey()
                        + " endpoint=" + endpoint.id() + " arrival=" + world));
                }
            }
        }
    }

    private static synchronized void enqueue(int index, Pose pose, NativeImage image) {
        if (closing && worker != null && !worker.isAlive()) {
            image.close();
            fail(new AssertionError("Adjacent readback arrived after worker exit: " + index));
            return;
        }
        received++;
        if (QUEUE.put(index, new Work(index, pose, image)) != null) {
            image.close();
            fail(new AssertionError("Duplicate adjacent image callback " + index));
        }
        AdjacentSourcePortalsTap.class.notifyAll();
    }

    private static void consume() {
        try {
            while (true) {
                Work work;
                synchronized (AdjacentSourcePortalsTap.class) {
                    while ((work = QUEUE.remove(processed)) == null) {
                        if (closing && processed == issued) {
                            return;
                        }
                        if (closing && System.nanoTime() - closingAt > 5_000_000_000L) {
                            fail(new AssertionError("Adjacent capture callback missing at image " + processed + "/" + issued));
                            return;
                        }
                        AdjacentSourcePortalsTap.class.wait(50L);
                    }
                }
                try (NativeImage image = work.image()) {
                    inspect(work, image);
                } catch (Throwable error) {
                    synchronized (AdjacentSourcePortalsTap.class) {
                        fail(error);
                    }
                } finally {
                    synchronized (AdjacentSourcePortalsTap.class) {
                        processed++;
                        AdjacentSourcePortalsTap.class.notifyAll();
                    }
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (AdjacentSourcePortalsTap.class) {
                fail(error);
            }
        } finally {
            synchronized (AdjacentSourcePortalsTap.class) {
                for (Work pending : QUEUE.values()) {
                    pending.image().close();
                }
                QUEUE.clear();
            }
            if (firstCrop != null) {
                firstCrop.close();
            }
            if (secondCrop != null) {
                secondCrop.close();
            }
        }
    }

    private static void inspect(Work work, NativeImage image) throws IOException {
        if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
            throw new AssertionError("Adjacent readback dimensions changed");
        }
        List<Probe> probes = probes(work.pose());
        Oracle oracle = oracle(image, probes);
        String file = "frame-" + String.format("%05d", work.index()) + ".png";
        NetherCapturePngWriter.write(image, directory.resolve(file));
        synchronized (AdjacentSourcePortalsTap.class) {
            CAPTURES.add(new Capture(work.index(), work.pose(), file, oracle));
        }
        if (work.pose().ordinal() == 0 && !overviewReady && (oracle.checked() < 64 || oracle.mismatched() > MAX_ERRORS)) {
            return;
        }
        if (work.pose().ordinal() == 0 && oracle.checked() < 64 || oracle.mismatched() > MAX_ERRORS) {
            throw new AssertionError("Adjacent frame " + work.pose().frame() + " independent scene mismatch: " + oracle);
        }
        if (work.pose().ordinal() == 0 && work.pose().sourceARendered() && work.pose().sourceBRendered() && firstCrop == null) {
            firstCrop = crop(image, probes, scene.endpoints().get(0).id());
            secondCrop = crop(image, probes, scene.endpoints().get(1).id());
            if (firstCrop != null && secondCrop != null) {
                NetherCapturePngWriter.write(firstCrop, directory.resolve("source-aperture-a-crop.png"));
                NetherCapturePngWriter.write(secondCrop, directory.resolve("source-aperture-b-crop.png"));
                synchronized (AdjacentSourcePortalsTap.class) {
                    overviewReady = true;
                }
            } else {
                if (firstCrop != null) {
                    firstCrop.close();
                    firstCrop = null;
                }
                if (secondCrop != null) {
                    secondCrop.close();
                    secondCrop = null;
                }
            }
        }
        if (work.pose().ordinal() > 0 && work.pose().world().equals("minecraft:the_nether") && INJECTIONS.isEmpty()) {
            if (firstCrop == null || secondCrop == null) {
                throw new AssertionError("No real source aperture crops before first actual crossing");
            }
            reject(image, probes, firstCrop, "reduced-source-a", work.index());
            reject(image, probes, secondCrop, "reduced-source-b", work.index());
            reject(image, probes, null, "gray-aperture", work.index());
        }
    }

    private static List<Probe> probes(Pose pose) {
        Matrix4f inverse = new Matrix4f().set(pose.matrix()).invert();
        Vector4f center = inverse.transform(new Vector4f(0.0F, 0.0F, 1.0F, 0.0F));
        if (Math.abs(center.w) < 0.00001F) {
            throw new AssertionError("Missing adjacent optical projection origin");
        }
        Vec3d eye = pose.camera().add(new Vec3d(center.x / center.w, center.y / center.w, center.z / center.w));
        List<Probe> probes = new ArrayList<>(WIDTH * HEIGHT / (GRID * GRID));
        for (int y = GRID; y < HEIGHT - GRID; y += GRID) {
            for (int x = GRID; x < WIDTH - GRID; x += GRID) {
                AdjacentSourcePortalsScene.Sample expected = sample(pose, inverse, eye, x, y);
                if (expected != null && expected.equals(sample(pose, inverse, eye, x - 1, y))
                    && expected.equals(sample(pose, inverse, eye, x + 1, y))
                    && expected.equals(sample(pose, inverse, eye, x, y - 1))
                    && expected.equals(sample(pose, inverse, eye, x, y + 1))) {
                    probes.add(new Probe(x, y, expected.color(), expected.firstPortal(), expected.depth()));
                }
            }
        }
        return List.copyOf(probes);
    }

    private static AdjacentSourcePortalsScene.Sample sample(Pose pose, Matrix4f inverse, Vec3d eye, int x, int y) {
        Vector4f near = inverse.transform(new Vector4f(2.0F * (x + 0.5F) / WIDTH - 1.0F,
            1.0F - 2.0F * (y + 0.5F) / HEIGHT, 0.0F, 1.0F));
        if (Math.abs(near.w) < 0.00001F) {
            return null;
        }
        Vec3d direction = pose.camera().add(new Vec3d(near.x / near.w, near.y / near.w, near.z / near.w))
            .subtract(eye).normalize();
        return scene.sample(pose.world(), eye, direction);
    }

    private static Oracle oracle(NativeImage image, List<Probe> probes) {
        int errors = 0;
        int firstX = -1;
        int firstY = -1;
        Map<UUID, Integer> paths = new HashMap<>();
        for (Probe probe : probes) {
            if (probe.portal() != null) {
                paths.merge(probe.portal(), 1, Integer::sum);
            }
            if (palette(image.getPixel(probe.x(), probe.y())) != probe.color()) {
                errors++;
                if (firstX < 0) {
                    firstX = probe.x();
                    firstY = probe.y();
                }
            }
        }
        return new Oracle(probes.size(), errors, firstX, firstY, Map.copyOf(paths));
    }

    private static NativeImage crop(NativeImage image, List<Probe> probes, UUID portal) {
        int left = WIDTH;
        int right = 0;
        int top = HEIGHT;
        int bottom = 0;
        int samples = 0;
        for (Probe probe : probes) {
            if (portal.equals(probe.portal())) {
                left = Math.min(left, probe.x());
                right = Math.max(right, probe.x());
                top = Math.min(top, probe.y());
                bottom = Math.max(bottom, probe.y());
                samples++;
            }
        }
        if (samples < 64 || right - left < 128 || bottom - top < 128) {
            return null;
        }
        int x = (left + right) / 2 - 64;
        int y = (top + bottom) / 2 - 64;
        int covered = 0;
        for (Probe probe : probes) {
            if (probe.x() >= x && probe.x() < x + 128 && probe.y() >= y && probe.y() < y + 128) {
                if (!portal.equals(probe.portal())) {
                    return null;
                }
                covered++;
            }
        }
        if (covered < 64) {
            return null;
        }
        NativeImage crop = new NativeImage(128, 128, false);
        for (int row = 0; row < 128; row++) {
            for (int column = 0; column < 128; column++) {
                crop.setPixel(column, row, image.getPixel(x + column, y + row));
            }
        }
        return crop;
    }

    private static void reject(NativeImage image, List<Probe> probes, NativeImage crop, String name, int index) throws IOException {
        for (int y = 192; y < HEIGHT - 192; y += 64) {
            for (int x = 320; x < WIDTH - 320; x += 64) {
                int checked = 0;
                int wrong = 0;
                for (Probe probe : probes) {
                    if (probe.x() < x || probe.x() >= x + 64 || probe.y() < y || probe.y() >= y + 64 || probe.portal() != null) {
                        continue;
                    }
                    checked++;
                    int pixel = crop == null ? 0xff777777 : crop.getPixel((probe.x() - x) * 2, (probe.y() - y) * 2);
                    if (palette(pixel) != probe.color()) {
                        wrong++;
                    }
                }
                if (checked < 32 || wrong <= MAX_ERRORS) {
                    continue;
                }
                try (NativeImage corrupted = new NativeImage(WIDTH, HEIGHT, false)) {
                    corrupted.copyFrom(image);
                    for (int row = 0; row < 64; row++) {
                        for (int column = 0; column < 64; column++) {
                            corrupted.setPixel(x + column, y + row, crop == null ? 0xff777777 : crop.getPixel(column * 2, row * 2));
                        }
                    }
                    Oracle rejected = oracle(corrupted, probes);
                    if (rejected.mismatched() <= MAX_ERRORS) {
                        throw new AssertionError("Adjacent independent oracle accepted " + name);
                    }
                    NetherCapturePngWriter.write(corrupted, directory.resolve("negative-" + name + ".png"));
                    synchronized (AdjacentSourcePortalsTap.class) {
                        INJECTIONS.add(new Injection(name, index, x, y, 64, checked, wrong, rejected));
                    }
                    return;
                }
            }
        }
        throw new AssertionError("Adjacent negative control could not find a checked guard region: " + name);
    }

    private static int palette(int argb) {
        int red = argb >> 16 & 255;
        int green = argb >> 8 & 255;
        int blue = argb & 255;
        if (red > 45 && red > green * 2 + 20 && red > blue * 2 + 20) {
            return 0;
        }
        if (green > 35 && green > red * 1.2D + 2 && green > blue * 1.5D + 10) {
            return 1;
        }
        if (blue > 24 && blue > red * 1.4D + 2 && blue > green * 1.3D + 2) {
            return 2;
        }
        if (red > 35 && green > 30 && red > blue * 2 && green > blue * 1.5D) {
            return 3;
        }
        if (green > 24 && blue > 24 && green > red * 1.5D && blue > red * 1.5D) {
            return 4;
        }
        return red > 30 && blue > 24 && red > green * 1.5D && blue > green * 1.5D ? 5 : -1;
    }

    private static Timing timing(Crossing crossing) {
        List<Frame> before = new ArrayList<>();
        List<Frame> after = new ArrayList<>();
        for (Frame candidate : FRAMES) {
            if (candidate.ended() < crossing.attempt().entered() && candidate.level() == crossing.attempt().sourceLevel()) {
                before.add(candidate);
            }
            if (candidate.ended() >= crossing.returned() && candidate.level() == crossing.destinationLevel()
                && candidate.ordinal() == crossing.ordinal()) {
                after.add(candidate);
            }
        }
        if (before.size() < 40 || after.size() < 50) {
            throw new AssertionError("Incomplete first-four timing windows at crossing " + crossing.ordinal());
        }
        long[] approach = before.subList(before.size() - 40, before.size()).stream().mapToLong(Frame::interval).sorted().toArray();
        long[] arrival = after.subList(0, 40).stream().mapToLong(Frame::interval).sorted().toArray();
        long[] settled = after.subList(10, 50).stream().mapToLong(Frame::interval).sorted().toArray();
        Frame first = after.getFirst();
        long median = approach[20];
        long slow = Arrays.stream(arrival).filter(value -> value > median * 2).count();
        return new Timing(crossing.ordinal(), crossing.attempt().portal(), first.index(), crossing.returned() - crossing.attempt().entered(),
            first.ended() - first.started(), first.interval(), median, arrival[39], arrival[37], settled[20], slow, capture);
    }

    private static void writeEvidence() {
        if (directory == null) {
            return;
        }
        List<Timing> timings = new ArrayList<>();
        if (failure == null) {
            try {
                for (Crossing crossing : CROSSINGS) {
                    timings.add(timing(crossing));
                }
            } catch (AssertionError error) {
                fail(error);
            }
        }
        Evidence evidence = new Evidence(capture, overviewReady, issued, received, processed, maximumOutstanding, meshSelections,
            scene, List.copyOf(FRAMES), List.copyOf(CROSSINGS), List.copyOf(CAPTURES), List.copyOf(INJECTIONS), List.copyOf(timings),
            failure == null ? null : failure.toString(),
            "Independent server-authored wall/cuboid and recursive endpoint ray model; stationary preparation waits for both visible aperture interiors; all later frames check direct wall surfaces and recursively visible aperture interiors; eight-pixel sample grid, matching ray-model samples one pixel in each cardinal direction, 0.15-block tile and 0.12-block aperture margins, maximum 12 mismatches per frame. Source/root mesh eligibility and rendered-source witnesses are separate from pixels. Captured times include readback and are not performance evidence. No coverage claim for unsampled pixels or recursion beyond three.");
        try {
            Files.writeString(directory.resolve("provenance.json"), new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        } catch (IOException error) {
            throw new AssertionError("Cannot write adjacent portal evidence", error);
        }
    }

    private static void check() {
        if (failure != null) {
            throw new AssertionError("Adjacent source portal regression failed", failure);
        }
    }

    private static void fail(Throwable error) {
        if (failure == null) {
            failure = error;
            error.printStackTrace();
        }
    }

    public record Attempt(UUID portal, UUID token, long generation, long entered, int frame, int player, int sourceLevel,
                          String sourceWorld, boolean forward, double motion, double previousDistance, double eyeDistance) {
    }

    private record Crossing(int ordinal, Attempt attempt, long returned, int destinationLevel, String destinationWorld) {
    }

    private record Frame(int index, long started, long ended, long interval, String world, int level, int ordinal) {
    }

    private record Pose(int frame, String world, int level, String playerWorld, int playerLevel, int ordinal, Vec3d camera, float[] matrix,
                        boolean sourceARendered, boolean sourceBRendered) {
    }

    private record Probe(int x, int y, int color, UUID portal, int depth) {
    }

    private record Oracle(int checked, int mismatched, int firstX, int firstY, Map<UUID, Integer> portalSamples) {
    }

    private record Capture(int index, Pose pose, String file, Oracle oracle) {
    }

    private record Work(int index, Pose pose, NativeImage image) {
    }

    private record Injection(String name, int frame, int x, int y, int size, int checked, int injectedMismatches, Oracle rejected) {
    }

    private record Timing(int ordinal, UUID portal, int firstDestinationFrame, long nativeCrossingNanos, long firstRenderNanos,
                          long firstFrameIntervalNanos, long approachMedianNanos, long maximumFirst40Nanos,
                          long p95First40Nanos, long settledMedianNanos, long framesAboveTwiceApproach, boolean captured) {
    }

    private record Evidence(boolean capture, boolean overviewReady, int issued, int received, int processed, int maximumOutstanding,
                            int meshSelections, AdjacentSourcePortalsScene scene, List<Frame> frames, List<Crossing> crossings,
                            List<Capture> captures, List<Injection> controls, List<Timing> timings, String failure, String oracleScope) {
    }
}
