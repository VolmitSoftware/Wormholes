package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.PairEndpoint;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;
import java.util.UUID;

public final class MinecraftDoorItems {
    private static final String PREFIX = "wormholes:door_";

    private MinecraftDoorItems() {
    }

    public static boolean isWormholeRune(ItemStack item) {
        return item.is(Items.DARK_PRISMARINE) && "WORMHOLE".equals(data(item).getString("wormholes:rune"));
    }

    public static boolean isDoorItem(ItemStack item) {
        return identity(item).isPresent() || kit(item).isPresent();
    }

    public static ItemStack pairKit(DoorForm form) {
        CompoundTag data = new CompoundTag();
        data.putString(PREFIX + "pair_kit_id", UUID.randomUUID().toString());
        data.putString(PREFIX + "pair_kit_form", form.name());
        ItemStack item = new ItemStack(Items.BUNDLE);
        item.getOrCreateTag().merge(data);
        item.setHoverName(Component.literal(form == DoorForm.DOOR
            ? "Entangled Door Pair" : "Entangled Trapdoor Pair"));
        return item;
    }

    public static ItemStack door(DoorItemIdentity identity) {
        return door(identity, material(identity));
    }

    public static ItemStack door(DoorItemIdentity identity, Item material) {
        CompoundTag data = new CompoundTag();
        data.putInt(PREFIX + "schema", 3);
        data.putString(PREFIX + "item_id", identity.itemId().toString());
        data.putString(PREFIX + "kind", identity.kind().name());
        data.putString(PREFIX + "form", identity.form().name());
        if (identity.pairId() != null) {
            data.putString(PREFIX + "pair_id", identity.pairId().toString());
            data.putString(PREFIX + "pair_endpoint", identity.pairEndpoint().name());
        }
        if (identity.spaceId() != null) {
            data.putString(PREFIX + "space_id", identity.spaceId().toString());
        }
        ItemStack item = new ItemStack(material);
        item.getOrCreateTag().merge(data);
        String form = identity.form() == DoorForm.DOOR ? "Door" : "Trapdoor";
        String name = switch (identity.kind()) {
            case PAIR -> "Wormhole " + form + ' ' + identity.pairEndpoint();
            case PERSONAL -> "Personal Dimension " + form;
            case PUBLIC -> "Public Dimension " + form;
            case RETURN -> "Return Door";
        };
        item.setHoverName(Component.literal(name));
        return item;
    }

    public static Optional<DoorItemIdentity> identity(ItemStack item) {
        CompoundTag data = data(item);
        if (data.getInt(PREFIX + "schema") != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new DoorItemIdentity(UUID.fromString(data.getString(PREFIX + "item_id")),
                DoorKind.valueOf(data.getString(PREFIX + "kind")),
                DoorForm.valueOf(data.getString(PREFIX + "form")),
                optionalId(data, "pair_id"),
                data.contains(PREFIX + "pair_endpoint") ? PairEndpoint.valueOf(data.getString(PREFIX + "pair_endpoint")) : null,
                optionalId(data, "space_id")));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static Optional<PairKit> kit(ItemStack item) {
        CompoundTag data = data(item);
        if (!data.contains(PREFIX + "pair_kit_id")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new PairKit(UUID.fromString(data.getString(PREFIX + "pair_kit_id")),
                DoorForm.valueOf(data.getString(PREFIX + "pair_kit_form"))));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static Item material(DoorItemIdentity identity) {
        boolean trapdoor = identity.form() == DoorForm.TRAPDOOR;
        return switch (identity.kind()) {
            case PAIR -> trapdoor ? Items.OAK_TRAPDOOR : Items.OAK_DOOR;
            case PERSONAL -> trapdoor ? Items.DARK_OAK_TRAPDOOR : Items.DARK_OAK_DOOR;
            case PUBLIC -> trapdoor ? Items.BIRCH_TRAPDOOR : Items.BIRCH_DOOR;
            case RETURN -> Items.CRIMSON_DOOR;
        };
    }

    private static CompoundTag data(ItemStack item) {
        CompoundTag tag = item.getTag();
        return tag == null ? new CompoundTag() : tag;
    }

    private static UUID optionalId(CompoundTag data, String key) {
        return data.contains(PREFIX + key) ? UUID.fromString(data.getString(PREFIX + key)) : null;
    }

    public record PairKit(UUID kitId, DoorForm form) {
    }
}
