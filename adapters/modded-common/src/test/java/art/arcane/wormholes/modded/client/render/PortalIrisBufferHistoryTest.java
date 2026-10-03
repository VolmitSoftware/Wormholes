package art.arcane.wormholes.modded.client.render;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalIrisBufferHistoryTest {
    @Test
    public void originalPackBytesAreCopiedBeforeTheirNativeStorageIsChangedOrReleased() {
        ByteBuffer source = ByteBuffer.wrap(new byte[]{9, 1, 2, 3, 8});
        source.position(1).limit(4);
        PortalIrisBufferHistory history = new PortalIrisBufferHistory(source);
        source.put(1, (byte) 7);
        history.allocated(8);
        Writer writer = new Writer();
        history.reset(writer);
        assertEquals(List.of(8L), writer.clears);
        assertArrayEquals(new byte[]{1, 2, 3}, writer.restored.getFirst());
        assertEquals(1, source.position());
        assertEquals(4, source.limit());
    }

    @Test
    public void resetsUseActualResizedCapacityAndNeverWritePastTheNewAllocation() {
        PortalIrisBufferHistory history = new PortalIrisBufferHistory(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
        Writer writer = new Writer();
        history.allocated(16);
        history.reset(writer);
        history.allocated(2);
        history.reset(writer);
        history.allocated(32);
        history.reset(writer);
        assertEquals(List.of(16L, 2L, 32L), writer.clears);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, writer.restored.get(0));
        assertArrayEquals(new byte[]{1, 2}, writer.restored.get(1));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, writer.restored.get(2));
    }

    @Test
    public void relativeOrEmptyBuffersClearWithoutCopyingAnyPackTemplate() {
        PortalIrisBufferHistory history = new PortalIrisBufferHistory(null);
        Writer writer = new Writer();
        history.reset(writer);
        assertTrue(writer.clears.isEmpty());
        history.allocated(1024);
        history.reset(writer);
        assertEquals(List.of(1024L), writer.clears);
        assertTrue(writer.restored.isEmpty());
    }

    @Test
    public void closedBuffersReleaseTemplatesAndRejectFutureLeaseResets() {
        PortalIrisBufferHistory history = new PortalIrisBufferHistory(ByteBuffer.wrap(new byte[]{1}));
        history.allocated(8);
        history.close();
        history.close();
        assertThrows(IllegalStateException.class, () -> history.reset(new Writer()));
        assertThrows(IllegalStateException.class, () -> history.allocated(8));
    }

    private static final class Writer implements PortalIrisBufferHistory.Writer {
        private final List<Long> clears = new ArrayList<>();
        private final List<byte[]> restored = new ArrayList<>();

        @Override
        public void clear(long bytes) {
            clears.add(bytes);
        }

        @Override
        public void restore(byte[] bytes, int length) {
            restored.add(Arrays.copyOf(bytes, length));
        }
    }
}
