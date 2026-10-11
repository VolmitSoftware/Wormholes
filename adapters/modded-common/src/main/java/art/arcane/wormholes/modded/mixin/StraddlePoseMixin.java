package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.StraddleCollision;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
public abstract class StraddlePoseMixin {
    @Redirect(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean wormholesStraddleFits(Level level, Entity player, AABB box) {
        StraddleTracker.Straddle straddle = StraddleTracker.straddle(player);
        return straddle == null ? level.noCollision(player, box) : StraddleCollision.fits(player, box, straddle);
    }
}
