package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.ClientCrossingView;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.network.client.TravelMessage;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class NetherCrossingEffectsTap {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int GRID = 8;
    private static final int MAX_FRAMES = 2048;
    private static final int MAX_ERRORS = 12;
    private static final int MAX_READINESS_FRAMES = 256;
    private static final int MAX_PENDING_CAPTURES = 32;
    private static final List<Capture> CAPTURES = new ArrayList<>();
    private static final List<Layer> LAYERS = new ArrayList<>();
    private static final List<NativeCrossing> CROSSINGS = new ArrayList<>();
    private static List<Marker> markers = List.of();
    private static Map<String, List<Wall>> walls = Map.of();
    private static List<Occluder> occluders = List.of();
    private static List<PortalView> views = List.of();
    private static Path directory;
    private static String label;
    private static Pose pose;
    private static Matrix4f renderProjection;
    private static Effects renderEffects;
    private static Vec3 start;
    private static int physicalLevel;
    private static int swaps;
    private static int afterSwap;
    private static int issued;
    private static int completed;
    private static int renderedFrames;
    private static boolean recording;
    private static boolean frameInProgress;
    private static boolean leadIn;
    private static Throwable failure;
    private static Injection injection;
    private static boolean preparing;
    private static int readyFrames;
    private static CaptureWorker worker;

    private NetherCrossingEffectsTap() {
    }

    static void arm(Minecraft minecraft, String name, List<Marker> scene, List<Occluder> physicalBoxes) {
        arm(minecraft, name, scene, physicalBoxes, false);
    }

    private static void arm(Minecraft minecraft, String name, List<Marker> scene, List<Occluder> physicalBoxes, boolean warmup) {
        CaptureWorker previous;
        synchronized (NetherCrossingEffectsTap.class) {
            if (recording || issued != completed) {
                throw new AssertionError("Previous camera-effects readbacks are pending");
            }
            previous = worker;
        }
        if (previous != null) {
            previous.closeAndAwait();
        }
        Path output = minecraft.gameDirectory.toPath().resolve("screenshots/nether-effects-" + name);
        try {
            Files.createDirectories(output);
        } catch (IOException error) {
            throw new AssertionError("Cannot create camera-effects evidence", error);
        }
        Map<String, List<Wall>> boundaries = walls(scene);
        synchronized (NetherCrossingEffectsTap.class) {
            label = name;
            markers = List.copyOf(scene);
            walls = boundaries;
            occluders = List.copyOf(physicalBoxes);
            directory = output;
            start = minecraft.player.position();
            physicalLevel = System.identityHashCode(minecraft.player.level());
            swaps = 0;
            afterSwap = 0;
            issued = 0;
            completed = 0;
            renderedFrames = 0;
            leadIn = false;
            failure = null;
            injection = null;
            preparing = warmup;
            readyFrames = 0;
            pose = null;
            renderProjection = null;
            renderEffects = null;
            frameInProgress = false;
            CAPTURES.clear();
            LAYERS.clear();
            CROSSINGS.clear();
            worker = new CaptureWorker();
            worker.start();
            recording = true;
        }
    }

    static void prepare(Minecraft minecraft, String name, List<Marker> scene, List<Occluder> physicalBoxes) {
        arm(minecraft, name + "-readiness", scene, physicalBoxes, true);
    }

    static synchronized boolean ready() {
        if (failure != null) {
            throw new AssertionError(label + ": scene readiness failed", failure);
        }
        return readyFrames >= 3;
    }

    public static synchronized void beginFrame() {
        frameInProgress = recording;
        if (!recording) {
            return;
        }
        renderedFrames++;
        pose = null;
        renderProjection = null;
        renderEffects = null;
        views = List.of();
        LAYERS.clear();
        int limit = preparing ? MAX_READINESS_FRAMES : MAX_FRAMES;
        if (renderedFrames > limit) {
            fail(new AssertionError("Camera-effects capture exceeded " + limit + " rendered frames"));
            recording = false;
            frameInProgress = false;
        }
    }

    public static synchronized void projection(Matrix4f projection, Effects effects) {
        if (!frameInProgress || PortalStencilRenderer.instance().nested()) {
            return;
        }
        if (renderProjection != null) {
            fail(new AssertionError("Multiple main effect-adjusted projections in one rendered frame"));
            return;
        }
        renderProjection = new Matrix4f(projection);
        renderEffects = effects;
    }

    private static void capturePose(Matrix4f projection, Effects effects) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || !minecraft.gameRenderer.mainCamera().isInitialized()) {
            fail(new AssertionError("Native client disconnected during continuous effects capture"));
            return;
        }
        CameraRenderState camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        ClientCrossingView crossingView = WormholesClient.instance().seamlessTravel().view();
        float partial = minecraft.gameRenderer.mainCamera().getCameraEntityPartialTicks(minecraft.getDeltaTracker());
        Vec3 physicalEye = minecraft.player.getEyePosition();
        Vec3 interpolatedEye = minecraft.player.getEyePosition(partial);
        List<Arm> arms = new ArrayList<>();
        for (TravelMessage.TravelBegin arm : WormholesClient.instance().seamlessTravel().arms()) {
            arms.add(new Arm(arm.sourcePortal().toString(), arm.token().toString(), arm.generation(), arm.levelHandle(),
                arm.sourceWorld(), arm.sourceGeometry().toString(), arm.sourceToDestination().toString(),
                arm.sourceGeometry().signedDistance(physicalEye.x, physicalEye.y, physicalEye.z),
                arm.sourceGeometry().signedDistance(interpolatedEye.x, interpolatedEye.y, interpolatedEye.z)));
        }
        views = List.copyOf(WormholesClient.instance().portalViews().current());
        List<View> viewStates = new ArrayList<>();
        for (PortalView view : views) {
            viewStates.add(new View(view.kind().name(), view.key().toString(),
                view.source().dimension().identifier().toString(), System.identityHashCode(view.source()),
                view.destination().dimension().identifier().toString(), System.identityHashCode(view.destination()),
                view.recursion(), view.surface().geometry().toString(), view.surface().placement().toString(),
                view.toDestination().toString(), view.surface().signedDistance(vector(camera.pos)),
                view.surface().signedDistance(vector(physicalEye)), view.surface().signedDistance(vector(interpolatedEye))));
        }
        boolean hidden = NativeTestWindow.hiddenAndUnfocused(minecraft);
        boolean released = NativeTestWindow.mouseReleased(minecraft);
        if (!hidden || !released || !minecraft.options.bobView().get() || minecraft.options.fovEffectScale().get() != 1.0D) {
            fail(new AssertionError("Hidden rendering or normal camera-effect settings changed during capture"));
        }
        int level = System.identityHashCode(minecraft.player.level());
        boolean walking = minecraft.options.keyUp.isDown() && minecraft.player.position().distanceTo(start) > 1.0D
            && Math.abs(camera.entityRenderState.bob) > 0.001F;
        synchronized (NetherCrossingEffectsTap.class) {
            if (level != physicalLevel) {
                swaps++;
                afterSwap = 0;
                physicalLevel = level;
            }
            if (swaps > 0) {
                afterSwap++;
            }
            if (swaps == 0 && walking) {
                leadIn = true;
            }
        }
        Matrix4f finalViewProjection = new Matrix4f(projection).mul(camera.viewRotationMatrix);
        pose = new Pose(renderedFrames, TravelTap.frameIndex(), TravelTap.gameTickIndex(), System.nanoTime(), partial,
            effects, point(camera.pos), camera.yRot, camera.xRot, minecraft.level.dimension().identifier().toString(),
            System.identityHashCode(minecraft.level), minecraft.player.level().dimension().identifier().toString(), level,
            System.identityHashCode(minecraft.player), point(minecraft.player.position()), point(physicalEye), point(interpolatedEye),
            point(minecraft.player.oldPosition()), minecraft.player.getYRot(), minecraft.player.getXRot(),
            minecraft.options.keyUp.isDown(), minecraft.player.isSprinting(), crossingView.active(),
            crossingView.crossing() == null ? null : crossingView.crossing().key().toString(),
            crossingView.crossing() == null ? null : crossingView.crossing().toward().toString(), crossingView.bobFactor(),
            camera.entityRenderState.backwardsInterpolatedWalkDistance, camera.entityRenderState.bob,
            minecraft.player.walkAnimation.position(partial), minecraft.player.walkAnimation.speed(partial),
            minecraft.player.xBob, minecraft.player.yBob, minecraft.player.xBobO, minecraft.player.yBobO,
            minecraft.player.portalEffectIntensity, minecraft.player.oPortalEffectIntensity,
            minecraft.player.getEffectBlendFactor(MobEffects.NAUSEA, partial), minecraft.player.hurtTime,
            minecraft.options.bobView().get(), minecraft.options.fovEffectScale().get(), minecraft.options.fov().get(),
            hidden, released, minecraft.gameRenderer.mainRenderTarget().width, minecraft.gameRenderer.mainRenderTarget().height,
            matrix(projection), matrix(camera.projectionMatrix),
            matrix(camera.viewRotationMatrix), matrix(finalViewProjection), List.copyOf(arms), List.copyOf(viewStates));
    }

    public static synchronized void layer(PortalView view, int depth) {
        if (frameInProgress) {
            LAYERS.add(new Layer(depth, view.kind().name(), view.key().toString(), view.recursion(),
                view.source().dimension().identifier().toString(), view.destination().dimension().identifier().toString()));
        }
    }

    public static synchronized CrossingAttempt entering(Minecraft minecraft, LocalPlayer player, TravelMessage.TravelBegin arm,
                                                        float partial, Vec3 previous, Vec3 eye) {
        if (!recording) {
            return null;
        }
        return new CrossingAttempt(renderedFrames, TravelTap.frameIndex(), TravelTap.gameTickIndex(), System.nanoTime(),
            System.identityHashCode(player), System.identityHashCode(player.level()), player.level().dimension().identifier().toString(),
            System.identityHashCode(minecraft.level), minecraft.level.dimension().identifier().toString(),
            arm.sourcePortal().toString(), arm.token().toString(), arm.generation(), arm.levelHandle(), arm.sourceWorld(),
            arm.sourceToDestination().toString(), partial, point(previous), point(eye), point(player.getEyePosition()),
            point(player.getEyePosition(partial)), arm.sourceGeometry().signedDistance(previous.x, previous.y, previous.z),
            arm.sourceGeometry().signedDistance(eye.x, eye.y, eye.z), player.getYRot(), player.getXRot(), minecraft.options.keyUp.isDown());
    }

    public static synchronized void returned(CrossingAttempt attempt, Minecraft minecraft, Vec3 result) {
        if (attempt == null) {
            return;
        }
        CROSSINGS.add(new NativeCrossing(attempt, result != null, result == null ? null : point(result),
            System.identityHashCode(minecraft.player), System.identityHashCode(minecraft.player.level()),
            minecraft.player.level().dimension().identifier().toString(), System.nanoTime()));
    }

    public static void endFrame() {
        Matrix4f projection;
        Effects effects;
        synchronized (NetherCrossingEffectsTap.class) {
            if (!frameInProgress) {
                return;
            }
            frameInProgress = false;
            projection = renderProjection;
            effects = renderEffects;
        }
        if (projection == null) {
            fail(new AssertionError("Rendered frame lacks the final effect-adjusted projection"));
            return;
        }
        capturePose(projection, effects);
        if (pose == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Pose rendered = pose;
        Samples samples = probes(rendered, views);
        List<Opening> apertures = openings(rendered, views);
        CaptureRequest request;
        CaptureWorker active;
        synchronized (NetherCrossingEffectsTap.class) {
            active = worker;
            int index = issued;
            if (!active.reserve(index)) {
                recording = false;
                fail(new AssertionError(label + ": camera-effects capture queue overflow at frame=" + index
                    + " capacity=" + MAX_PENDING_CAPTURES + "; consecutive framebuffer evidence is incomplete"));
                return;
            }
            issued++;
            Capture capture = new Capture(index, swaps, afterSwap, rendered, List.copyOf(LAYERS), samples.probes(), samples.occluded(), apertures,
                "frame-" + String.format("%05d", index) + ".png", null);
            request = new CaptureRequest(directory, label, preparing, capture);
        }
        try {
            Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> active.accept(request, image));
        } catch (Throwable error) {
            active.readbackFailed(request, error);
        }
    }

    static synchronized boolean walkingLeadIn() {
        if (failure != null) {
            throw new AssertionError(label + ": camera-effects capture failed", failure);
        }
        return leadIn;
    }

    static synchronized int framesAfterSwap() {
        if (failure != null) {
            throw new AssertionError(label + ": camera-effects capture failed", failure);
        }
        return afterSwap;
    }

    static synchronized void stop() {
        recording = false;
    }

    static synchronized boolean complete() {
        return issued == completed && (worker == null || worker.drained());
    }

    static void verify(float outboundYaw) {
        CaptureWorker active;
        synchronized (NetherCrossingEffectsTap.class) {
            if (recording || issued != completed) {
                throw new AssertionError(label + ": camera-effects verification requires drained readbacks");
            }
            active = worker;
        }
        active.closeAndAwait();
        try {
            verifyCapture(outboundYaw);
        } catch (AssertionError error) {
            fail(error);
            writeEvidence();
            throw error;
        }
    }

    private static void verifyCapture(float outboundYaw) {
        writeEvidence();
        if (failure != null) {
            throw new AssertionError(label + ": camera-effects acceptance failed", failure);
        }
        if (recording || issued != completed || renderedFrames != issued || swaps != 2 || afterSwap < 16 || !leadIn || injection == null) {
            throw new AssertionError(label + ": incomplete effects coverage: frames=" + issued + '/' + renderedFrames
                + " readbacks=" + completed + " swaps=" + swaps + " afterSwap=" + afterSwap + " leadIn=" + leadIn
                + " deeperDuplicateRejected=" + (injection != null));
        }
        int acceptedCrossings = 0;
        for (NativeCrossing crossing : CROSSINGS) {
            if (crossing.accepted()) {
                acceptedCrossings++;
                if (acceptedCrossings == 1 && Math.abs(Math.IEEEremainder(crossing.attempt().yaw() - outboundYaw, 360.0D)) > 0.5D) {
                    throw new AssertionError(label + ": native crossing angle did not match requested yaw " + outboundYaw);
                }
                if (!crossing.attempt().forwardHeld() || crossing.attempt().playerIdentity() != crossing.returnedPlayer()
                    || crossing.attempt().playerLevel() == crossing.returnedLevel()) {
                    throw new AssertionError(label + ": accepted crossing lacks native held-W/player/resident-level continuity");
                }
            }
        }
        if (acceptedCrossings != 2) {
            throw new AssertionError(label + ": expected exactly two accepted native cross calls, found " + acceptedCrossings);
        }
        int[] visible = new int[3];
        boolean nearPlaneBob = false;
        boolean effectAdjusted = false;
        boolean arm = false;
        boolean returning = false;
        boolean renderedLayer = false;
        int activeCrossings = 0;
        for (int index = 0; index < CAPTURES.size(); index++) {
            Capture capture = CAPTURES.get(index);
            if (capture.index() != index || capture.pose().renderFrame() != index + 1) {
                throw new AssertionError("Missing consecutive rendered frame at " + index);
            }
            if (capture.oracle().checked() >= 64) {
                visible[capture.swaps()]++;
            }
            if (capture.swaps() > 0 && capture.afterSwap() == 1 && capture.oracle().checked() < 64) {
                throw new AssertionError(label + ": first post-swap image has insufficient depth sentinel coverage");
            }
            renderedLayer |= !capture.layers().isEmpty();
            activeCrossings += capture.pose().crossingViewActive() ? 1 : 0;
            for (Arm candidate : capture.pose().arms()) {
                if (candidate.sourceWorld().equals(capture.pose().playerWorld()) && Math.abs(candidate.interpolatedSignedDistance()) < 1.0D
                    && Math.abs(capture.pose().extractedBob()) > 0.001F && capture.pose().forwardHeld()) {
                    nearPlaneBob = true;
                    for (int element = 0; element < 16; element++) {
                        effectAdjusted |= Math.abs(capture.pose().finalProjection().get(element) - capture.pose().baseProjection().get(element)) > 0.00001F;
                    }
                }
            }
            for (View view : capture.pose().views()) {
                arm |= view.kind().equals("ARM");
                returning |= view.kind().equals("RETURN");
            }
        }
        if (visible[0] < 2 || visible[1] < 1 || visible[2] < 2 || !nearPlaneBob || !effectAdjusted || !arm || !returning || !renderedLayer) {
            throw new AssertionError(label + ": unexercised sentinel/camera path: visible=" + List.of(visible[0], visible[1], visible[2])
                + " nearPlaneBob=" + nearPlaneBob + " effectAdjusted=" + effectAdjusted + " ARM=" + arm
                + " RETURN=" + returning + " renderedLayer=" + renderedLayer);
        }
        System.out.println("WORMHOLES_NETHER_EFFECTS_CAPTURE_COMPLETE " + label + " frames=" + issued
            + " swaps=" + swaps + " bobbing=true finalProjection=true interiorSentinel=passed deeperApertureCrop=rejected"
            + " activeCrossingFrames=" + activeCrossings + " fullPixelCompositionProof=false timings=separate");
    }

    static void close() {
        CaptureWorker active;
        synchronized (NetherCrossingEffectsTap.class) {
            recording = false;
            active = worker;
        }
        if (active != null) {
            active.requestClose();
        }
    }

    private static Samples probes(Pose rendered, List<PortalView> current) {
        List<Probe> output = new ArrayList<>();
        List<OccludedProbe> blocked = new ArrayList<>();
        Matrix4f inverse = projection(rendered.finalViewProjection()).invert();
        Vec3d eye = vector(rendered.camera());
        Vector4f opticalCenter = inverse.transform(new Vector4f(0.0F, 0.0F, 1.0F, 0.0F));
        if (Math.abs(opticalCenter.w) < 0.00001F) {
            throw new AssertionError("Cannot recover the effect-adjusted perspective origin");
        }
        Vec3d rayOrigin = eye.add(new Vec3d(opticalCenter.x / opticalCenter.w, opticalCenter.y / opticalCenter.w,
            opticalCenter.z / opticalCenter.w));
        for (int y = GRID; y < HEIGHT - GRID; y += GRID) {
            for (int x = GRID; x < WIDTH - GRID; x += GRID) {
                float clipX = 2.0F * (x + 0.5F) / WIDTH - 1.0F;
                float clipY = 1.0F - 2.0F * (y + 0.5F) / HEIGHT;
                Vector4f near = inverse.transform(new Vector4f(clipX, clipY, 0.0F, 1.0F));
                if (Math.abs(near.w) < 0.00001F) {
                    continue;
                }
                Vec3d offset = new Vec3d(near.x / near.w, near.y / near.w, near.z / near.w);
                Vec3d direction = eye.add(offset).subtract(rayOrigin).normalize();
                PortalView selected = null;
                double nearest = Double.POSITIVE_INFINITY;
                boolean apertureMargin = false;
                for (PortalView view : current) {
                    if (!view.source().dimension().identifier().toString().equals(rendered.renderWorld()) || !view.surface().servesEye(eye)) {
                        continue;
                    }
                    apertureMargin |= apertureMargin(view.surface(), rayOrigin, direction);
                    double distance = intersection(view.surface(), rayOrigin, direction);
                    if (distance < nearest) {
                        selected = view;
                        nearest = distance;
                    }
                }
                if (apertureMargin) {
                    continue;
                }
                Occlusion foreground = selected == null ? null : occlusion(rendered.renderWorld(), rayOrigin, direction, 0.0D, nearest);
                if (foreground != null) {
                    blocked.add(new OccludedProbe(x, y, 0, foreground.box(), foreground.distance()));
                    continue;
                }
                Vec3d destinationEye = selected == null ? rayOrigin : selected.toDestination().point(rayOrigin);
                Vec3d destinationRay = selected == null ? direction : selected.toDestination().direction(direction).normalize();
                String world = selected == null ? rendered.renderWorld() : selected.destination().dimension().identifier().toString();
                Hit marker = wall(world, destinationEye, destinationRay);
                if (marker != null) {
                    double from = selected == null ? 0.0D : selected.toDestination().point(rayOrigin.add(direction.multiply(nearest)))
                        .subtract(destinationEye).dot(destinationRay);
                    Occlusion obstruction = occlusion(world, destinationEye, destinationRay, from, marker.distance());
                    if (obstruction != null) {
                        blocked.add(new OccludedProbe(x, y, selected == null ? 0 : 1, obstruction.box(), obstruction.distance()));
                        continue;
                    }
                    output.add(new Probe(x, y, marker.color(), selected == null ? null : selected.key().toString(),
                        selected == null ? 0 : 1, marker.distance()));
                }
            }
        }
        return new Samples(List.copyOf(output), List.copyOf(blocked));
    }

    private static Occlusion occlusion(String world, Vec3d eye, Vec3d direction, double from, double until) {
        Occlusion nearest = null;
        for (Occluder box : occluders) {
            if (!box.world().equals(world)) {
                continue;
            }
            double entry = Math.max(0.0D, from);
            double exit = until;
            for (int axis = 0; axis < 3; axis++) {
                double min = switch (axis) {
                    case 0 -> box.x();
                    case 1 -> box.y();
                    default -> box.z();
                };
                double component = direction.component(axis);
                if (Math.abs(component) < 0.000001D) {
                    if (eye.component(axis) < min || eye.component(axis) > min + 1.0D) {
                        exit = -1.0D;
                        break;
                    }
                    continue;
                }
                double first = (min - eye.component(axis)) / component;
                double second = (min + 1.0D - eye.component(axis)) / component;
                entry = Math.max(entry, Math.min(first, second));
                exit = Math.min(exit, Math.max(first, second));
            }
            if (exit > entry + 0.000001D && (nearest == null || entry < nearest.distance())) {
                nearest = new Occlusion(box, entry);
            }
        }
        return nearest;
    }

    private static List<Opening> openings(Pose rendered, List<PortalView> current) {
        List<Opening> output = new ArrayList<>();
        Matrix4f matrix = projection(rendered.finalViewProjection());
        Vec3d eye = vector(rendered.camera());
        for (PortalView view : current) {
            if (!view.source().dimension().identifier().toString().equals(rendered.renderWorld()) || !view.surface().servesEye(eye)) {
                continue;
            }
            List<Pixel> polygon = new ArrayList<>(4);
            for (int[] corner : new int[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
                double u = corner[0] == 0 ? 0.12D : view.surface().geometry().apertureWidth() - 0.12D;
                double v = corner[1] == 0 ? 0.12D : view.surface().geometry().apertureHeight() - 0.12D;
                Vec3d relative = view.surface().placement().point(view.surface().aperture().point(u, v)).subtract(eye);
                Vector4f clip = matrix.transform(new Vector4f((float) relative.x(), (float) relative.y(), (float) relative.z(), 1.0F));
                if (clip.w <= 0.001F) {
                    polygon.clear();
                    break;
                }
                polygon.add(new Pixel((clip.x / clip.w + 1.0D) * WIDTH * 0.5D, (1.0D - clip.y / clip.w) * HEIGHT * 0.5D));
            }
            if (polygon.size() == 4) {
                output.add(new Opening(view.key().toString(), List.copyOf(polygon)));
            }
        }
        return List.copyOf(output);
    }

    private static double intersection(PortalSurface surface, Vec3d eye, Vec3d direction) {
        double denominator = direction.dot(surface.servedNormal());
        if (denominator >= -0.00001D) {
            return Double.POSITIVE_INFINITY;
        }
        double distance = -surface.signedDistance(eye) / denominator;
        if (distance <= 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3d hit = eye.add(direction.multiply(distance));
        if (!surface.contains(hit)) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3d local = surface.placement().inverse().point(hit);
        int normal = surface.geometry().facingDirection().axisIndex();
        for (int axis = 0; axis < 3; axis++) {
            if (axis != normal && (local.component(axis) < surface.geometry().apertureArea().min(axis) + 0.12D
                || local.component(axis) > surface.geometry().apertureArea().max(axis) - 0.12D)) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return distance;
    }

    private static Hit wall(String world, Vec3d eye, Vec3d direction) {
        Hit nearest = null;
        for (Wall wall : walls.getOrDefault(world, List.of())) {
            double component = direction.component(wall.axis());
            if (Math.abs(component) < 0.000001D) {
                continue;
            }
            double face = wall.coordinate() + (component > 0.0D ? 0.0D : 1.0D);
            double entry = (face - eye.component(wall.axis())) / component;
            if (entry <= 0.0D || nearest != null && entry >= nearest.distance()) {
                continue;
            }
            Vec3d hit = eye.add(direction.multiply(entry));
            int tangent = wall.axis() == 0 ? 2 : 0;
            int column = (int) Math.floor(hit.component(tangent));
            int row = (int) Math.floor(hit.y());
            Integer color = wall.cells().get(cell(column, row));
            if (color == null) {
                continue;
            }
            double u = hit.component(tangent) - column;
            double v = hit.y() - row;
            boolean interior = u > 0.20D && u < 0.80D && v > 0.20D && v < 0.80D;
            nearest = new Hit(entry, interior ? color : -1);
        }
        return nearest == null || nearest.color() < 0 ? null : nearest;
    }

    private static Map<String, List<Wall>> walls(List<Marker> scene) {
        Map<String, List<Marker>> worlds = new HashMap<>();
        for (Marker marker : scene) {
            worlds.computeIfAbsent(marker.world(), ignored -> new ArrayList<>()).add(marker);
        }
        Map<String, List<Wall>> output = new HashMap<>();
        for (Map.Entry<String, List<Marker>> world : worlds.entrySet()) {
            Map<WallPlane, Map<Long, Integer>> planes = new HashMap<>();
            for (Marker marker : world.getValue()) {
                int axis = marker.normalAxis();
                int coordinate = axis == 0 ? marker.x() : marker.z();
                int column = axis == 0 ? marker.z() : marker.x();
                planes.computeIfAbsent(new WallPlane(axis, coordinate), ignored -> new HashMap<>())
                    .put(cell(column, marker.y()), marker.color());
            }
            List<Wall> boundaries = new ArrayList<>();
            for (Map.Entry<WallPlane, Map<Long, Integer>> plane : planes.entrySet()) {
                boundaries.add(new Wall(plane.getKey().axis(), plane.getKey().coordinate(), Map.copyOf(plane.getValue())));
            }
            output.put(world.getKey(), List.copyOf(boundaries));
        }
        return Map.copyOf(output);
    }

    private static long cell(int column, int row) {
        return ((long) column << 32) | (row & 0xffffffffL);
    }

    private static boolean apertureMargin(PortalSurface surface, Vec3d eye, Vec3d direction) {
        double denominator = direction.dot(surface.servedNormal());
        if (denominator >= -0.00001D) {
            return false;
        }
        double distance = -surface.signedDistance(eye) / denominator;
        if (distance <= 0.0D) {
            return false;
        }
        Vec3d hit = eye.add(direction.multiply(distance));
        if (!surface.contains(hit)) {
            return false;
        }
        Vec3d local = surface.placement().inverse().point(hit);
        int normal = surface.geometry().facingDirection().axisIndex();
        boolean interior = true;
        for (int axis = 0; axis < 3; axis++) {
            if (axis == normal) {
                continue;
            }
            double min = surface.geometry().apertureArea().min(axis);
            double max = surface.geometry().apertureArea().max(axis);
            interior &= local.component(axis) > min + 0.12D && local.component(axis) < max - 0.12D;
        }
        return !interior;
    }

    private static boolean captured(CaptureRequest request, NativeImage image) {
        Path output = request.output();
        Capture capture = request.capture();
        try (image) {
            if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
                throw new AssertionError("Effects fixture requires actual native 1920x1080 framebuffer");
            }
            NetherCapturePngWriter.write(image, output.resolve(capture.file()));
            Oracle oracle = inspect(image, capture.probes());
            synchronized (NetherCrossingEffectsTap.class) {
                CAPTURES.add(new Capture(capture.index(), capture.swaps(), capture.afterSwap(), capture.pose(), capture.layers(),
                    capture.probes(), capture.occluded(), capture.openings(), capture.file(), oracle));
            }
            if (request.preparing()) {
                int[][] coverage = new int[2][3];
                for (Probe probe : capture.probes()) {
                    coverage[probe.expectedDepth()][probe.color()]++;
                }
                boolean covered = true;
                boolean visible = false;
                for (int[] depth : coverage) {
                    if (depth[0] + depth[1] + depth[2] == 0) {
                        continue;
                    }
                    visible = true;
                    for (int color : depth) {
                        covered &= color >= 16;
                    }
                }
                covered &= visible;
                synchronized (NetherCrossingEffectsTap.class) {
                    readyFrames = covered && oracle.mismatched() <= MAX_ERRORS ? readyFrames + 1 : 0;
                    if (readyFrames >= 3) {
                        recording = false;
                    }
                }
                return true;
            }
            if (oracle.mismatched() > MAX_ERRORS) {
                throw new AssertionError(request.label() + " frame=" + capture.index() + ": wrong depth sentinel inside opening: " + oracle);
            }
            boolean needsInjection;
            synchronized (NetherCrossingEffectsTap.class) {
                needsInjection = injection == null && oracle.checked() >= 128;
            }
            if (needsInjection) {
                Injection rejected = rejectDeeperAperture(output, image, capture);
                synchronized (NetherCrossingEffectsTap.class) {
                    injection = rejected;
                }
            }
            return true;
        } catch (Throwable error) {
            fail(error);
            return false;
        }
    }

    private static Oracle inspect(NativeImage image, List<Probe> probes) {
        int mismatched = 0;
        int firstX = -1;
        int firstY = -1;
        for (Probe probe : probes) {
            if (palette(image.getPixel(probe.x(), probe.y())) != probe.color()) {
                mismatched++;
                if (firstX < 0) {
                    firstX = probe.x();
                    firstY = probe.y();
                }
            }
        }
        return new Oracle(probes.size(), mismatched, firstX, firstY);
    }

    private static Injection rejectDeeperAperture(Path output, NativeImage image, Capture capture) throws IOException {
        List<Probe> probes = capture.probes();
        try (NativeImage corrupted = new NativeImage(WIDTH, HEIGHT, false)) {
            for (int size : new int[] {256, 192, 128, 96}) {
                for (int centerY = size; centerY < HEIGHT - size; centerY += 32) {
                    for (int centerX = size; centerX < WIDTH - size; centerX += 32) {
                        int sourceX = centerX - size;
                        int sourceY = centerY - size;
                        int targetX = centerX - size / 2;
                        int targetY = centerY - size / 2;
                        Opening opening = containingOpening(capture.openings(), sourceX, sourceY, size * 2);
                        if (opening == null) {
                            continue;
                        }
                        int mismatches = 0;
                        for (Probe probe : probes) {
                            if (probe.x() >= targetX && probe.x() < targetX + size && probe.y() >= targetY && probe.y() < targetY + size) {
                                int sourcePixel = image.getPixel(sourceX + (probe.x() - targetX) * 2, sourceY + (probe.y() - targetY) * 2);
                                if (palette(sourcePixel) != probe.color()) {
                                    mismatches++;
                                }
                            }
                        }
                        if (mismatches <= MAX_ERRORS) {
                            continue;
                        }
                        corrupted.copyFrom(image);
                        for (int y = 0; y < size; y++) {
                            for (int x = 0; x < size; x++) {
                                corrupted.setPixel(targetX + x, targetY + y, image.getPixel(sourceX + x * 2, sourceY + y * 2));
                            }
                        }
                        Oracle oracle = inspect(corrupted, probes);
                        if (oracle.mismatched() <= MAX_ERRORS) {
                            throw new AssertionError("Interior depth oracle accepted a duplicate deeper aperture crop");
                        }
                        Path crop = output.resolve("negative-control-source-crop.png");
                        try (NativeImage sourceCrop = new NativeImage(size * 2, size * 2, false)) {
                            for (int y = 0; y < size * 2; y++) {
                                for (int x = 0; x < size * 2; x++) {
                                    sourceCrop.setPixel(x, y, image.getPixel(sourceX + x, sourceY + y));
                                }
                            }
                            NetherCapturePngWriter.write(sourceCrop, crop);
                        }
                        NetherCapturePngWriter.write(corrupted, output.resolve("negative-control-deeper-aperture.png"));
                        return new Injection(capture.index(), sourceX, sourceY, size * 2, targetX, targetY, size,
                            opening.key(), mismatches, oracle.mismatched(), "negative-control-source-crop.png", "negative-control-deeper-aperture.png");
                    }
                }
            }
        }
        return null;
    }

    private static Opening containingOpening(List<Opening> openings, int x, int y, int size) {
        for (Opening opening : openings) {
            if (opening.contains(x, y) && opening.contains(x + size - 1, y)
                && opening.contains(x + size - 1, y + size - 1) && opening.contains(x, y + size - 1)) {
                return opening;
            }
        }
        return null;
    }

    private static int palette(int argb) {
        int red = argb >> 16 & 255;
        int green = argb >> 8 & 255;
        int blue = argb & 255;
        if (red > 45 && red >= green * 2 + 20 && red >= blue * 2 + 20) {
            return 0;
        }
        if (blue > 24 && blue > red * 1.4D + 2 && blue > green * 1.3D + 2) {
            return 1;
        }
        return green > 35 && green > red * 1.2D + 2 && green > blue * 1.5D + 10 ? 2 : -1;
    }

    private static void fail(Throwable error) {
        boolean first;
        synchronized (NetherCrossingEffectsTap.class) {
            first = failure == null;
            if (first) {
                failure = error;
            }
        }
        if (first) {
            error.printStackTrace();
        }
    }

    private static void writeEvidence() {
        Evidence evidence;
        Path output;
        synchronized (NetherCrossingEffectsTap.class) {
            if (issued != completed) {
                throw new AssertionError(label + ": camera-effects evidence requires drained readbacks");
            }
            output = directory;
            evidence = new Evidence(label, markers, occluders, preparing, readyFrames, issued, completed, renderedFrames, swaps, leadIn,
                "Direct and interior first-layer patterned-wall samples use actual final effect projection and recorded physical obsidian unit-cuboid/aperture intersections; 0.20-block wall and 0.12-block aperture margins; at most 12 mismatches per frame. Readiness retains every spatial-warmup image and requires three consecutive images within the same mismatch limit with all three colors sampled for each geometrically visible depth path; it is separate from uncaptured cold performance measurement. Actual 2:1 reduced crop negative control is synthetic corruption of a real capture. Does not certify unsampled pixels, all possible duplicates, sound, or shader color fidelity; capture timings are not performance evidence.",
                injection, failure == null ? null : failure.toString(), worker == null ? null : worker.evidence(),
                List.copyOf(CROSSINGS), List.copyOf(CAPTURES));
        }
        try {
            Files.writeString(output.resolve("provenance.json"), new GsonBuilder().setPrettyPrinting().create().toJson(evidence) + "\n");
        } catch (IOException error) {
            throw new AssertionError("Cannot persist Nether camera-effects provenance", error);
        }
    }

    private static List<Double> point(Vec3 point) {
        return List.of(point.x, point.y, point.z);
    }

    private static Vec3d vector(Vec3 point) {
        return new Vec3d(point.x, point.y, point.z);
    }

    private static Vec3d vector(List<Double> point) {
        return new Vec3d(point.get(0), point.get(1), point.get(2));
    }

    private static List<Float> matrix(Matrix4f matrix) {
        float[] elements = matrix.get(new float[16]);
        List<Float> snapshot = new ArrayList<>(16);
        for (float element : elements) {
            snapshot.add(element);
        }
        return List.copyOf(snapshot);
    }

    private static Matrix4f projection(List<Float> snapshot) {
        float[] elements = new float[16];
        for (int index = 0; index < elements.length; index++) {
            elements[index] = snapshot.get(index);
        }
        return new Matrix4f().set(elements);
    }

    private static final class CaptureWorker implements Runnable {
        private final Map<Integer, FrameTask> pending = new TreeMap<>();
        private final Thread thread = new Thread(this, "wormholes-nether-effects-capture");
        private int requested;
        private int received;
        private int processed;
        private int processing = -1;
        private int maximumOutstanding;
        private int maximumBuffered;
        private int overflows;
        private int rejectedReadbacks;
        private int failedReadbacks;
        private int failedProcessing;
        private boolean closing;
        private boolean terminated;

        void start() {
            thread.start();
        }

        synchronized boolean reserve(int index) {
            if (requested - processed >= MAX_PENDING_CAPTURES) {
                overflows++;
                return false;
            }
            if (closing || terminated || index != requested) {
                return false;
            }
            requested++;
            maximumOutstanding = Math.max(maximumOutstanding, requested - processed);
            return true;
        }

        void accept(CaptureRequest request, NativeImage image) {
            boolean transferred = false;
            try {
                synchronized (this) {
                    int index = request.capture().index();
                    if (terminated || index < processed || index == processing || index >= requested || pending.containsKey(index)) {
                        rejectedReadbacks++;
                        throw new AssertionError(request.label() + ": rejected camera-effects readback frame=" + index);
                    }
                    pending.put(index, new FrameTask(request, image, null));
                    received++;
                    maximumBuffered = Math.max(maximumBuffered, pending.size());
                    transferred = true;
                    notifyAll();
                }
            } catch (Throwable error) {
                readbackFailed(request, error);
            } finally {
                if (!transferred) {
                    image.close();
                }
            }
        }

        void readbackFailed(CaptureRequest request, Throwable error) {
            synchronized (this) {
                int index = request.capture().index();
                if (!terminated && index >= processed && index != processing && index < requested && !pending.containsKey(index)) {
                    pending.put(index, new FrameTask(request, null, error));
                    failedReadbacks++;
                    notifyAll();
                }
            }
            synchronized (NetherCrossingEffectsTap.class) {
                if (worker != this) {
                    return;
                }
            }
            fail(error);
        }

        synchronized void requestClose() {
            closing = true;
            notifyAll();
        }

        synchronized boolean drained() {
            return processed == requested && (!closing || terminated);
        }

        synchronized QueueEvidence evidence() {
            return new QueueEvidence(MAX_PENDING_CAPTURES, requested, received, processed, pending.size(),
                requested - received - failedReadbacks, processing, maximumOutstanding, maximumBuffered,
                overflows, rejectedReadbacks, failedReadbacks, failedProcessing, closing,
                processed == requested, closing ? processed == requested ? "drained" : "draining" : "open");
        }

        void closeAndAwait() {
            requestClose();
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException error) {
                    interrupted = true;
                    fail(error);
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while draining camera-effects evidence");
            }
        }

        @Override
        public void run() {
            try {
                while (true) {
                    FrameTask task = next();
                    if (task == null) {
                        break;
                    }
                    try {
                        if (task.image() != null) {
                            if (!captured(task.request(), task.image())) {
                                synchronized (this) {
                                    failedProcessing++;
                                }
                            }
                        } else {
                            fail(task.error());
                        }
                    } finally {
                        synchronized (NetherCrossingEffectsTap.class) {
                            completed++;
                        }
                        synchronized (this) {
                            processed++;
                            processing = -1;
                            notifyAll();
                        }
                    }
                }
                writeEvidence();
            } catch (Throwable error) {
                fail(error);
            } finally {
                List<FrameTask> abandoned;
                synchronized (this) {
                    terminated = true;
                    abandoned = List.copyOf(pending.values());
                    pending.clear();
                    notifyAll();
                }
                for (FrameTask task : abandoned) {
                    if (task.image() != null) {
                        try {
                            task.image().close();
                        } catch (Throwable error) {
                            fail(error);
                        }
                    }
                }
            }
        }

        private FrameTask next() {
            while (true) {
                InterruptedException interrupted = null;
                synchronized (this) {
                    FrameTask task = pending.remove(processed);
                    if (task != null) {
                        processing = processed;
                        return task;
                    }
                    if (closing && processed == requested) {
                        return null;
                    }
                    try {
                        wait();
                    } catch (InterruptedException error) {
                        interrupted = error;
                    }
                }
                if (interrupted != null) {
                    fail(interrupted);
                }
            }
        }
    }

    private record CaptureRequest(Path output, String label, boolean preparing, Capture capture) {
    }

    private record FrameTask(CaptureRequest request, NativeImage image, Throwable error) {
    }

    private record QueueEvidence(int capacity, int requested, int received, int processed, int buffered, int awaitingReadbacks,
                                 int processingFrame, int maximumOutstanding, int maximumBuffered, int overflows,
                                 int rejectedReadbacks, int failedReadbacks, int failedProcessing, boolean closeRequested,
                                 boolean imagesDrained, String shutdownState) {
    }

    public record Effects(float portalIntensity, float nauseaIntensity, float spinningAngle, float worldPartialTicks, String source) {
    }

    public record CrossingAttempt(int renderFrame, int travelFrame, long gameTick, long nanos, int playerIdentity, int playerLevel,
                                  String playerWorld, int renderLevel, String renderWorld, String sourcePortal, String token,
                                  long generation, int handle, String sourceWorld, String transform, float nativePartialTick,
                                  List<Double> previousNativeEye, List<Double> nativeEye, List<Double> physicalEye, List<Double> interpolatedEye,
                                  double previousSignedDistance, double nativeSignedDistance, float yaw, float pitch, boolean forwardHeld) {
        public CrossingAttempt {
            previousNativeEye = List.copyOf(previousNativeEye);
            nativeEye = List.copyOf(nativeEye);
            physicalEye = List.copyOf(physicalEye);
            interpolatedEye = List.copyOf(interpolatedEye);
        }
    }

    private record NativeCrossing(CrossingAttempt attempt, boolean accepted, List<Double> nativeResult, int returnedPlayer,
                                  int returnedLevel, String returnedWorld, long returnedNanos) {
    }

    record Marker(String world, int x, int y, int z, int color, int normalAxis) {
    }

    record Occluder(String world, int x, int y, int z) {
    }

    private record Occlusion(Occluder box, double distance) {
    }

    private record OccludedProbe(int x, int y, int expectedDepth, Occluder box, double entryDistance) {
    }

    private record Samples(List<Probe> probes, List<OccludedProbe> occluded) {
    }

    private record Hit(double distance, int color) {
    }

    private record WallPlane(int axis, int coordinate) {
    }

    private record Wall(int axis, int coordinate, Map<Long, Integer> cells) {
    }

    private record Probe(int x, int y, int color, String viewKey, int expectedDepth, double wallDistance) {
    }

    private record Pixel(double x, double y) {
    }

    private record Opening(String key, List<Pixel> polygon) {
        boolean contains(double x, double y) {
            double sign = 0.0D;
            for (int index = 0; index < polygon.size(); index++) {
                Pixel first = polygon.get(index);
                Pixel second = polygon.get((index + 1) % polygon.size());
                double dx = second.x() - first.x();
                double dy = second.y() - first.y();
                double cross = dx * (y - first.y()) - dy * (x - first.x());
                if (Math.abs(cross) < 4.0D * Math.hypot(dx, dy)) {
                    return false;
                }
                if (sign == 0.0D) {
                    sign = Math.signum(cross);
                } else if (sign != Math.signum(cross)) {
                    return false;
                }
            }
            return true;
        }
    }

    private record Arm(String sourcePortal, String token, long generation, int handle, String sourceWorld, String geometry,
                       String transform, double physicalSignedDistance, double interpolatedSignedDistance) {
    }

    private record View(String kind, String key, String sourceWorld, int sourceLevel, String destinationWorld, int destinationLevel,
                        int selectedRecursion, String geometry, String placement, String transform, double cameraSignedDistance,
                        double physicalSignedDistance, double interpolatedSignedDistance) {
    }

    private record Layer(int renderedDepth, String kind, String key, int selectedRecursion, String sourceWorld, String destinationWorld) {
    }

    private record Pose(int renderFrame, int travelFrame, long gameTick, long nanos, float cameraPartialTick, Effects effects,
                        List<Double> camera, float cameraYaw, float cameraPitch, String renderWorld, int renderLevel, String playerWorld,
                        int playerLevel, int playerIdentity, List<Double> feet, List<Double> physicalEye, List<Double> interpolatedEye,
                        List<Double> oldFeet, float playerYaw, float playerPitch, boolean forwardHeld, boolean sprinting,
                        boolean crossingViewActive, String crossingKey, String crossingTransform, double bobFactor,
                        float extractedWalkDistance, float extractedBob, float walkPhase, float walkSpeed,
                        float xBob, float yBob, float oldXBob, float oldYBob, float livePortalIntensity, float oldPortalIntensity,
                        float liveNauseaIntensity, int hurtTime, boolean bobEnabled, double fovEffectScale, int fov,
                        boolean hiddenAndUnfocused, boolean mouseReleased, int width, int height, List<Float> finalProjection,
                        List<Float> baseProjection, List<Float> viewRotation, List<Float> finalViewProjection, List<Arm> arms, List<View> views) {
    }

    private record Oracle(int checked, int mismatched, int firstX, int firstY) {
    }

    private record Capture(int index, int swaps, int afterSwap, Pose pose, List<Layer> layers, List<Probe> probes,
                           List<OccludedProbe> occluded, List<Opening> openings, String file, Oracle oracle) {
    }

    private record Injection(int frame, int sourceX, int sourceY, int sourceSize, int targetX, int targetY, int targetSize,
                             String openingKey, int interiorMismatched, int totalMismatched, String sourceCrop, String corruptedFrame) {
    }

    private record Evidence(String label, List<Marker> markers, List<Occluder> occluders, boolean spatialWarmup, int readyFrames,
                            int issued, int completed, int renderedFrames, int swaps,
                            boolean walkingLeadIn, String oracleScope, Injection injection, String failure, QueueEvidence queue,
                            List<NativeCrossing> nativeCrossings, List<Capture> captures) {
    }
}
