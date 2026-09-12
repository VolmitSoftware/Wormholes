package art.arcane.wormholes.render.blockentity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTLimiter;
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer;

/** Java-edition binary NBT with a named root, the layout {@code NbtIo.write} produces and the client reads. */
public final class BlockEntityNbt {
    private static final DefaultNBTSerializer SERIALIZER = new DefaultNBTSerializer();

    private BlockEntityNbt() {
    }

    public static byte[] encode(NBTCompound compound) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(buffer);
        SERIALIZER.serializeTag(out, compound, true);
        out.flush();
        return buffer.toByteArray();
    }

    public static NBTCompound decode(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        NBT tag = SERIALIZER.deserializeTag(NBTLimiter.noop(), in, true);
        if (!(tag instanceof NBTCompound compound)) {
            throw new IOException("Block entity root tag is not a compound");
        }
        return compound;
    }
}
