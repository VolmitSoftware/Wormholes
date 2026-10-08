package art.arcane.wormholes.modded.client.render.sodium;

import net.minecraft.client.renderer.DynamicGpuDataStorage;
import org.joml.Vector4f;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class SodiumClipUniformsTest {
    private static final DynamicGpuDataStorage.DynamicGpuData BASE = new Filled(184, 7.0F);

    @Test
    public void thePlaneIsWrittenAtItsOffsetAfterTheSodiumGlobals() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());
        new SodiumClipUniforms(BASE, 192, new Vector4f(1.0F, -2.0F, 3.0F, -4.0F)).write(buffer);
        buffer.clear();
        assertEquals(7.0F, buffer.getFloat(0), 0.0F);
        assertEquals(7.0F, buffer.getFloat(180), 0.0F);
        assertEquals(1.0F, buffer.getFloat(192), 0.0F);
        assertEquals(-2.0F, buffer.getFloat(196), 0.0F);
        assertEquals(3.0F, buffer.getFloat(200), 0.0F);
        assertEquals(-4.0F, buffer.getFloat(204), 0.0F);
    }

    @Test
    public void theOffsetIsRelativeToTheBlockStart() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(512).order(ByteOrder.nativeOrder());
        buffer.position(256);
        new SodiumClipUniforms(BASE, 192, new Vector4f(5.0F, 6.0F, 7.0F, 8.0F)).write(buffer);
        buffer.clear();
        assertEquals(5.0F, buffer.getFloat(256 + 192), 0.0F);
        assertEquals(8.0F, buffer.getFloat(256 + 204), 0.0F);
        assertEquals(0.0F, buffer.getFloat(192), 0.0F);
    }

    @Test
    public void aBlockTooSmallForThePlaneKeepsOnlyTheSodiumGlobals() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(200).order(ByteOrder.nativeOrder());
        new SodiumClipUniforms(BASE, 192, new Vector4f(1.0F, 1.0F, 1.0F, 1.0F)).write(buffer);
        buffer.clear();
        assertEquals(7.0F, buffer.getFloat(180), 0.0F);
        assertEquals(0.0F, buffer.getFloat(192), 0.0F);
    }

    @Test
    public void layersWithTheSameMatricesButDifferentPlanesAreNotDeduplicated() {
        assertEquals(new SodiumClipUniforms(BASE, 192, new Vector4f(1.0F, 0.0F, 0.0F, 0.0F)),
            new SodiumClipUniforms(BASE, 192, new Vector4f(1.0F, 0.0F, 0.0F, 0.0F)));
        assertNotEquals(new SodiumClipUniforms(BASE, 192, new Vector4f(1.0F, 0.0F, 0.0F, 0.0F)),
            new SodiumClipUniforms(BASE, 192, new Vector4f(0.0F, 0.0F, 0.0F, 0.0F)));
    }

    private record Filled(int bytes, float value) implements DynamicGpuDataStorage.DynamicGpuData {
        @Override
        public void write(ByteBuffer buffer) {
            int start = buffer.position();
            for (int offset = 0; offset < bytes; offset += Float.BYTES) {
                buffer.putFloat(value);
            }
            buffer.limit(buffer.position()).position(start);
        }
    }
}
