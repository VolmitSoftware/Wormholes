package art.arcane.optics.stream;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ClientViewWriter {
    private byte[] buffer;
    private int size;

    public ClientViewWriter() {
        this(256);
    }

    public ClientViewWriter(int initialCapacity) {
        this.buffer = new byte[Math.max(16, initialCapacity)];
    }

    public int size() {
        return size;
    }

    public void reset() {
        size = 0;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    public byte[] rawBuffer() {
        return buffer;
    }

    public void u8(int value) {
        ensure(1);
        buffer[size++] = (byte) value;
    }

    public void u16(int value) {
        ensure(2);
        buffer[size++] = (byte) value;
        buffer[size++] = (byte) (value >>> 8);
    }

    public void i32(int value) {
        ensure(4);
        buffer[size++] = (byte) value;
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) (value >>> 16);
        buffer[size++] = (byte) (value >>> 24);
    }

    public void i64(long value) {
        ensure(8);
        for (int shift = 0; shift < 64; shift += 8) {
            buffer[size++] = (byte) (value >>> shift);
        }
    }

    public void f32(float value) {
        i32(Float.floatToRawIntBits(value));
    }

    public void f64(double value) {
        i64(Double.doubleToRawLongBits(value));
    }

    public void varint(int value) throws ClientViewProtocolException {
        if (value < 0) {
            throw new ClientViewProtocolException("varint must not be negative: " + value);
        }
        ensure(5);
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer[size++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer[size++] = (byte) remaining;
    }

    public void string(String value) throws ClientViewProtocolException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ViewStreamLimits.MAX_STRING_BYTES) {
            throw new ClientViewProtocolException("string of " + bytes.length + " bytes exceeds " + ViewStreamLimits.MAX_STRING_BYTES);
        }
        varint(bytes.length);
        bytes(bytes);
    }

    public void bytes(byte[] value) {
        bytes(value, 0, value.length);
    }

    public void bytes(byte[] value, int offset, int length) {
        ensure(length);
        System.arraycopy(value, offset, buffer, size, length);
        size += length;
    }

    public void longs(long[] values) {
        ensure(values.length * 8);
        for (long value : values) {
            for (int shift = 0; shift < 64; shift += 8) {
                buffer[size++] = (byte) (value >>> shift);
            }
        }
    }

    public static int varintSize(int value) {
        int size = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            remaining >>>= 7;
            size++;
        }
        return size;
    }

    private void ensure(int extra) {
        int required = size + extra;
        if (required <= buffer.length) {
            return;
        }
        int grown = Math.max(required, buffer.length + (buffer.length >> 1));
        buffer = Arrays.copyOf(buffer, grown);
    }
}
