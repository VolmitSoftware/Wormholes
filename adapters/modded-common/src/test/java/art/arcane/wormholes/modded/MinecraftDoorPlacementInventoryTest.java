package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class MinecraftDoorPlacementInventoryTest {
    @BeforeClass
    public static void bootstrap() {
        MinecraftDoorStateTest.bootstrap();
        Items.DARK_OAK_TRAPDOOR.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
    }

    @Test
    public void successfulBoundDoorPlacementConsumesExactlyOneInEachHandAndMode() {
        for (DoorForm form : DoorForm.values()) {
            for (InteractionHand hand : InteractionHand.values()) {
                for (boolean creative : new boolean[] {false, true}) {
                    for (int count : new int[] {1, 4}) {
                        ItemStack held = MinecraftDoorItems.door(DoorItemIdentity.newPersonal(form));
                        held.setCount(count);
                        ItemStack other = new ItemStack(Items.OAK_DOOR, 6);
                        ServerPlayer player = player(held, other, hand, creative);
                        held.consume(1, player);
                        assertTrue(MinecraftDoorService.completeDoorPlacement(player, hand, true));
                        assertEquals(count - 1, held.getCount());
                        assertEquals(6, other.getCount());
                        verify(player.getInventory()).setChanged();
                        verify(player.containerMenu).broadcastFullState();
                    }
                }
            }
        }
    }

    @Test
    public void rejectedPlacementPreservesBothHandsAndSendsNoInventoryChange() {
        for (InteractionHand hand : InteractionHand.values()) {
            for (boolean creative : new boolean[] {false, true}) {
                ItemStack held = MinecraftDoorItems.door(DoorItemIdentity.newPersonal());
                held.setCount(4);
                ItemStack other = MinecraftDoorItems.door(DoorItemIdentity.newPublic());
                other.setCount(6);
                ServerPlayer player = player(held, other, hand, creative);
                assertFalse(MinecraftDoorService.completeDoorPlacement(player, hand, false));
                assertEquals(4, held.getCount());
                assertEquals(6, other.getCount());
                verifyNoInteractions(player.getInventory(), player.containerMenu);
            }
        }
    }

    @Test
    public void ordinaryCreativeDoorsRemainReusable() {
        ItemStack held = new ItemStack(Items.OAK_DOOR, 4);
        ServerPlayer player = player(held, ItemStack.EMPTY, InteractionHand.MAIN_HAND, true);
        held.consume(1, player);
        assertTrue(MinecraftDoorService.completeDoorPlacement(player, InteractionHand.MAIN_HAND, true));
        assertEquals(4, held.getCount());
    }

    private static ServerPlayer player(ItemStack held, ItemStack other, InteractionHand hand, boolean creative) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.hasInfiniteMaterials()).thenReturn(creative);
        when(player.getItemInHand(hand)).thenReturn(held);
        when(player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND))
            .thenReturn(other);
        when(player.getInventory()).thenReturn(mock(Inventory.class));
        player.containerMenu = mock(InventoryMenu.class);
        return player;
    }
}
