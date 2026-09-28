package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftLocalization;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class LanguageEditorOpenMixin {
    @Inject(method = "initMenu", at = @At("HEAD"))
    private void wormholesLanguageEditorOpened(AbstractContainerMenu menu, CallbackInfo callback) {
        MinecraftLocalization.menuOpened((ServerPlayer) (Object) this, menu);
    }
}
