package art.arcane.wormholes.modded.client.render;

import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.util.Objects;
import java.util.Set;

public final class PortalClipScope implements AutoCloseable {
    private static PortalClipScope current;
    private static final Bindings OPEN_GL = new Bindings() {
        @Override
        public int maximum() {
            return GL11C.glGetInteger(GL30C.GL_MAX_CLIP_DISTANCES);
        }

        @Override
        public boolean enabled(int distance) {
            return GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0 + distance);
        }

        @Override
        public void enabled(int distance, boolean value) {
            if (value) {
                GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0 + distance);
            } else {
                GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0 + distance);
            }
        }
    };

    private final PortalClipScope previous;
    private final Vector4f plane;
    private final Bindings bindings;
    private final boolean[] original;
    private boolean closed;

    private PortalClipScope(Vector4fc plane, Bindings bindings) {
        this.plane = new Vector4f(Objects.requireNonNull(plane));
        this.bindings = Objects.requireNonNull(bindings);
        original = new boolean[bindings.maximum()];
        for (int distance = 0; distance < original.length; distance++) {
            original[distance] = bindings.enabled(distance);
        }
        select(-1, Set.of());
        previous = current;
        current = this;
    }

    public static PortalClipScope open(Vector4fc plane) {
        return new PortalClipScope(plane, OPEN_GL);
    }

    static PortalClipScope open(Vector4fc plane, Bindings bindings) {
        return new PortalClipScope(plane, bindings);
    }

    public static PortalClipScope current() {
        return current;
    }

    public Vector4fc plane() {
        return plane;
    }

    public void select(int selected, Set<Integer> existingDistances) {
        if (selected < -1 || selected >= original.length) {
            throw new IllegalArgumentException("Destination clip distance exceeds device limits");
        }
        for (int distance = 0; distance < original.length; distance++) {
            bindings.enabled(distance, distance == selected || original[distance] && existingDistances.contains(distance));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (current != this) {
            throw new IllegalStateException("Destination clip scope order");
        }
        closed = true;
        RuntimeException failure = null;
        try {
            for (int distance = 0; distance < original.length; distance++) {
                try {
                    bindings.enabled(distance, original[distance]);
                } catch (RuntimeException cleanup) {
                    if (failure == null) {
                        failure = cleanup;
                    } else {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
        } finally {
            current = previous;
        }
        if (failure != null) {
            throw failure;
        }
    }

    interface Bindings {
        int maximum();

        boolean enabled(int distance);

        void enabled(int distance, boolean value);
    }
}
