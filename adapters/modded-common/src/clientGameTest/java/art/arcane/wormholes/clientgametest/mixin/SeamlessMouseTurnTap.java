package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.SeamlessTurningTap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public abstract class SeamlessMouseTurnTap {
    @WrapMethod(method = "turn")
    private void wormholesTest$nativeTurn(double yaw, double pitch, Operation<Void> original) {
        Entity entity = (Entity) (Object) this;
        float before = entity.getYRot();
        original.call(yaw, pitch);
        SeamlessTurningTap.turned(entity, before);
    }
}
