package art.arcane.wormholes.modded.client.render.sodium;

import net.minecraft.client.renderer.DynamicGpuDataStorage;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public record SodiumClipUniforms(DynamicGpuDataStorage.DynamicGpuData base, int offset, Vector4fc plane)
    implements DynamicGpuDataStorage.DynamicGpuData {
    private static final int PLANE_BYTES = 4 * Float.BYTES;

    @Override
    public void write(ByteBuffer buffer) {
        int start = buffer.position();
        long address = MemoryUtil.memAddress(buffer);
        base.write(buffer);
        if (buffer.capacity() - start < offset + PLANE_BYTES) {
            return;
        }
        MemoryUtil.memPutFloat(address + offset, plane.x());
        MemoryUtil.memPutFloat(address + offset + Float.BYTES, plane.y());
        MemoryUtil.memPutFloat(address + offset + 2L * Float.BYTES, plane.z());
        MemoryUtil.memPutFloat(address + offset + 3L * Float.BYTES, plane.w());
    }
}
