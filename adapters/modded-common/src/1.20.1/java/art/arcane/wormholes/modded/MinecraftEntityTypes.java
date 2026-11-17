package art.arcane.wormholes.modded;

import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

public final class MinecraftEntityTypes {
    public static final EntityType<ArmorStand> ARMOR_STAND = EntityType.ARMOR_STAND;
    public static final EntityType<Display.BlockDisplay> BLOCK_DISPLAY = EntityType.BLOCK_DISPLAY;
    public static final EntityType<Chicken> CHICKEN = EntityType.CHICKEN;
    public static final EntityType<Cow> COW = EntityType.COW;
    public static final EntityType<EnderDragon> ENDER_DRAGON = EntityType.ENDER_DRAGON;
    public static final EntityType<GlowItemFrame> GLOW_ITEM_FRAME = EntityType.GLOW_ITEM_FRAME;
    public static final EntityType<ItemEntity> ITEM = EntityType.ITEM;
    public static final EntityType<Display.ItemDisplay> ITEM_DISPLAY = EntityType.ITEM_DISPLAY;
    public static final EntityType<ItemFrame> ITEM_FRAME = EntityType.ITEM_FRAME;
    // Mannequins do not exist on 1.20.1; armor stands stand in for client reflections.
    public static final EntityType<ArmorStand> MANNEQUIN = EntityType.ARMOR_STAND;
    public static final EntityType<Painting> PAINTING = EntityType.PAINTING;
    public static final EntityType<Pig> PIG = EntityType.PIG;
    public static final EntityType<Player> PLAYER = EntityType.PLAYER;
    public static final EntityType<Sheep> SHEEP = EntityType.SHEEP;
    public static final EntityType<Display.TextDisplay> TEXT_DISPLAY = EntityType.TEXT_DISPLAY;
    public static final EntityType<WitherBoss> WITHER = EntityType.WITHER;

    private MinecraftEntityTypes() {
    }
}
