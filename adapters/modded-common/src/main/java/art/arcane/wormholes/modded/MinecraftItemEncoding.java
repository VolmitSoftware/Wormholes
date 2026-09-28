package art.arcane.wormholes.modded;

import net.minecraft.core.HolderLookup;
import net.minecraft.SharedConstants;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public final class MinecraftItemEncoding {
    private MinecraftItemEncoding() {
    }

    public static String encode(ItemStack item, HolderLookup.Provider registries) {
        if (item.isEmpty()) {
            throw new IllegalArgumentException("Travel cost item must not be empty");
        }
        CompoundTag tag = (CompoundTag) ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), item).getOrThrow();
        NbtUtils.addCurrentDataVersion(tag);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(tag, bytes);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not preserve the exact travel cost item", error);
        }
    }

    public static ItemStack decode(String encoded, HolderLookup.Provider registries) {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(encoded))) {
            CompoundTag tag = NbtIo.readCompressed(bytes, NbtAccounter.create(16L * 1024 * 1024));
            tag = (CompoundTag) DataFixers.getDataFixer().update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, tag),
                NbtUtils.getDataVersion(tag, 0), SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
            ItemStack item = ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow();
            if (item.isEmpty()) {
                throw new IllegalArgumentException("Travel cost item must not be empty");
            }
            return item;
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Could not read the stored travel cost item", error);
        }
    }
}
