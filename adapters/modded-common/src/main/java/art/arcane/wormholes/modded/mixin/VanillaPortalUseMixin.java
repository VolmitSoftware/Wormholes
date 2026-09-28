package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftPortalConstruction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class VanillaPortalUseMixin {
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void wormholes$formedPortal(ServerPlayer player, Level level, ItemStack item, InteractionHand hand,
                                       BlockHitResult hit, CallbackInfoReturnable<InteractionResult> callback) {
        MinecraftPortalConstruction construction = MinecraftPortalConstruction.forServer(player.level().getServer());
        if (construction != null && callback.getReturnValue().consumesAction()
            && (level.getBlockState(hit.getBlockPos()).is(Blocks.OBSIDIAN)
                || level.getBlockState(hit.getBlockPos()).is(Blocks.END_PORTAL_FRAME))) {
            construction.vanilla().afterUse(player, hit.getBlockPos().immutable());
        }
    }
}
