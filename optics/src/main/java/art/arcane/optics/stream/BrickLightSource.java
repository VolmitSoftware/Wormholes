package art.arcane.optics.stream;

@FunctionalInterface
public interface BrickLightSource {
    BrickLightSource NONE = (sectionX, sectionY, sectionZ, blockNibbles, skyNibbles) -> false;

    boolean fill(int sectionX, int sectionY, int sectionZ, byte[] blockNibbles, byte[] skyNibbles);

    static int nibble(byte[] nibbles, int cellIndex) {
        int packed = nibbles[cellIndex >>> 1] & 0xFF;
        return (cellIndex & 1) == 0 ? packed & 0x0F : packed >>> 4;
    }

    static void setNibble(byte[] nibbles, int cellIndex, int value) {
        int slot = cellIndex >>> 1;
        int packed = nibbles[slot] & 0xFF;
        if ((cellIndex & 1) == 0) {
            packed = (packed & 0xF0) | (value & 0x0F);
        } else {
            packed = (packed & 0x0F) | ((value & 0x0F) << 4);
        }
        nibbles[slot] = (byte) packed;
    }
}
