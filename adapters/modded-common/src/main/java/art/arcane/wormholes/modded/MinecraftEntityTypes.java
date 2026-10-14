package art.arcane.wormholes.modded;

import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

public final class MinecraftEntityTypes {
    public static final EntityType<ArmorStand> ARMOR_STAND = EntityTypes.ARMOR_STAND;
    public static final EntityType<Display.BlockDisplay> BLOCK_DISPLAY = EntityTypes.BLOCK_DISPLAY;
    public static final EntityType<Chicken> CHICKEN = EntityTypes.CHICKEN;
    public static final EntityType<Cow> COW = EntityTypes.COW;
    public static final EntityType<EnderDragon> ENDER_DRAGON = EntityTypes.ENDER_DRAGON;
    public static final EntityType<GlowItemFrame> GLOW_ITEM_FRAME = EntityTypes.GLOW_ITEM_FRAME;
    public static final EntityType<ItemEntity> ITEM = EntityTypes.ITEM;
    public static final EntityType<Display.ItemDisplay> ITEM_DISPLAY = EntityTypes.ITEM_DISPLAY;
    public static final EntityType<ItemFrame> ITEM_FRAME = EntityTypes.ITEM_FRAME;
    public static final EntityType<Mannequin> MANNEQUIN = EntityTypes.MANNEQUIN;
    public static final EntityType<Painting> PAINTING = EntityTypes.PAINTING;
    public static final EntityType<Pig> PIG = EntityTypes.PIG;
    public static final EntityType<Player> PLAYER = EntityTypes.PLAYER;
    public static final EntityType<Sheep> SHEEP = EntityTypes.SHEEP;
    public static final EntityType<Display.TextDisplay> TEXT_DISPLAY = EntityTypes.TEXT_DISPLAY;
    public static final EntityType<WitherBoss> WITHER = EntityTypes.WITHER;

    private MinecraftEntityTypes() {
    }
}
