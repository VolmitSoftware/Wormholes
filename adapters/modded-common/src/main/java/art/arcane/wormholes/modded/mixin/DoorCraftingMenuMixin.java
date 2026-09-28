package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorItems;
import art.arcane.wormholes.modded.MinecraftDoorService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class DoorCraftingMenuMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void wormholesDoorCraft(int slot, int button, ContainerInput input, Player player, CallbackInfo callback) {
        if (slot != 0 || !((Object) this instanceof AbstractCraftingMenu menu)
            || !(player instanceof ServerPlayer serverPlayer) || !MinecraftDoorItems.isDoorItem(menu.getResultSlot().getItem())) {
            return;
        }
        MinecraftDoorService service = MinecraftDoorService.forServer(serverPlayer.level().getServer());
        if (service == null || !service.canCraft(serverPlayer) || input == ContainerInput.QUICK_MOVE) {
            callback.cancel();
            menu.broadcastChanges();
        }
    }
}
