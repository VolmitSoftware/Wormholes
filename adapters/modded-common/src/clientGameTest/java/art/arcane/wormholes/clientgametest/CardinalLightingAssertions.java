package art.arcane.wormholes.clientgametest;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import net.minecraft.world.level.CardinalLighting;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CardinalLightingAssertions {
    private CardinalLightingAssertions() {
    }

    public static void verify() {
        GpuBufferSlice previous = RenderSystem.getShaderLights();
        GpuBufferSlice normal = null;
        GpuBufferSlice nether = null;
        GpuBufferSlice ui = null;
        try {
            try (Lighting lighting = new Lighting();
                 GpuBuffer foreign = RenderSystem.getDevice().createBuffer(() -> "Cardinal lighting acceptance", GpuBuffer.USAGE_UNIFORM, Lighting.UBO_SIZE)) {
                lighting.updateLevel(CardinalLighting.Type.DEFAULT);
                lighting.setupFor(Lighting.Entry.LEVEL);
                normal = RenderSystem.getShaderLights();
                requireContents(normal, CardinalLighting.Type.DEFAULT);
                lighting.updateLevel(CardinalLighting.Type.NETHER);
                nether = RenderSystem.getShaderLights();
                require(normal.buffer() != nether.buffer(), "Destination lighting overwrote the enclosing lighting buffer");
                requireContents(nether, CardinalLighting.Type.NETHER);
                requireContents(normal, CardinalLighting.Type.DEFAULT);
                for (int index = 0; index < 64; index++) {
                    lighting.updateLevel(CardinalLighting.Type.DEFAULT);
                    requireSame(RenderSystem.getShaderLights(), normal, "Main lighting did not restore its retained slice");
                    lighting.updateLevel(CardinalLighting.Type.NETHER);
                    requireSame(RenderSystem.getShaderLights(), nether, "Destination lighting did not reuse its retained slice");
                }
                requireContents(normal, CardinalLighting.Type.DEFAULT);
                requireContents(nether, CardinalLighting.Type.NETHER);
                require(((CardinalLightingWrites) lighting).wormholesTest$levelWrites() == 0, "World lighting still rewrites the native LEVEL buffer");
                lighting.setupFor(Lighting.Entry.ITEMS_3D);
                ui = RenderSystem.getShaderLights();
                lighting.updateLevel(CardinalLighting.Type.DEFAULT);
                requireSame(RenderSystem.getShaderLights(), ui, "World lighting changed an active inventory lighting slice");
                lighting.setupFor(Lighting.Entry.LEVEL);
                requireSame(RenderSystem.getShaderLights(), normal, "World lighting did not select the latest world after inventory rendering");
                GpuBufferSlice foreignSlice = foreign.slice();
                RenderSystem.setShaderLights(foreignSlice);
                lighting.updateLevel(CardinalLighting.Type.NETHER);
                requireSame(RenderSystem.getShaderLights(), foreignSlice, "World lighting overwrote an external shader lighting slice");
                lighting.setupFor(Lighting.Entry.LEVEL);
                requireSame(RenderSystem.getShaderLights(), nether, "Native world setup did not select destination lighting");
                try (Lighting separate = new Lighting()) {
                    separate.updateLevel(CardinalLighting.Type.DEFAULT);
                    separate.setupFor(Lighting.Entry.LEVEL);
                    require(RenderSystem.getShaderLights().buffer() != normal.buffer(), "Separate lighting instances share mutable native state");
                    lighting.setupFor(Lighting.Entry.LEVEL);
                }
                require(!normal.buffer().isClosed() && !nether.buffer().isClosed(), "Closing another lighting instance released retained world lighting");
                requireContents(normal, CardinalLighting.Type.DEFAULT);
                requireContents(nether, CardinalLighting.Type.NETHER);
            }
            require(normal.buffer().isClosed() && nether.buffer().isClosed() && ui.buffer().isClosed(), "Closing lighting leaked native or retained world buffers");
        } finally {
            RenderSystem.setShaderLights(previous);
        }
    }

    private static void requireContents(GpuBufferSlice slice, CardinalLighting.Type type) {
        require(slice.buffer() instanceof GlBuffer, "Cardinal lighting GPU readback requires the actual OpenGL buffer");
        GlBuffer buffer = (GlBuffer) slice.buffer();
        int previous = GL15C.glGetInteger(GL31C.GL_COPY_READ_BUFFER);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bytes = stack.malloc(Lighting.UBO_SIZE).order(ByteOrder.nativeOrder());
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buffer.handle());
            GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER, slice.offset(), bytes);
            Vector3f first = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
            Vector3f second = new Vector3f(-0.2F, type == CardinalLighting.Type.NETHER ? -1.0F : 1.0F, 0.7F).normalize();
            requireVector(bytes, 0, first);
            requireVector(bytes, 16, second);
        } finally {
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, previous);
        }
    }

    private static void requireVector(ByteBuffer bytes, int offset, Vector3f expected) {
        require(Math.abs(bytes.getFloat(offset) - expected.x) < 0.000001F
            && Math.abs(bytes.getFloat(offset + 4) - expected.y) < 0.000001F
            && Math.abs(bytes.getFloat(offset + 8) - expected.z) < 0.000001F,
            "GPU cardinal lighting differs from native light directions");
    }

    private static void requireSame(GpuBufferSlice actual, GpuBufferSlice expected, String message) {
        require(actual.buffer() == expected.buffer() && actual.offset() == expected.offset() && actual.length() == expected.length(), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
