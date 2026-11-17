package art.arcane.wormholes.modded;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.entity.EntityVisualMetadata;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftPacketBlobs {
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS,
        EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private final RegistryAccess registries;

    public MinecraftPacketBlobs(RegistryAccess registries) {
        this.registries = registries;
    }

    public List<SynchedEntityData.DataValue<?>> readMetadata(byte[] data) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        byte[] raw = EntityVisualMetadata.decode(data).data();
        if (raw.length == 0) {
            return List.of();
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
            int id;
            while ((id = buffer.readUnsignedByte()) != 255) {
                values.add(SynchedEntityData.DataValue.read(buffer, id));
            }
            return values;
        } finally {
            buffer.release();
        }
    }

    public byte[] writeMetadata(List<SynchedEntityData.DataValue<?>> values) {
        return writeMetadata(values, new EntityVisualMetadata(GravityFrame.WORLD, 1.0D, new byte[0]));
    }

    public byte[] writeMetadata(List<SynchedEntityData.DataValue<?>> values, EntityVisualMetadata visual) {
        if (values.isEmpty()) {
            return visual.withData(new byte[0]).encode();
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (SynchedEntityData.DataValue<?> value : values) {
                value.write(buffer);
            }
            buffer.writeByte(255);
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.readBytes(encoded);
            return visual.withData(encoded).encode();
        } finally {
            buffer.release();
        }
    }

    public List<Equipment> readEquipment(byte[] data) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            int count = buffer.readByte();
            List<Equipment> equipment = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int slot = buffer.readByte();
                ItemStack item = buffer.readItem();
                if (slot >= 0 && slot < EQUIPMENT_SLOTS.length) {
                    equipment.add(new Equipment(EQUIPMENT_SLOTS[slot], item));
                }
            }
            return equipment;
        } finally {
            buffer.release();
        }
    }

    public byte[] writeEquipment(List<Equipment> equipment) {
        if (equipment.isEmpty()) {
            return new byte[0];
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeByte(equipment.size());
            for (Equipment piece : equipment) {
                buffer.writeByte(slotId(piece.slot()));
                buffer.writeItem(piece.item());
            }
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.readBytes(encoded);
            return encoded;
        } finally {
            buffer.release();
        }
    }

    private static int slotId(EquipmentSlot slot) {
        for (int i = 0; i < EQUIPMENT_SLOTS.length; i++) {
            if (EQUIPMENT_SLOTS[i] == slot) {
                return i;
            }
        }
        throw new IllegalArgumentException("Equipment slot has no Wormholes wire identity: " + slot);
    }

    public record Equipment(EquipmentSlot slot, ItemStack item) {
    }
}
