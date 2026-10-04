package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface PreparedEntityAccess {
    @Invoker("setLevel")
    void wormholes$level(Level level);

    @Invoker("unsetRemoved")
    void wormholes$restore();
}
