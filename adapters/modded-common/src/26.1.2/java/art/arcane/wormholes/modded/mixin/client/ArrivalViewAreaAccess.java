package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ViewArea.class)
public interface ArrivalViewAreaAccess {
    @Invoker("getRenderSectionAt")
    SectionRenderDispatcher.RenderSection wormholes$arrivalSection(BlockPos position);

    @Mutable
    @Accessor("level")
    void wormholes$arrivalLevel(Level level);
}
