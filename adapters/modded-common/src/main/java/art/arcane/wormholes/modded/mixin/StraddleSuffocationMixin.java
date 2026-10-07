package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.StraddleCollision;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class StraddleSuffocationMixin {
    @ModifyReturnValue(method = "isInWall", at = @At("RETURN"))
    private boolean wormholesStraddleInWall(boolean inWall) {
        if (!inWall) {
            return false;
        }
        Entity entity = (Entity) (Object) this;
        StraddleTracker.Straddle straddle = StraddleTracker.straddle(entity);
        return straddle == null || StraddleCollision.inWall(entity, straddle);
    }
}
