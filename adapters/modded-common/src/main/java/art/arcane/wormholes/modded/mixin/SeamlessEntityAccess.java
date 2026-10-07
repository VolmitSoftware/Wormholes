package art.arcane.wormholes.modded.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface SeamlessEntityAccess {
    @Invoker("unsetRemoved")
    void wormholesUnsetRemoved();
}
