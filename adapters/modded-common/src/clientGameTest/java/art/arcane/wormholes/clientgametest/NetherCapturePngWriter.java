package art.arcane.wormholes.clientgametest;

import com.mojang.blaze3d.platform.NativeImage;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

final class NetherCapturePngWriter {
    private static final int OUTPUT_BUFFER_BYTES = 64 * 1024;
    private static final byte[] IHDR = {73, 72, 68, 82};
    private static final byte[] IDAT = {73, 68, 65, 84};
    private static final byte[] IEND = {73, 69, 78, 68};
    private static final byte[] EMPTY = {};

    private NetherCapturePngWriter() {
    }

    static void write(NativeImage image, Path output) throws IOException {
        int components = switch (image.format()) {
            case RGBA -> 4;
            case RGB -> 3;
            default -> throw new IOException("Nether capture requires RGB or RGBA pixels, got " + image.format());
        };
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IOException("Nether capture requires positive image dimensions");
        }
        int stride = Math.multiplyExact(width, components);
        ByteBuffer pixels = image.getPixelBytes();
        if (pixels.remaining() != Math.multiplyExact(stride, height)) {
            throw new IOException("Nether capture pixel buffer does not match image dimensions");
        }
        byte[] row = new byte[Math.addExact(stride, 1)];
        byte[] encoded = new byte[OUTPUT_BUFFER_BYTES];
        Deflater deflater = new Deflater(Deflater.NO_COMPRESSION);
        try (DataOutputStream file = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output), OUTPUT_BUFFER_BYTES))) {
            file.writeLong(0x89504E470D0A1A0AL);
            writeHeader(file, width, height, components);
            for (int y = 0; y < height; y++) {
                pixels.get(row, 1, stride);
                deflater.setInput(row);
                while (!deflater.needsInput()) {
                    writeData(file, deflater, encoded);
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                writeData(file, deflater, encoded);
            }
            writeChunk(file, IEND, EMPTY, 0);
        } finally {
            deflater.end();
        }
    }

    private static void writeHeader(DataOutputStream file, int width, int height, int components) throws IOException {
        byte[] header = new byte[13];
        ByteBuffer values = ByteBuffer.wrap(header);
        values.putInt(width).putInt(height).put((byte) 8).put((byte) (components == 4 ? 6 : 2));
        writeChunk(file, IHDR, header, header.length);
    }

    private static void writeData(DataOutputStream file, Deflater deflater, byte[] encoded) throws IOException {
        int length = deflater.deflate(encoded);
        if (length > 0) {
            writeChunk(file, IDAT, encoded, length);
        }
    }

    private static void writeChunk(DataOutputStream file, byte[] type, byte[] bytes, int length) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(bytes, 0, length);
        file.writeInt(length);
        file.write(type);
        file.write(bytes, 0, length);
        file.writeInt((int) crc.getValue());
    }
}
