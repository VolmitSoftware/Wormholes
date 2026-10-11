package art.arcane.wormholes.modded.client.render.dh;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

public final class DhBlazeClipPrograms {
    public static final int UNIFORM_SIZE = 32;
    private static final int MAX_DISTANCES = 8;

    private DhBlazeClipPrograms() {
    }

    public static GpuBufferSlice upload(DhApiRenderParam parameters, CommandEncoder encoder) {
        Vector4fc relative = DhClipPrograms.plane();
        Vector4f plane = clipPlane(parameters, relative, new Vector4f());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UNIFORM_SIZE).putVec4(plane).putInt(relative == null ? 0 : 1)
                .putInt(0).putInt(0).putInt(0).get();
            return encoder.transientMemory().uploadGpu(data,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        }
    }

    static Vector4f clipPlane(DhApiRenderParam parameters, Vector4fc relative, Vector4f target) {
        if (relative == null) {
            return target.zero();
        }
        Matrix4f inverse = DhClipPrograms.inverseProjection(parameters, new Matrix4f());
        return inverse.transpose().transform(target.set(relative));
    }

    public static int enable() {
        return distances(DhClipPrograms.plane() != null);
    }

    public static int suspend() {
        return distances(false);
    }

    public static void restore(int previous) {
        for (int distance = 0; distance < MAX_DISTANCES; distance++) {
            if ((previous & (1 << distance)) != 0) {
                GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0 + distance);
            } else {
                GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0 + distance);
            }
        }
    }

    private static int distances(boolean clipping) {
        int previous = 0;
        for (int distance = 0; distance < MAX_DISTANCES; distance++) {
            if (GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0 + distance)) {
                previous |= 1 << distance;
            }
            if (distance == 0 && clipping) {
                GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0);
            } else {
                GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0 + distance);
            }
        }
        return previous;
    }
}
