package art.arcane.wormholes.modded.client.render;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class PortalIrisBufferHistory implements AutoCloseable {
    private byte[] initial;
    private long allocatedBytes;
    private boolean closed;

    public PortalIrisBufferHistory(ByteBuffer content) {
        if (content != null) {
            ByteBuffer source = content.duplicate();
            initial = new byte[source.remaining()];
            source.get(initial);
        }
    }

    public void allocated(long bytes) {
        if (closed || bytes < 0) {
            throw new IllegalStateException("Shader buffer allocation");
        }
        allocatedBytes = bytes;
    }

    public void reset(Writer writer) {
        if (closed) {
            throw new IllegalStateException("Shader buffer history closed");
        }
        Objects.requireNonNull(writer);
        if (allocatedBytes == 0) {
            return;
        }
        writer.clear(allocatedBytes);
        if (initial != null && initial.length > 0) {
            writer.restore(initial, (int) Math.min(allocatedBytes, initial.length));
        }
    }

    @Override
    public void close() {
        initial = null;
        allocatedBytes = 0;
        closed = true;
    }

    public interface Writer {
        void clear(long bytes);

        void restore(byte[] bytes, int length);
    }
}
