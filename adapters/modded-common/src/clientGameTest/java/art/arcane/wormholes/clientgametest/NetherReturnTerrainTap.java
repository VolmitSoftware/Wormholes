package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.PortalShaderScope;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL32C;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class NetherReturnTerrainTap {
    static final String WORLD = "minecraft:overworld";
    static final double CAMERA_X = 0.5D;
    static final double CAMERA_Y = 1.62D;
    static final double CAMERA_Z = -2.5D;
    static final int WALL_Z = 10;
    static final int MIN_X = -20;
    static final int MAX_X = 20;
    static final int MIN_FLOOR_Z = -8;
    static final int WALL_TOP = 18;
    static final int DOOR_X = 3;
    static final int DOOR_Z = 5;
    private static final String SHADER_PACK_PROPERTY = "wormholes.test.shaderPackRequested";
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int GRID = 8;
    private static final int MAX_FRAMES = 96;
    private static final int ACCEPTED_FRAMES = 8;
    private static final int CAPACITY = 8;
    private static final int MAX_ERRORS = 8;
    private static final int MIN_PROBES = 1000;
    private static final int MIN_CLASS = 64;
    private static final int MIN_SURFACE = 256;
    private static final int MIN_REGION = 64;
    private static final int MIN_DOOR = 64;
    private static final double MIN_CONTRAST = 24.0D;
    private static final double TILE_MARGIN = 0.18D;
    private static final double DOOR_MARGIN = 0.2D;
    private static final double OCCLUDER_MARGIN = 0.25D;
    private static final double SHADOW_REACH = 2.5D;
    private static final double DOOR_SLAB = 13.0D / 16.0D;
    private static final double POSE_TOLERANCE = 0.05D;
    private static final long READBACK_TIMEOUT_NANOS = 5_000_000_000L;
    private static final long OUTSTANDING_TIMEOUT_NANOS = 20_000_000_000L;
    private static final String SCOPE = "Main framebuffer readback of a stationary first-person view of a concrete tile wall and floor with a closed dark oak door, "
        + "probed on an eight-pixel grid with 0.18-block tile margins and matching cardinal-neighbor ray hits. Control windows are captured before the Nether round trip "
        + "with the landmark built around the waiting camera; each sample must match its own frame's color centroid for its tile and surface, and with shaders off it must "
        + "also match the original concrete palette. Target windows are captured after the round trip and a 400-block teleport into a landmark already present in the "
        + "streamed chunks; each sample must match the control centroids for its tile and surface. All windows allow at most 8 mismatches, require 24 RGB units between "
        + "every pair of tile colors on each surface, coverage of both outer quarters and both floor halves, and visible main-renderer sections. Door samples must be "
        + "closer to the control frame with the door than to the control frame without it wherever those differ by at least 24 RGB units. A window passes after 8 "
        + "consecutive passing frames within its first 96 frames. Unsampled pixels, sky, entities and frame timing are not certified.";
    private static final Map<String, Window> WINDOWS = new LinkedHashMap<>();
    private static final ArrayBlockingQueue<Work> QUEUE = new ArrayBlockingQueue<>(CAPACITY);
    private static Path directory;
    private static Thread worker;
    private static Throwable failure;
    private static boolean active;
    private static boolean closing;
    private static boolean shaderPackRequested;
    private static boolean shaders;
    private static String shaderPack;
    private static int initialPlayer;
    private static int sessionResets;
    private static long protocolFailures;
    private static int frame;
    private static ReadbackWatchdog readbacks = new ReadbackWatchdog(OUTSTANDING_TIMEOUT_NANOS);
    private static Window window;

    private NetherReturnTerrainTap() {
    }

    public static synchronized void beginFrame() {
        if (active) {
            frame++;
        }
    }

    public static synchronized void endFrame() {
        if (!active || window == null || !window.issuing) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null
            || System.identityHashCode(minecraft.player) != initialPlayer || minecraft.level != minecraft.player.level()
            || !minecraft.level.dimension().identifier().toString().equals(WORLD)
            || !WormholesClient.instance().session().active() || WormholesClient.instance().session().resets() != sessionResets
            || WormholesClient.instance().session().protocolFailures() != protocolFailures
            || !NativeTestWindow.hiddenAndUnfocused(minecraft) || !NativeTestWindow.mouseReleased(minecraft)
            || ClientRenderAccess.mainTarget(minecraft).width != WIDTH || ClientRenderAccess.mainTarget(minecraft).height != HEIGHT
            || PortalShaderScope.shaders() != shaders || shaders && !shaderPack.equals(Iris.getCurrentPackName())
            || !ClientGui.hudHidden(minecraft) || minecraft.options.bobView().get() || minecraft.options.ambientOcclusion().get()
            || minecraft.options.fovEffectScale().get() != 0.0D || minecraft.options.fov().get() != 70) {
            fail(new AssertionError("Nether return terrain frame " + frame + " left the connected hidden 1920x1080 fixed-camera Overworld view"));
            return;
        }
        CameraRenderState camera = ClientRenderAccess.gameState(minecraft).levelRenderState.cameraRenderState;
        BlockPos origin = window.origin;
        double x = camera.pos.x - origin.getX();
        double y = camera.pos.y - origin.getY();
        double z = camera.pos.z - origin.getZ();
        if (Math.abs(x - CAMERA_X) > POSE_TOLERANCE || Math.abs(y - CAMERA_Y) > POSE_TOLERANCE || Math.abs(z - CAMERA_Z) > POSE_TOLERANCE
            || Math.abs(Mth.wrapDegrees(camera.yRot)) > 0.01F || Math.abs(camera.xRot) > 0.01F) {
            fail(new AssertionError("Nether return terrain window " + window.name + " camera moved off the landmark pose in frame " + frame
                + ": " + x + "," + y + "," + z + " yaw=" + camera.yRot + " pitch=" + camera.xRot));
            return;
        }
        if (readbacks.outstanding() >= CAPACITY) {
            fail(new AssertionError("Nether return terrain readback capacity exceeded " + CAPACITY));
            return;
        }
        Pose pose = new Pose(frame, x, y, z, new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix).get(new float[16]),
            MainRendererSections.visible(minecraft));
        int index = window.issued++;
        if (window.issued >= MAX_FRAMES) {
            window.issuing = false;
        }
        readbacks.issue(System.nanoTime());
        Window target = window;
        Screenshot.takeScreenshot(ClientRenderAccess.mainTarget(minecraft), image -> queue(new Work(target, index, pose, image)));
        completeReadback(index);
    }

    static synchronized void open(Minecraft minecraft) {
        if (active || worker != null && worker.isAlive() || readbacks.outstanding() != 0) {
            throw new AssertionError("Previous Nether return terrain capture is still active");
        }
        directory = minecraft.gameDirectory.toPath().resolve("screenshots/nether-return-terrain");
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            throw new AssertionError("Cannot create Nether return terrain evidence", error);
        }
        WINDOWS.clear();
        QUEUE.clear();
        initialPlayer = System.identityHashCode(minecraft.player);
        sessionResets = WormholesClient.instance().session().resets();
        protocolFailures = WormholesClient.instance().session().protocolFailures();
        shaderPackRequested = Boolean.getBoolean(SHADER_PACK_PROPERTY);
        shaders = PortalShaderScope.shaders();
        shaderPack = shaders ? Iris.getCurrentPackName() : "OFF";
        frame = 0;
        readbacks = new ReadbackWatchdog(OUTSTANDING_TIMEOUT_NANOS);
        window = null;
        failure = null;
        closing = false;
        active = true;
        worker = new Thread(NetherReturnTerrainTap::consume, "wormholes-nether-return-terrain-capture");
        worker.setDaemon(true);
        worker.start();
    }

    static synchronized void requireShaderPack() {
        System.out.println("WORMHOLES_NETHER_RETURN_TERRAIN shaderPackRequested=" + shaderPackRequested + " shaders=" + shaders + " pack=" + shaderPack);
        if (shaderPackRequested && !shaders) {
            AssertionError error = new AssertionError("Nether return terrain run requested a shader pack, but Iris has no shader pack in use in the world; "
                + "the pack failed to load or compile");
            fail(error);
            throw error;
        }
    }

    static synchronized void begin(String name, BlockPos origin, Mode mode) {
        check();
        if (window != null && !window.done() || WINDOWS.containsKey(name)) {
            throw new AssertionError("Nether return terrain window " + name + " opened while another window is unfinished");
        }
        Window opened = new Window(name, origin.immutable(), mode);
        if (mode == Mode.TARGET) {
            Window control = WINDOWS.get("control");
            Window door = WINDOWS.get("control-door");
            if (control == null || door == null || !control.passed() || !door.passed()) {
                throw new AssertionError("Nether return terrain target window requires passing control windows");
            }
            opened.reference = door.centroids;
            opened.withoutDoor = control.doorPixels;
            opened.withDoor = door.doorPixels;
        }
        WINDOWS.put(name, opened);
        window = opened;
    }

    static synchronized boolean done(String name) {
        check();
        Window target = WINDOWS.get(name);
        return target != null && target.done();
    }

    static synchronized void require(String name) {
        check();
        Window target = WINDOWS.get(name);
        if (target == null || !target.done()) {
            throw new AssertionError("Nether return terrain window " + name + " never finished");
        }
        if (!target.passed()) {
            throw new AssertionError("Nether return terrain window " + name + " never showed the landmark for " + ACCEPTED_FRAMES
                + " consecutive main frames within " + MAX_FRAMES + ": frames=" + target.results.size() + " visibleSections="
                + target.visibleSummary() + " last=" + target.lastSummary());
        }
        if (target.mode == Mode.DOOR) {
            int discriminative = discriminative(WINDOWS.get("control").doorPixels, target.doorPixels);
            if (discriminative < MIN_DOOR) {
                throw new AssertionError("Nether return terrain door references differ at only " + discriminative + " samples");
            }
        }
        System.out.println("WORMHOLES_NETHER_RETURN_TERRAIN window=" + name + " mode=" + target.mode + " frames=" + target.results.size()
            + " firstAccepted=" + target.firstAccepted + " visibleSections=" + target.visibleSummary() + " shaderPackRequested=" + shaderPackRequested
            + " shaders=" + shaders + " pack=" + shaderPack);
    }

    static synchronized void stop() {
        active = false;
        closing = true;
        if (window != null) {
            window.issuing = false;
        }
    }

    static synchronized boolean complete() {
        return !active && (failure != null || readbacks.outstanding() == 0 && (worker == null || !worker.isAlive()));
    }

    static synchronized void persist() {
        Map<String, List<Result>> windows = new LinkedHashMap<>();
        for (Window target : WINDOWS.values()) {
            windows.put(target.name, new ArrayList<>(target.results.values()));
        }
        Evidence evidence = new Evidence(shaderPackRequested, shaders, shaderPack, GRID, MAX_ERRORS, ACCEPTED_FRAMES, MAX_FRAMES, SCOPE, readbacks.issued(),
            readbacks.completed(), failure == null ? null : failure.toString(), windows);
        try {
            Files.writeString(directory.resolve("nether-return-terrain.json"), new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        } catch (IOException error) {
            throw new AssertionError("Cannot save Nether return terrain evidence", error);
        }
    }

    static synchronized void verify() {
        check();
        for (String name : List.of("control", "control-door", "target", "second")) {
            Window target = WINDOWS.get(name);
            if (target == null || !target.passed()) {
                throw new AssertionError("Nether return terrain window " + name + " did not pass");
            }
        }
        if (!complete() || readbacks.outstanding() != 0) {
            throw new AssertionError("Nether return terrain readback incomplete: " + readbacks.completed() + "/" + readbacks.issued());
        }
        System.out.println("WORMHOLES_NETHER_RETURN_TERRAIN_PASS windows=4 acceptedFrames=" + ACCEPTED_FRAMES + " shaderPackRequested=" + shaderPackRequested
            + " shaders=" + shaders + " pack=" + shaderPack);
    }

    static int tile(int column, int row) {
        return ArrivalTerrainTap.tile(WORLD, column, row);
    }

    private static void completeReadback(int index) {
        long sync = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        try {
            int result = GL32C.glClientWaitSync(sync, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, READBACK_TIMEOUT_NANOS);
            if (result != GL32C.GL_ALREADY_SIGNALED && result != GL32C.GL_CONDITION_SATISFIED) {
                fail(new AssertionError("Nether return terrain readback " + index + " of window " + window.name + " did not reach the GPU: " + result));
                return;
            }
        } finally {
            GL32C.glDeleteSync(sync);
        }
        RenderSystem.executePendingTasks();
    }

    private static void queue(Work work) {
        synchronized (NetherReturnTerrainTap.class) {
            if (worker == null || !worker.isAlive() || !QUEUE.offer(work)) {
                work.image().close();
                readbacks.complete(System.nanoTime());
                work.window().inspected++;
                fail(new AssertionError("Nether return terrain readback worker unavailable at frame " + work.pose().frame()));
            }
        }
    }

    private static void consume() {
        try {
            while (true) {
                Work work = QUEUE.poll(100L, TimeUnit.MILLISECONDS);
                if (work != null) {
                    try (NativeImage image = work.image()) {
                        inspect(work, image);
                    } catch (Throwable error) {
                        synchronized (NetherReturnTerrainTap.class) {
                            fail(error);
                        }
                    } finally {
                        synchronized (NetherReturnTerrainTap.class) {
                            readbacks.complete(System.nanoTime());
                            work.window().inspected++;
                        }
                    }
                }
                synchronized (NetherReturnTerrainTap.class) {
                    if (closing && readbacks.outstanding() == 0) {
                        return;
                    }
                    if (readbacks.expired(System.nanoTime())) {
                        fail(new AssertionError("Nether return terrain readback timed out: " + readbacks.completed() + "/" + readbacks.issued()));
                        return;
                    }
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (NetherReturnTerrainTap.class) {
                fail(error);
            }
        }
    }

    private static void inspect(Work work, NativeImage image) throws IOException {
        if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
            throw new AssertionError("Nether return terrain framebuffer dimensions changed");
        }
        Window target = work.window();
        Probes probes = target.probes(work.pose());
        Oracle oracle = oracle(image, probes, target, work.pose().visible());
        int[] doorPixels = pixels(image, probes.door());
        String file = null;
        if (work.index() < 2 || work.index() % 16 == 0 || oracle.passed() || work.index() == MAX_FRAMES - 1) {
            file = target.name + "-" + String.format("%03d", work.index()) + ".png";
            NetherCapturePngWriter.write(image, directory.resolve(file));
        }
        List<Control> controls = List.of();
        if (target.mode == Mode.TARGET && oracle.passed() && target.controls.isEmpty()) {
            controls = List.of(erased(image, probes, target, work.pose(), "left-quarter", 0), erased(image, probes, target, work.pose(), "all", 1));
        }
        synchronized (NetherReturnTerrainTap.class) {
            target.record(new Result(work.index(), work.pose().frame(), work.pose().visible(), file, oracle, controls), doorPixels, oracle.centroids());
        }
    }

    private static Control erased(NativeImage original, Probes probes, Window target, Pose pose, String name, int region) throws IOException {
        try (NativeImage erased = new NativeImage(WIDTH, HEIGHT, false)) {
            erased.copyFrom(original);
            int right = region == 0 ? WIDTH / 4 : WIDTH;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < right; x++) {
                    erased.setPixel(x, y, 0xff000000);
                }
            }
            Oracle rejected = oracle(erased, probes, target, pose.visible());
            if (rejected.passed()) {
                throw new AssertionError("Nether return terrain oracle accepted the " + name + " erased copy of window " + target.name);
            }
            String file = target.name + "-negative-" + name + ".png";
            NetherCapturePngWriter.write(erased, directory.resolve(file));
            return new Control(name, file, rejected);
        }
    }

    private static Oracle oracle(NativeImage image, Probes probes, Window target, int visible) {
        int[][] counts = new int[2][4];
        double[][][] sums = new double[2][4][3];
        int[] regions = new int[4];
        for (Probe probe : probes.surface()) {
            int pixel = image.getPixel(probe.x(), probe.y());
            counts[probe.surface()][probe.color()]++;
            sums[probe.surface()][probe.color()][0] += pixel >> 16 & 255;
            sums[probe.surface()][probe.color()][1] += pixel >> 8 & 255;
            sums[probe.surface()][probe.color()][2] += pixel & 255;
            if (probe.x() < WIDTH / 4) {
                regions[0]++;
            } else if (probe.x() > WIDTH * 3 / 4) {
                regions[1]++;
            }
            if (probe.surface() == 1) {
                regions[probe.x() < WIDTH / 2 ? 2 : 3]++;
            }
        }
        double[][][] own = new double[2][4][3];
        double contrast = Double.POSITIVE_INFINITY;
        boolean coverage = probes.surface().size() >= MIN_PROBES;
        for (int surface = 0; surface < 2; surface++) {
            int total = 0;
            for (int color = 0; color < 4; color++) {
                total += counts[surface][color];
                if (counts[surface][color] == 0) {
                    contrast = 0.0D;
                    continue;
                }
                for (int component = 0; component < 3; component++) {
                    own[surface][color][component] = sums[surface][color][component] / counts[surface][color];
                }
            }
            coverage &= total >= MIN_SURFACE;
            for (int first = 0; first < 4 && contrast > 0.0D; first++) {
                for (int second = first + 1; second < 4; second++) {
                    contrast = Math.min(contrast, distance(own[surface][first], own[surface][second]));
                }
            }
        }
        for (int color = 0; color < 4; color++) {
            coverage &= counts[0][color] + counts[1][color] >= MIN_CLASS;
        }
        for (int region : regions) {
            coverage &= region >= MIN_REGION;
        }
        double[][][] reference = target.reference == null ? own : target.reference;
        int mismatches = 0;
        int paletteErrors = 0;
        for (Probe probe : probes.surface()) {
            int pixel = image.getPixel(probe.x(), probe.y());
            if (nearest(reference[probe.surface()], pixel) != probe.color()) {
                mismatches++;
            }
            if (ArrivalTerrainTap.palette(pixel) != probe.color()) {
                paletteErrors++;
            }
        }
        int doorSamples = 0;
        int doorMismatches = 0;
        if (target.withoutDoor != null) {
            for (int index = 0; index < probes.door().size(); index++) {
                int without = target.withoutDoor[index];
                int with = target.withDoor[index];
                if (contrast(without, with) < MIN_CONTRAST) {
                    continue;
                }
                doorSamples++;
                Probe probe = probes.door().get(index);
                int pixel = image.getPixel(probe.x(), probe.y());
                if (contrast(pixel, without) <= contrast(pixel, with)) {
                    doorMismatches++;
                }
            }
        }
        boolean palette = shaders || target.mode == Mode.TARGET || paletteErrors <= MAX_ERRORS;
        boolean door = target.withoutDoor == null || doorSamples >= MIN_DOOR && doorMismatches <= MAX_ERRORS;
        boolean passed = coverage && visible > 0 && mismatches <= MAX_ERRORS && contrast >= MIN_CONTRAST && palette && door;
        return new Oracle(probes.surface().size(), counts, regions, mismatches, paletteErrors, contrast, doorSamples, doorMismatches, visible, passed, own);
    }

    private static int nearest(double[][] centroids, int pixel) {
        int best = -1;
        double distance = Double.POSITIVE_INFINITY;
        double red = pixel >> 16 & 255;
        double green = pixel >> 8 & 255;
        double blue = pixel & 255;
        for (int color = 0; color < centroids.length; color++) {
            double[] centroid = centroids[color];
            double candidate = (centroid[0] - red) * (centroid[0] - red) + (centroid[1] - green) * (centroid[1] - green)
                + (centroid[2] - blue) * (centroid[2] - blue);
            if (candidate < distance) {
                distance = candidate;
                best = color;
            }
        }
        return best;
    }

    private static double distance(double[] first, double[] second) {
        double red = first[0] - second[0];
        double green = first[1] - second[1];
        double blue = first[2] - second[2];
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private static double contrast(int first, int second) {
        double red = (first >> 16 & 255) - (second >> 16 & 255);
        double green = (first >> 8 & 255) - (second >> 8 & 255);
        double blue = (first & 255) - (second & 255);
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private static int discriminative(int[] without, int[] with) {
        int count = 0;
        for (int index = 0; index < without.length; index++) {
            if (contrast(without[index], with[index]) >= MIN_CONTRAST) {
                count++;
            }
        }
        return count;
    }

    private static int[] pixels(NativeImage image, List<Probe> samples) {
        int[] pixels = new int[samples.size()];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = image.getPixel(samples.get(index).x(), samples.get(index).y());
        }
        return pixels;
    }

    private static Probes probes(Pose pose) {
        Matrix4f viewProjection = new Matrix4f().set(pose.matrix());
        if (!Float.isFinite(viewProjection.determinant()) || Math.abs(viewProjection.determinant()) < 0.000001F) {
            throw new AssertionError("Nether return terrain view projection is singular or nonfinite");
        }
        Matrix4f inverse = viewProjection.invert();
        Vector4f center = inverse.transform(new Vector4f(0.0F, 0.0F, 1.0F, 0.0F));
        if (!Float.isFinite(center.w) || Math.abs(center.w) < 0.00001F) {
            throw new AssertionError("Invalid Nether return terrain projection origin");
        }
        double[] camera = {pose.x(), pose.y(), pose.z()};
        double[] eye = {camera[0] + center.x / center.w, camera[1] + center.y / center.w, camera[2] + center.z / center.w};
        List<Probe> surface = new ArrayList<>(WIDTH * HEIGHT / (GRID * GRID));
        List<Probe> door = new ArrayList<>(256);
        for (int y = GRID; y < HEIGHT - GRID; y += GRID) {
            for (int x = GRID; x < WIDTH - GRID; x += GRID) {
                Hit hit = sample(inverse, camera, eye, x, y);
                if (hit == null || !hit.equals(sample(inverse, camera, eye, x - 1, y)) || !hit.equals(sample(inverse, camera, eye, x + 1, y))
                    || !hit.equals(sample(inverse, camera, eye, x, y - 1)) || !hit.equals(sample(inverse, camera, eye, x, y + 1))) {
                    continue;
                }
                Probe probe = new Probe(x, y, hit.color(), hit.surface(), hit.column(), hit.row());
                if (hit.surface() == 2) {
                    door.add(probe);
                } else {
                    surface.add(probe);
                }
            }
        }
        return new Probes(pose.x(), pose.y(), pose.z(), pose.matrix(), List.copyOf(surface), List.copyOf(door));
    }

    private static Hit sample(Matrix4f inverse, double[] camera, double[] eye, int x, int y) {
        Vector4f near = inverse.transform(new Vector4f(2.0F * (x + 0.5F) / WIDTH - 1.0F, 1.0F - 2.0F * (y + 0.5F) / HEIGHT, 0.0F, 1.0F));
        if (!Float.isFinite(near.w) || Math.abs(near.w) < 0.00001F) {
            return null;
        }
        double[] direction = {camera[0] + near.x / near.w - eye[0], camera[1] + near.y / near.w - eye[1], camera[2] + near.z / near.w - eye[2]};
        double wall = direction[2] > 0.000001D ? (WALL_Z - eye[2]) / direction[2] : Double.POSITIVE_INFINITY;
        double floor = direction[1] < -0.000001D ? -eye[1] / direction[1] : Double.POSITIVE_INFINITY;
        double distance = Math.min(wall, floor);
        if (!Double.isFinite(distance) || distance <= 0.0D) {
            return null;
        }
        double hitX = eye[0] + direction[0] * distance;
        double hitY = eye[1] + direction[1] * distance;
        double hitZ = eye[2] + direction[2] * distance;
        boolean onWall = wall <= floor;
        if (hitX < MIN_X || hitX > MAX_X + 1 || (onWall ? hitY > WALL_TOP + 1 : hitZ < MIN_FLOOR_Z)) {
            return null;
        }
        double door = box(eye, direction, DOOR_X - OCCLUDER_MARGIN, -OCCLUDER_MARGIN, DOOR_Z - OCCLUDER_MARGIN,
            DOOR_X + 1 + OCCLUDER_MARGIN, 2 + OCCLUDER_MARGIN, DOOR_Z + 1 + OCCLUDER_MARGIN);
        if (door > 0.0D && door < distance) {
            return doorFace(eye, direction, DOOR_Z) && doorFace(eye, direction, DOOR_Z + DOOR_SLAB) ? new Hit(-1, 2, 0, 0) : null;
        }
        if (!onWall && hitX > DOOR_X - SHADOW_REACH && hitX < DOOR_X + 1 + SHADOW_REACH && hitZ > DOOR_Z - SHADOW_REACH && hitZ < DOOR_Z + 1 + SHADOW_REACH) {
            return null;
        }
        double column = hitX;
        double row = onWall ? hitY : hitZ;
        if (edge(column) < TILE_MARGIN || edge(row) < TILE_MARGIN) {
            return null;
        }
        int u = (int) Math.floor(column);
        int v = (int) Math.floor(row);
        return new Hit(tile(u, v), onWall ? 0 : 1, u, v);
    }

    private static boolean doorFace(double[] eye, double[] direction, double plane) {
        if (direction[2] <= 0.000001D) {
            return false;
        }
        double distance = (plane - eye[2]) / direction[2];
        double x = eye[0] + direction[0] * distance;
        double y = eye[1] + direction[1] * distance;
        return distance > 0.0D && x > DOOR_X + DOOR_MARGIN && x < DOOR_X + 1 - DOOR_MARGIN && y > DOOR_MARGIN && y < 2 - DOOR_MARGIN;
    }

    private static double box(double[] eye, double[] direction, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double[] low = {minX, minY, minZ};
        double[] high = {maxX, maxY, maxZ};
        double entry = 0.0D;
        double exit = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 0.000001D) {
                if (eye[axis] < low[axis] || eye[axis] > high[axis]) {
                    return -1.0D;
                }
                continue;
            }
            double first = (low[axis] - eye[axis]) / direction[axis];
            double second = (high[axis] - eye[axis]) / direction[axis];
            entry = Math.max(entry, Math.min(first, second));
            exit = Math.min(exit, Math.max(first, second));
        }
        return exit >= entry && exit > 0.0D ? Math.max(entry, 0.000001D) : -1.0D;
    }

    private static double edge(double coordinate) {
        double fraction = coordinate - Math.floor(coordinate);
        return Math.min(fraction, 1.0D - fraction);
    }

    private static void check() {
        if (failure != null) {
            throw new AssertionError("Nether return terrain capture failed", failure);
        }
    }

    private static void fail(Throwable error) {
        if (failure == null) {
            failure = error;
            error.printStackTrace();
        }
    }

    enum Mode {
        CONTROL,
        DOOR,
        TARGET
    }

    private static final class Window {
        private final String name;
        private final BlockPos origin;
        private final Mode mode;
        private final TreeMap<Integer, Result> results = new TreeMap<>();
        private final TreeMap<Integer, int[]> doors = new TreeMap<>();
        private final TreeMap<Integer, double[][][]> owns = new TreeMap<>();
        private final List<Control> controls = new ArrayList<>(2);
        private double[][][] reference;
        private int[] withoutDoor;
        private int[] withDoor;
        private double[][][] centroids;
        private int[] doorPixels;
        private Probes probes;
        private boolean issuing = true;
        private int issued;
        private int inspected;
        private int cursor;
        private int consecutive;
        private int firstAccepted = -1;
        private boolean finished;

        private Window(String name, BlockPos origin, Mode mode) {
            this.name = name;
            this.origin = origin;
            this.mode = mode;
        }

        private Probes probes(Pose pose) {
            Probes cached;
            synchronized (NetherReturnTerrainTap.class) {
                cached = probes;
            }
            if (cached != null && cached.x() == pose.x() && cached.y() == pose.y() && cached.z() == pose.z()
                && Arrays.equals(cached.matrix(), pose.matrix())) {
                return cached;
            }
            Probes computed = NetherReturnTerrainTap.probes(pose);
            if (withoutDoor != null && computed.door().size() != withoutDoor.length) {
                throw new AssertionError("Nether return terrain window " + name + " door samples " + computed.door().size()
                    + " differ from the control layout " + withoutDoor.length);
            }
            synchronized (NetherReturnTerrainTap.class) {
                probes = computed;
            }
            return computed;
        }

        private void record(Result result, int[] door, double[][][] own) {
            results.put(result.index(), result);
            doors.put(result.index(), door);
            owns.put(result.index(), own);
            if (!result.controls().isEmpty()) {
                controls.addAll(result.controls());
            }
            while (!finished && results.containsKey(cursor)) {
                Result next = results.get(cursor);
                consecutive = next.oracle().passed() ? consecutive + 1 : 0;
                cursor++;
                if (consecutive == ACCEPTED_FRAMES) {
                    firstAccepted = cursor - ACCEPTED_FRAMES;
                    accept();
                } else if (cursor >= MAX_FRAMES) {
                    finished = true;
                    issuing = false;
                }
            }
        }

        private void accept() {
            int samples = doors.get(firstAccepted).length;
            double[][][] mean = new double[2][4][3];
            long[][] channels = new long[samples][3];
            for (int index = firstAccepted; index < firstAccepted + ACCEPTED_FRAMES; index++) {
                double[][][] own = owns.get(index);
                for (int surface = 0; surface < 2; surface++) {
                    for (int color = 0; color < 4; color++) {
                        for (int component = 0; component < 3; component++) {
                            mean[surface][color][component] += own[surface][color][component] / ACCEPTED_FRAMES;
                        }
                    }
                }
                int[] door = doors.get(index);
                for (int sample = 0; sample < samples; sample++) {
                    channels[sample][0] += door[sample] >> 16 & 255;
                    channels[sample][1] += door[sample] >> 8 & 255;
                    channels[sample][2] += door[sample] & 255;
                }
            }
            int[] averaged = new int[samples];
            for (int sample = 0; sample < samples; sample++) {
                averaged[sample] = 0xff000000 | (int) (channels[sample][0] / ACCEPTED_FRAMES) << 16
                    | (int) (channels[sample][1] / ACCEPTED_FRAMES) << 8 | (int) (channels[sample][2] / ACCEPTED_FRAMES);
            }
            centroids = mean;
            doorPixels = averaged;
            finished = true;
            issuing = false;
            if (mode == Mode.TARGET && controls.size() != 2) {
                firstAccepted = -1;
                centroids = null;
            }
        }

        private boolean done() {
            return finished && inspected == issued;
        }

        private boolean passed() {
            return finished && firstAccepted >= 0;
        }

        private String visibleSummary() {
            int minimum = Integer.MAX_VALUE;
            int maximum = 0;
            for (Result result : results.values()) {
                minimum = Math.min(minimum, result.visible());
                maximum = Math.max(maximum, result.visible());
            }
            return results.isEmpty() ? "none" : minimum + ".." + maximum;
        }

        private String lastSummary() {
            if (results.isEmpty()) {
                return "none";
            }
            Result last = results.lastEntry().getValue();
            Oracle oracle = last.oracle();
            return "frame=" + last.frame() + " visibleSections=" + last.visible() + " samples=" + oracle.checked() + " mismatches=" + oracle.mismatches()
                + " minimumContrast=" + Math.round(oracle.minimumContrast()) + " doorSamples=" + oracle.doorSamples() + " doorMismatches=" + oracle.doorMismatches()
                + " image=" + last.file();
        }
    }

    private record Pose(int frame, double x, double y, double z, float[] matrix, int visible) {
    }

    private record Work(Window window, int index, Pose pose, NativeImage image) {
    }

    private record Hit(int color, int surface, int column, int row) {
    }

    private record Probe(int x, int y, int color, int surface, int column, int row) {
    }

    private record Probes(double x, double y, double z, float[] matrix, List<Probe> surface, List<Probe> door) {
    }

    private record Oracle(int checked, int[][] colors, int[] outerQuartersAndFloorHalves, int mismatches, int paletteErrors, double minimumContrast,
                          int doorSamples, int doorMismatches, int visibleSections, boolean passed, double[][][] centroids) {
    }

    private record Control(String name, String file, Oracle rejected) {
    }

    private record Result(int index, int frame, int visible, String file, Oracle oracle, List<Control> controls) {
    }

    private record Evidence(boolean shaderPackRequested, boolean shaders, String shaderPack, int pixelGrid, int maximumMismatches, int acceptedFrames,
                            int maximumFrames, String oracleScope, int issued, int completed, String failure, Map<String, List<Result>> windows) {
    }
}
