package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4dc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Objects;

public final class DhClipPrograms {
    private static final int MAX_DISTANCES = 8;
    private static final ArrayDeque<Scope> PLANES = new ArrayDeque<>();
    private static Binding active;
    private static int previousDistances;

    private DhClipPrograms() {
    }

    public static Scope open(Vector4dc worldPlane, Vec3d camera) {
        Scope scope = new Scope(relativePlane(Objects.requireNonNull(worldPlane), Objects.requireNonNull(camera)));
        PLANES.push(scope);
        return scope;
    }

    public static Binding bind(int program) {
        return new Binding(GL20C.glGetUniformLocation(program, DhClipTransformation.PLANE),
            GL20C.glGetUniformLocation(program, DhClipTransformation.INVERSE),
            GL20C.glGetUniformLocation(program, DhClipTransformation.ENABLED));
    }

    public static void apply(Binding binding, Matrix4fc inverse) {
        if (binding.plane() < 0 || binding.inverse() < 0 || binding.enabled() < 0) {
            releaseActive();
            return;
        }
        if (active != binding) {
            releaseActive();
            previousDistances = 0;
            for (int distance = 0; distance < MAX_DISTANCES; distance++) {
                if (GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0 + distance)) {
                    previousDistances |= 1 << distance;
                    GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0 + distance);
                }
            }
            active = binding;
        }
        Vector4fc plane = plane();
        GL20C.glUniform1i(binding.enabled(), plane == null ? 0 : 1);
        if (plane == null) {
            GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0);
            return;
        }
        GL20C.glUniform4f(binding.plane(), plane.x(), plane.y(), plane.z(), plane.w());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            inverse.get(matrix);
            GL20C.glUniformMatrix4fv(binding.inverse(), false, matrix);
        }
        GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0);
    }

    public static void release(Binding binding) {
        if (active == binding) {
            releaseActive();
        }
    }

    public static Matrix4f inverseProjection(DhApiRenderParam parameters, Matrix4f target) {
        return matrix(parameters.dhInverseMvmProjectionMatrix, target);
    }

    static Vector4f relativePlane(Vector4dc worldPlane, Vec3d camera) {
        return new Vector4f((float) worldPlane.x(), (float) worldPlane.y(), (float) worldPlane.z(),
            (float) (worldPlane.w() + worldPlane.x() * camera.x() + worldPlane.y() * camera.y() + worldPlane.z() * camera.z()));
    }

    public static Vector4fc plane() {
        Scope scope = PLANES.peek();
        return scope == null ? null : scope.plane;
    }

    static Matrix4f matrix(DhApiMat4f matrix, Matrix4f target) {
        return target.set(matrix.m00, matrix.m10, matrix.m20, matrix.m30, matrix.m01, matrix.m11, matrix.m21, matrix.m31,
            matrix.m02, matrix.m12, matrix.m22, matrix.m32, matrix.m03, matrix.m13, matrix.m23, matrix.m33);
    }

    private static void releaseActive() {
        if (active == null) {
            return;
        }
        for (int distance = 0; distance < MAX_DISTANCES; distance++) {
            if ((previousDistances & (1 << distance)) != 0) {
                GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0 + distance);
            } else {
                GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0 + distance);
            }
        }
        active = null;
    }

    public record Binding(int plane, int inverse, int enabled) {
    }

    public static final class Scope implements AutoCloseable {
        private final Vector4fc plane;
        private final IrisClipPlanes.State clipState = IrisClipPlanes.capture();
        private boolean closed;

        private Scope(Vector4fc plane) {
            this.plane = plane;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (PLANES.peek() != this) {
                throw new IllegalStateException("Distant Horizons portal clip scopes closed out of order");
            }
            releaseActive();
            PLANES.pop();
            closed = true;
            clipState.restore();
        }
    }
}
