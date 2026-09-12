package art.arcane.wormholes.render.blockentity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Compact, sanitized block-entity snapshot: the block-entity type key and its client-facing NBT as a
 * named root compound in Java-edition binary form. Immutable; equality is by content.
 */
public record BlockEntitySample(String typeKey, byte[] nbt) {
    public static final int MAX_NBT_BYTES = 2048;

    public BlockEntitySample {
        Objects.requireNonNull(typeKey, "typeKey");
        Objects.requireNonNull(nbt, "nbt");
    }

    public int bytes() {
        return 32 + typeKey.length() + nbt.length;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeUTF(typeKey);
        out.writeShort(nbt.length);
        out.write(nbt);
    }

    public static BlockEntitySample read(DataInputStream in) throws IOException {
        String typeKey = in.readUTF();
        int length = in.readUnsignedShort();
        if (length > MAX_NBT_BYTES) {
            throw new IOException("Block entity NBT too large: " + length);
        }
        byte[] nbt = new byte[length];
        in.readFully(nbt);
        return new BlockEntitySample(typeKey, nbt);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BlockEntitySample sample)) {
            return false;
        }
        return typeKey.equals(sample.typeKey) && Arrays.equals(nbt, sample.nbt);
    }

    @Override
    public int hashCode() {
        return typeKey.hashCode() * 31 + Arrays.hashCode(nbt);
    }

    @Override
    public String toString() {
        return typeKey + "[" + nbt.length + "b]";
    }
}
