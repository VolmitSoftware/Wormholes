package art.arcane.wormholes.render.blockentity;

import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;

import java.io.IOException;
import java.util.ArrayList;

public enum BukkitBlockEntityTags implements BlockEntitySanitizer.TagAccess<NBT> {
    INSTANCE;

    public Iterable<String> names(NBT compound) {
        return new ArrayList<String>(((NBTCompound) compound).getTagNames());
    }

    public NBT get(NBT compound, String name) {
        return ((NBTCompound) compound).getTagOrNull(name);
    }

    public NBT compound() {
        return new NBTCompound();
    }

    public void put(NBT compound, String name, NBT value) {
        ((NBTCompound) compound).setTag(name, value);
    }

    public Iterable<? extends NBT> list(NBT value) {
        return value instanceof NBTList<?> list ? list.getTags() : null;
    }

    public boolean contains(NBT compound, String name) {
        return compound instanceof NBTCompound tag && tag.contains(name);
    }

    public byte[] encode(NBT compound) throws IOException {
        return BlockEntityNbt.encode((NBTCompound) compound);
    }
}
