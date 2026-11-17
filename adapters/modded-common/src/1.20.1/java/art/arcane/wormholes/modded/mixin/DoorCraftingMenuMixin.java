package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorItems;
import art.arcane.wormholes.modded.MinecraftDoorService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class DoorCraftingMenuMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void wormholesDoorCraft(int slot, int button, ClickType input, Player player, CallbackInfo callback) {
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (slot != 0 || !(menu instanceof CraftingMenu || menu instanceof InventoryMenu)
            || !(player instanceof ServerPlayer serverPlayer) || !MinecraftDoorItems.isDoorItem(menu.getSlot(0).getItem())) {
            return;
        }
        MinecraftDoorService service = MinecraftDoorService.forServer(serverPlayer.server);
        if (service == null || !service.canCraft(serverPlayer) || input == ClickType.QUICK_MOVE) {
            callback.cancel();
            menu.broadcastChanges();
        }
    }
}
