package art.arcane.wormholes.modded.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface SeamlessEntityAccess {
    @Accessor("id")
    int wormholesEntityId();

    @Invoker("unsetRemoved")
    void wormholesUnsetRemoved();
}
