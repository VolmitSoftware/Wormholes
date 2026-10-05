package art.arcane.wormholes.modded;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPortalToolsTest {
    @BeforeClass
    public static void bootstrap() {
        MinecraftTestBase.bootstrap();
        for (Item item : List.of(Items.BLAZE_ROD, Items.STICK, Items.STONE, Items.DIAMOND, Items.EMERALD)) {
            item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        }
    }

    @Test
    public void wandIdentitySurvivesCopyAndRejectsOrdinaryRenamedItems() {
        ItemStack wand = MinecraftPortalItemsTest.english().wand();
        assertTrue(MinecraftPortalTools.isWand(wand));
        assertTrue(MinecraftPortalTools.isWand(wand.copy()));
        ItemStack ordinary = new ItemStack(Items.BLAZE_ROD);
        ordinary.set(DataComponents.CUSTOM_NAME, Component.literal("Wormholes Wand"));
        assertFalse(MinecraftPortalTools.isWand(ordinary));
        assertFalse(MinecraftPortalTools.isWand(ItemStack.EMPTY));
        ItemStack wrongItem = new ItemStack(Items.STICK);
        wrongItem.set(DataComponents.CUSTOM_DATA, wand.get(DataComponents.CUSTOM_DATA));
        assertFalse(MinecraftPortalTools.isWand(wrongItem));
    }

    @Test
    public void ordinaryItemsAndOffhandClicksLeaveVanillaInteractionUntouched() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftPortalTools tools = new MinecraftPortalTools(runtime);
        ItemStack ordinary = new ItemStack(Items.BLAZE_ROD);
        ItemStack wand = MinecraftPortalItemsTest.english().wand();
        when(player.getMainHandItem()).thenReturn(ordinary);
        assertFalse(tools.attackBlock(player, BlockPos.ZERO));
        assertFalse(tools.useBlock(player, InteractionHand.MAIN_HAND, mock(BlockHitResult.class)));
        when(player.getMainHandItem()).thenReturn(wand);
        assertFalse(tools.useBlock(player, InteractionHand.OFF_HAND, mock(BlockHitResult.class)));
        verify(runtime, never()).portals();
    }
}
