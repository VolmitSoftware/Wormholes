package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.StraddleCollision;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Player.class)
public abstract class StraddlePoseMixin {
    @ModifyArg(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"), index = 1)
    private AABB wormholesStraddleFits(AABB box) {
        StraddleTracker.Straddle straddle = StraddleTracker.straddle((Player) (Object) this);
        return straddle == null ? box : StraddleCollision.front(box, straddle);
    }
}
