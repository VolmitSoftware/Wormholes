package art.arcane.wormholes.clientgametest;

import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.ClientCrossingView;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
import art.arcane.wormholes.network.client.TravelMessage;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class NetherCrossingCompositionTap {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int GRID = 8;
    private static final int EDGE_MARGIN = 24;
    private static final List<Capture> CAPTURES = new ArrayList<>();
    private static List<Marker> markers = List.of();
    private static Path directory;
    private static String label;
    private static Pose pose;
    private static int physicalLevel;
    private static int swaps;
    private static int afterSwap;
    private static int issued;
    private static int completed;
    private static int corruptionRejections;
    private static Injection injection;
    private static boolean recording;
    private static Throwable failure;

    private NetherCrossingCompositionTap() {
    }

    static synchronized void arm(Minecraft minecraft, String name, List<Marker> scene) {
        if (issued != completed) {
            throw new AssertionError("Previous Nether framebuffer readbacks are still pending");
        }
        label = name;
        markers = List.copyOf(scene);
        directory = minecraft.gameDirectory.toPath().resolve("screenshots/nether-composition-" + name);
        physicalLevel = System.identityHashCode(minecraft.player.level());
        swaps = 0;
        afterSwap = 0;
        issued = 0;
        completed = 0;
        corruptionRejections = 0;
        injection = null;
        pose = null;
        failure = null;
        CAPTURES.clear();
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            throw new AssertionError("Cannot create Nether frame evidence", error);
        }
        recording = true;
    }

    static synchronized void beforeFrame(Minecraft minecraft) {
        pose = null;
        if (!recording || minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        WormholesClient client = WormholesClient.instance();
        ClientCrossingView view = client.seamlessTravel().view();
        PortalViews.Crossing crossing = view.crossing();
        Matrix4f projection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        float[] matrix = projection.get(new float[16]);
        List<Arm> arms = new ArrayList<>();
        for (TravelMessage.TravelBegin arm : client.seamlessTravel().arms()) {
            arms.add(new Arm(arm.sourcePortal().toString(), arm.token().toString(), arm.generation(), arm.levelHandle(),
                arm.sourceWorld(), arm.sourceGeometry().toString(), arm.sourceToDestination().toString()));
        }
        pose = new Pose(TravelTap.frameIndex(), System.nanoTime(), TravelTap.gameTickIndex(),
            camera.getCameraEntityPartialTicks(minecraft.getDeltaTracker()), point(camera.position()), camera.yRot(), camera.xRot(),
            point(new Vec3(camera.forwardVector().x(), camera.forwardVector().y(), camera.forwardVector().z())),
            point(new Vec3(camera.upVector().x(), camera.upVector().y(), camera.upVector().z())),
            minecraft.level.dimension().identifier().toString(), System.identityHashCode(minecraft.level),
            minecraft.player.level().dimension().identifier().toString(), System.identityHashCode(minecraft.player.level()),
            System.identityHashCode(minecraft.player), point(minecraft.player.position()), point(minecraft.player.getEyePosition()),
            minecraft.player.getYRot(), minecraft.player.getXRot(), minecraft.options.keyUp.isDown(), view.active(),
            crossing == null ? null : crossing.key().toString(), crossing == null ? null : crossing.toward().toString(),
            minecraft.options.fov().get(), minecraft.options.framerateLimit().get(),
            minecraft.gameRenderer.mainRenderTarget().width, minecraft.gameRenderer.mainRenderTarget().height, matrix, List.copyOf(arms));
    }

    static synchronized void frame(Minecraft minecraft) {
        if (!recording || minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (pose == null) {
            fail(new AssertionError("Missing render-head Nether camera provenance; install TravelTap.frameStarted dispatch"));
            return;
        }
        Pose rendered = pose;
        pose = null;
        int currentLevel = System.identityHashCode(minecraft.player.level());
        if (currentLevel != physicalLevel) {
            swaps++;
            afterSwap = 0;
            physicalLevel = currentLevel;
        }
        if (swaps > 0) {
            afterSwap++;
        }
        List<View> views = new ArrayList<>();
        List<Footprint> footprints = new ArrayList<>();
        Matrix4f matrix = new Matrix4f().set(rendered.viewProjection());
        Vec3d eye = vector(rendered.camera());
        for (Marker marker : markers) {
            if (marker.world().equals(rendered.renderWorld())) {
                addFootprint(footprints, marker, Similarity.IDENTITY, matrix, eye, null);
            }
        }
        for (PortalView view : WormholesClient.instance().portalViews().current()) {
            views.add(new View(view.kind().name(), view.key().toString(), view.source().dimension().identifier().toString(),
                System.identityHashCode(view.source()), view.destination().dimension().identifier().toString(),
                System.identityHashCode(view.destination()), view.surface().geometry().toString(),
                view.surface().placement().toString(), view.surface().reversed(), view.toDestination().toString(),
                view.surface().servesEye(eye)));
            if (view.source().dimension().identifier().toString().equals(rendered.renderWorld()) && view.surface().servesEye(eye)) {
                Rect aperture = aperture(view.surface(), matrix, eye);
                if (aperture == null) {
                    continue;
                }
                for (Marker marker : markers) {
                    if (marker.world().equals(view.destination().dimension().identifier().toString())) {
                        addFootprint(footprints, marker, view.toDestination().inverse(), matrix, eye, aperture);
                    }
                }
            }
        }
        int index = issued++;
        Capture capture = new Capture(index, swaps, afterSwap, rendered, List.copyOf(views), List.copyOf(footprints),
            "frame-" + String.format("%05d", index) + ".png", null);
        Path output = directory;
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> captured(output, capture, image));
    }

    static synchronized int framesAfterSwap() {
        return afterSwap;
    }

    static synchronized void stop() {
        recording = false;
        pose = null;
    }

    static synchronized boolean complete() {
        return issued == completed;
    }

    static synchronized void verify() {
        CAPTURES.sort((first, second) -> Integer.compare(first.index(), second.index()));
        writeEvidence();
        if (failure != null) {
            throw new AssertionError(label + ": Nether frame capture/spatial exclusion failed", failure);
        }
        if (recording || issued != completed || swaps != 2 || afterSwap < 16 || CAPTURES.size() < 18) {
            throw new AssertionError(label + ": incomplete continuous framebuffer coverage: frames=" + CAPTURES.size()
                + " swaps=" + swaps + " afterSwap=" + afterSwap + " readbacks=" + completed + '/' + issued);
        }
        int visibleFrames = 0;
        int previousTravelFrame = -1;
        for (Capture capture : CAPTURES) {
            if (previousTravelFrame >= 0 && capture.pose().travelFrame() != previousTravelFrame + 1) {
                throw new AssertionError(label + ": missing rendered framebuffer between travel frames " + previousTravelFrame
                    + " and " + capture.pose().travelFrame());
            }
            previousTravelFrame = capture.pose().travelFrame();
            if (capture.oracle().colored() >= 8) {
                visibleFrames++;
            }
        }
        if (visibleFrames < 2 || corruptionRejections != 1) {
            throw new AssertionError(label + ": spatial oracle unexercised: visibleFrames=" + visibleFrames
                + " corruptionRejections=" + corruptionRejections);
        }
        System.out.println("WORMHOLES_NETHER_COMPOSITION_CAPTURE_COMPLETE " + label + " frames=" + CAPTURES.size()
            + " swaps=" + swaps + " spatialExclusion=passed displacedDuplicateSelfCheck=rejected fullPixelCompositionProof=false timings=separate");
    }

    static synchronized void close() {
        stop();
        if (directory != null) {
            writeEvidence();
        }
    }

    private static synchronized void captured(Path output, Capture capture, NativeImage image) {
        try (image) {
            if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
                throw new AssertionError("Nether composition requires actual native 1920x1080 framebuffer");
            }
            image.writeToFile(output.resolve(capture.file()));
            Oracle result = inspect(image, capture.footprints());
            CAPTURES.add(new Capture(capture.index(), capture.swaps(), capture.afterSwap(), capture.pose(), capture.views(),
                capture.footprints(), capture.file(), result));
            if (result.escaped() > 12) {
                throw new AssertionError(label + " frame=" + capture.index() + ": marker pixels outside projected scene footprints: " + result);
            }
            if (corruptionRejections == 0 && result.colored() >= 16) {
                injection = rejectDisplacedDuplicate(image, capture);
                corruptionRejections++;
            }
        } catch (IOException | RuntimeException | AssertionError error) {
            fail(error);
        } finally {
            completed++;
        }
    }

    private static Oracle inspect(NativeImage image, List<Footprint> footprints) {
        int colored = 0;
        int escaped = 0;
        int firstX = -1;
        int firstY = -1;
        for (int y = GRID; y < HEIGHT - GRID; y += GRID) {
            for (int x = GRID; x < WIDTH - GRID; x += GRID) {
                int color = palette(image.getPixel(x, y));
                if (color < 0) {
                    continue;
                }
                colored++;
                if (!allowed(footprints, x, y, color)) {
                    escaped++;
                    if (firstX < 0) {
                        firstX = x;
                        firstY = y;
                    }
                }
            }
        }
        return new Oracle(colored, escaped, firstX, firstY);
    }

    private static Injection rejectDisplacedDuplicate(NativeImage image, Capture capture) {
        List<Footprint> footprints = capture.footprints();
        int sampleX = -1;
        int sampleY = -1;
        for (int y = GRID; y < HEIGHT - GRID && sampleX < 0; y += GRID) {
            for (int x = GRID; x < WIDTH - GRID; x += GRID) {
                int pixel = image.getPixel(x, y);
                int candidate = palette(pixel);
                if (candidate >= 0 && allowed(footprints, x, y, candidate)) {
                    sampleX = x;
                    sampleY = y;
                    break;
                }
            }
        }
        if (sampleX < 0) {
            throw new AssertionError("No grounded marker sample for duplicate corruption check");
        }
        int size = 256;
        int sourceX = Math.clamp(sampleX - size / 2, 0, WIDTH - size) / GRID * GRID;
        int sourceY = Math.clamp(sampleY - size / 2, 0, HEIGHT - size) / GRID * GRID;
        try (NativeImage corrupted = new NativeImage(WIDTH, HEIGHT, false)) {
            for (int targetY = GRID; targetY <= HEIGHT - size; targetY += size) {
                for (int targetX = GRID; targetX <= WIDTH - size; targetX += size) {
                    int displaced = 0;
                    for (int y = GRID; y < size - GRID; y += GRID) {
                        for (int x = GRID; x < size - GRID; x += GRID) {
                            int color = palette(image.getPixel(sourceX + x, sourceY + y));
                            if (color >= 0 && !allowed(footprints, targetX + x, targetY + y, color)) {
                                displaced++;
                            }
                        }
                    }
                    if (displaced <= 12) {
                        continue;
                    }
                    corrupted.copyFrom(image);
                    for (int y = 0; y < size; y++) {
                        for (int x = 0; x < size; x++) {
                            corrupted.setPixel(targetX + x, targetY + y, image.getPixel(sourceX + x, sourceY + y));
                        }
                    }
                    Oracle result = inspect(corrupted, footprints);
                    if (result.escaped() <= 12) {
                        throw new AssertionError("Spatial oracle accepted injected displaced marker image");
                    }
                    return new Injection(capture.index(), sourceX, sourceY, targetX, targetY, size, result.escaped());
                }
            }
            throw new AssertionError("No displaced actual-marker crop exercised the spatial oracle");
        }
    }

    private static boolean allowed(List<Footprint> footprints, int x, int y, int color) {
        for (Footprint footprint : footprints) {
            if (footprint.color() == color && footprint.bounds().contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    private static int palette(int argb) {
        int red = argb >> 16 & 255;
        int green = argb >> 8 & 255;
        int blue = argb & 255;
        if (red > 65 && red > green * 2 + 25 && red > blue * 2 + 25) {
            return 0;
        }
        if (blue > 55 && blue > red * 2 + 20 && blue > green * 1.5D + 20) {
            return 1;
        }
        return green > 70 && green > red * 1.3D + 15 && green > blue * 1.5D + 15 ? 2 : -1;
    }

    private static void addFootprint(List<Footprint> output, Marker marker, Similarity placement, Matrix4f matrix, Vec3d eye, Rect aperture) {
        List<Vec3d> corners = new ArrayList<>(8);
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    corners.add(placement.point(new Vec3d(marker.x() + x, marker.y() + y, marker.z() + z)));
                }
            }
        }
        Rect rect = project(corners, matrix, eye);
        if (rect != null) {
            Rect clipped = aperture == null ? rect : rect.intersect(aperture);
            if (clipped != null) {
                output.add(new Footprint(marker.color(), clipped));
            }
        }
    }

    private static Rect aperture(PortalSurface surface, Matrix4f matrix, Vec3d eye) {
        List<Vec3d> points = new ArrayList<>(4);
        for (int column = 0; column <= 1; column++) {
            for (int row = 0; row <= 1; row++) {
                points.add(surface.placement().point(surface.aperture().point(column * surface.geometry().apertureWidth(),
                    row * surface.geometry().apertureHeight())));
            }
        }
        return project(points, matrix, eye);
    }

    private static Rect project(List<Vec3d> points, Matrix4f matrix, Vec3d eye) {
        double minX = WIDTH;
        double minY = HEIGHT;
        double maxX = 0;
        double maxY = 0;
        int behind = 0;
        for (Vec3d point : points) {
            Vec3d relative = point.subtract(eye);
            Vector4f clip = matrix.transform(new Vector4f((float) relative.x(), (float) relative.y(), (float) relative.z(), 1.0F));
            if (clip.w <= 0.001F) {
                behind++;
                continue;
            }
            double x = (clip.x / clip.w + 1.0D) * WIDTH * 0.5D;
            double y = (1.0D - clip.y / clip.w) * HEIGHT * 0.5D;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        if (behind == points.size()) {
            return null;
        }
        if (behind > 0) {
            return new Rect(0, 0, WIDTH, HEIGHT);
        }
        return new Rect((int) Math.floor(minX) - EDGE_MARGIN, (int) Math.floor(minY) - EDGE_MARGIN,
            (int) Math.ceil(maxX) + EDGE_MARGIN, (int) Math.ceil(maxY) + EDGE_MARGIN);
    }

    private static void fail(Throwable error) {
        if (failure == null) {
            failure = error;
            error.printStackTrace();
        }
    }

    private static void writeEvidence() {
        try {
            Files.writeString(directory.resolve("provenance.json"), new GsonBuilder().setPrettyPrinting().create().toJson(
                new Evidence(label, markers, issued, completed, swaps, corruptionRejections,
                        "Conservative projected cuboid/aperture rectangles with 24px edge tolerance; excludes displaced marker pixels, does not certify duplicates within footprints or complete composition; capture timings are not performance evidence",
                    injection, failure == null ? null : failure.toString(), List.copyOf(CAPTURES))) + "\n");
        } catch (IOException error) {
            throw new AssertionError("Cannot persist Nether frame provenance", error);
        }
    }

    private static double[] point(Vec3 point) {
        return new double[] {point.x, point.y, point.z};
    }

    private static Vec3d vector(double[] point) {
        return new Vec3d(point[0], point[1], point[2]);
    }

    record Marker(String world, int x, int y, int z, int color) {
    }

    private record Rect(int minX, int minY, int maxX, int maxY) {
        boolean contains(int x, int y) {
            return x >= minX && y >= minY && x <= maxX && y <= maxY;
        }

        Rect intersect(Rect other) {
            int left = Math.max(minX, other.minX);
            int top = Math.max(minY, other.minY);
            int right = Math.min(maxX, other.maxX);
            int bottom = Math.min(maxY, other.maxY);
            return left <= right && top <= bottom ? new Rect(left, top, right, bottom) : null;
        }
    }

    private record Footprint(int color, Rect bounds) {
    }

    private record Arm(String sourcePortal, String token, long generation, int handle, String sourceWorld, String geometry, String transform) {
    }

    private record View(String kind, String key, String sourceWorld, int sourceLevel, String destinationWorld, int destinationLevel,
                        String geometry, String placement, boolean reversed, String transform, boolean servesEye) {
    }

    private record Pose(int travelFrame, long nanos, long gameTick, float partialTick, double[] camera, float cameraYaw, float cameraPitch,
                        double[] forward, double[] up, String renderWorld, int renderLevel, String playerWorld, int playerLevel,
                        int playerIdentity, double[] feet, double[] eye, float playerYaw, float playerPitch, boolean forwardHeld,
                        boolean crossingViewActive, String crossingKey, String crossingTransform, int fov, int frameCap,
                        int framebufferWidth, int framebufferHeight, float[] viewProjection, List<Arm> arms) {
    }

    private record Oracle(int colored, int escaped, int firstEscapedX, int firstEscapedY) {
    }

    private record Capture(int index, int swaps, int afterSwap, Pose pose, List<View> views, List<Footprint> footprints, String file, Oracle oracle) {
    }

    private record Injection(int frame, int sourceX, int sourceY, int targetX, int targetY, int size, int escaped) {
    }

    private record Evidence(String label, List<Marker> markers, int issued, int completed, int swaps, int corruptionRejections,
                            String oracleScope, Injection injection, String failure, List<Capture> captures) {
    }
}
