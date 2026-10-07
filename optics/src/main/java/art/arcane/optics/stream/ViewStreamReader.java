package art.arcane.optics.stream;

import java.nio.charset.StandardCharsets;

public final class ViewStreamReader {
    private final byte[] data;
    private final int end;
    private int position;

    public ViewStreamReader(byte[] data) {
        this(data, 0, data.length);
    }

    public ViewStreamReader(byte[] data, int offset, int length) {
        this.data = data;
        this.position = offset;
        this.end = offset + length;
    }

    public int remaining() {
        return end - position;
    }

    public int position() {
        return position;
    }

    public boolean finished() {
        return position >= end;
    }

    public void expectEnd() throws ViewStreamProtocolException {
        if (position != end) {
            throw new ViewStreamProtocolException("trailing " + (end - position) + " bytes after message");
        }
    }

    public void require(int bytes) throws ViewStreamProtocolException {
        if (bytes < 0 || bytes > end - position) {
            throw new ViewStreamProtocolException("truncated: need " + bytes + " bytes, have " + (end - position));
        }
    }

    public int u8() throws ViewStreamProtocolException {
        require(1);
        return data[position++] & 0xFF;
    }

    public int u16() throws ViewStreamProtocolException {
        require(2);
        int value = (data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8);
        position += 2;
        return value;
    }

    public int i32() throws ViewStreamProtocolException {
        require(4);
        int value = (data[position] & 0xFF)
            | ((data[position + 1] & 0xFF) << 8)
            | ((data[position + 2] & 0xFF) << 16)
            | ((data[position + 3] & 0xFF) << 24);
        position += 4;
        return value;
    }

    public long i64() throws ViewStreamProtocolException {
        require(8);
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value |= (data[position + i] & 0xFFL) << (i * 8);
        }
        position += 8;
        return value;
    }

    public float f32() throws ViewStreamProtocolException {
        return Float.intBitsToFloat(i32());
    }

    public double f64() throws ViewStreamProtocolException {
        return Double.longBitsToDouble(i64());
    }

    public int varint() throws ViewStreamProtocolException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = u8();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 28) {
                throw new ViewStreamProtocolException("varint longer than 5 bytes");
            }
        }
        if (value < 0) {
            throw new ViewStreamProtocolException("varint overflow");
        }
        return value;
    }

    public int varint(int max) throws ViewStreamProtocolException {
        int value = varint();
        if (value > max) {
            throw new ViewStreamProtocolException("varint " + value + " exceeds " + max);
        }
        return value;
    }

    public String string() throws ViewStreamProtocolException {
        int length = varint(ViewStreamLimits.MAX_STRING_BYTES);
        require(length);
        String value = new String(data, position, length, StandardCharsets.UTF_8);
        position += length;
        return value;
    }

    public byte[] bytes(int length) throws ViewStreamProtocolException {
        require(length);
        byte[] copy = new byte[length];
        System.arraycopy(data, position, copy, 0, length);
        position += length;
        return copy;
    }

    public long[] longs(int count) throws ViewStreamProtocolException {
        require(count * 8);
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            values[i] = i64();
        }
        return values;
    }

    public int checkedCount(int count, int max, int minBytesEach) throws ViewStreamProtocolException {
        if (count < 0 || count > max) {
            throw new ViewStreamProtocolException("count " + count + " exceeds " + max);
        }
        if ((long) count * minBytesEach > remaining()) {
            throw new ViewStreamProtocolException("count " + count + " does not fit in " + remaining() + " bytes");
        }
        return count;
    }
}
