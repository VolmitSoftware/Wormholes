package art.arcane.wormholes.modded.mixin.client;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(value = ChunkBuilder.class, remap = false)
public interface SodiumArrivalBuilderAccess {
    @Accessor("localContext")
    ChunkBuildContext wormholes$context();
}
