package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Future;

@Mixin(SectionOcclusionGraph.class)
public interface ClientWorldOcclusionAccess {
    @Accessor("fullUpdateTask")
    Future<?> wormholes$fullUpdateTask();
}
