package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityRenderDispatcher.class)
public interface ClientWorldEntityDispatcherAccess {
    @Accessor("level")
    Level wormholes$level();
}
