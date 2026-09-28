package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.blockentity.BlockEntitySanitizer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public enum MinecraftBlockEntityTags implements BlockEntitySanitizer.TagAccess<Tag> {
    INSTANCE;

    public Iterable<String> names(Tag compound) {
        return ((CompoundTag) compound).keySet();
    }

    public Tag get(Tag compound, String name) {
        return ((CompoundTag) compound).get(name);
    }

    public Tag compound() {
        return new CompoundTag();
    }

    public void put(Tag compound, String name, Tag value) {
        ((CompoundTag) compound).put(name, value);
    }

    public Iterable<Tag> list(Tag value) {
        return value instanceof ListTag list ? list : null;
    }

    public boolean contains(Tag compound, String name) {
        return compound instanceof CompoundTag tag && tag.contains(name);
    }

    public byte[] encode(Tag compound) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        DataOutputStream output = new DataOutputStream(buffer);
        NbtIo.write((CompoundTag) compound, output);
        output.flush();
        return buffer.toByteArray();
    }
}
