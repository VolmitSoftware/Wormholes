package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PortalEntityRenderTap {
    private static final ArrayDeque<Layer> LAYERS = new ArrayDeque<>();
    private static final IdentityHashMap<EntityRenderState, UUID> IDENTITIES = new IdentityHashMap<>();
    private static final List<Layer> CURRENT = new ArrayList<>();
    private static List<Sample> completed = List.of();
    private static boolean enabled;
    private static long frame;
    private static String failure;

    private PortalEntityRenderTap() {
    }

    public static void start() {
        LAYERS.clear();
        IDENTITIES.clear();
        CURRENT.clear();
        completed = List.of();
        frame = 0L;
        failure = null;
        enabled = true;
    }

    public static void stop() {
        enabled = false;
        LAYERS.clear();
        IDENTITIES.clear();
        CURRENT.clear();
        completed = List.of();
    }

    public static void beginFrame() {
        if (!enabled) {
            return;
        }
        if (!LAYERS.isEmpty()) {
            failure = "A portal entity render scope outlived its frame";
        }
        frame++;
        LAYERS.clear();
        IDENTITIES.clear();
        CURRENT.clear();
    }

    public static void endFrame() {
        if (!enabled) {
            return;
        }
        List<Sample> samples = new ArrayList<>(CURRENT.size());
        for (Layer layer : CURRENT) {
            samples.add(new Sample(frame, layer.depth, layer.path, layer.eye, layer.screen, Map.copyOf(layer.entities)));
        }
        completed = List.copyOf(samples);
        if (!LAYERS.isEmpty()) {
            failure = "A portal entity render scope did not exit";
        }
    }

    public static void enter(PortalView view, CameraRenderState camera, Matrix4f projection, int depth) {
        if (!enabled) {
            return;
        }
        List<Object> path = new ArrayList<>(LAYERS.size() + 1);
        Iterator<Layer> ancestors = LAYERS.descendingIterator();
        while (ancestors.hasNext()) {
            path.add(ancestors.next().view.key());
        }
        path.add(view.key());
        Minecraft client = Minecraft.getInstance();
        ScreenCamera screen = LAYERS.isEmpty() ? new ScreenCamera(camera.pos,
            new Matrix4f(projection).mul(camera.viewRotationMatrix), client.gameRenderer.mainRenderTarget().width,
            client.gameRenderer.mainRenderTarget().height) : LAYERS.getLast().screen;
        Vec3d eye = view.toDestination().point(new Vec3d(camera.pos.x, camera.pos.y, camera.pos.z));
        Layer layer = new Layer(new LayerSettings(view, depth, List.copyOf(path), new Vec3(eye.x(), eye.y(), eye.z()), screen));
        LAYERS.push(layer);
        CURRENT.add(layer);
    }

    public static void leave() {
        if (!enabled) {
            return;
        }
        if (LAYERS.isEmpty()) {
            failure = "A portal entity render scope exited without an entry";
            return;
        }
        LAYERS.pop();
    }

    public static void identify(Entity entity, EntityRenderState state) {
        if (enabled) {
            IDENTITIES.put(state, entity.getUUID());
        }
    }

    public static void extracted(LevelRenderState state) {
        Layer layer = LAYERS.peek();
        if (!enabled || layer == null) {
            return;
        }
        for (EntityRenderState entity : state.entityRenderStates) {
            record(layer, entity, false);
        }
    }

    public static void submitted(EntityRenderState state) {
        Layer layer = LAYERS.peek();
        if (enabled && layer != null) {
            record(layer, state, true);
        }
    }

    public static List<Sample> samples() {
        return completed;
    }

    public static String failure() {
        return failure;
    }

    private static void record(Layer layer, EntityRenderState state, boolean submitted) {
        UUID identity = IDENTITIES.get(state);
        Minecraft client = Minecraft.getInstance();
        if (identity == null && state instanceof AvatarRenderState avatar && client.player != null && avatar.id == client.player.getId()) {
            identity = client.player.getUUID();
        }
        if (identity == null) {
            return;
        }
        EntitySample previous = layer.entities.get(identity);
        double width = state.boundingBoxWidth * 0.5D;
        double height = state.boundingBoxHeight;
        layer.entities.put(identity, new EntitySample(previous != null && previous.extracted() || !submitted,
            previous != null && previous.submitted() || submitted,
            apparent(state.x, state.y + height * 0.5D, state.z), apparent(state.x, state.y + height, state.z),
            apparent(state.x, state.y, state.z), apparent(state.x - width, state.y + height * 0.5D, state.z),
            apparent(state.x + width, state.y + height * 0.5D, state.z)));
    }

    private static Vec3 apparent(double x, double y, double z) {
        Vec3d position = new Vec3d(x, y, z);
        for (Layer layer : LAYERS) {
            position = layer.view.toDestination().inverse().point(position);
        }
        return new Vec3(position.x(), position.y(), position.z());
    }

    public record Sample(long frame, int depth, List<Object> path, Vec3 eye, ScreenCamera screen, Map<UUID, EntitySample> entities) {
    }

    public record EntitySample(boolean extracted, boolean submitted, Vec3 center, Vec3 top, Vec3 bottom, Vec3 left, Vec3 right) {
    }

    public record ScreenCamera(Vec3 eye, Matrix4f viewProjection, int width, int height) {
        public int[] point(Vec3 world) {
            Vector4f clip = viewProjection.transform(new Vector4f((float) (world.x - eye.x), (float) (world.y - eye.y),
                (float) (world.z - eye.z), 1.0F));
            if (clip.w <= 0.0F) {
                return null;
            }
            return new int[] {(int) Math.floor((clip.x / clip.w + 1.0F) * 0.5F * width),
                (int) Math.floor((1.0F - clip.y / clip.w) * 0.5F * height)};
        }

        public boolean contains(Vec3 world) {
            int[] projected = point(world);
            return projected != null && projected[0] >= 0 && projected[1] >= 0 && projected[0] < width && projected[1] < height;
        }
    }

    private record LayerSettings(PortalView view, int depth, List<Object> path, Vec3 eye, ScreenCamera screen) {
    }

    private static final class Layer {
        private final PortalView view;
        private final int depth;
        private final List<Object> path;
        private final Vec3 eye;
        private final ScreenCamera screen;
        private final Map<UUID, EntitySample> entities = new HashMap<>();

        private Layer(LayerSettings settings) {
            view = settings.view();
            depth = settings.depth();
            path = settings.path();
            eye = settings.eye();
            screen = settings.screen();
        }
    }
}
