package art.arcane.wormholes.modded.mixin.client;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(value = RenderSectionManager.class, remap = false)
public interface SodiumArrivalSectionsAccess {
    @Mutable
    @Accessor("world")
    void wormholes$arrivalLevel(ClientLevel level);

    @Mutable
    @Accessor("builder")
    void wormholes$arrivalBuilder(ChunkBuilder builder);

    @Mutable
    @Accessor("sectionCache")
    void wormholes$arrivalCache(ClonedChunkSectionCache cache);

    @Mutable
    @Accessor("occlusionCuller")
    void wormholes$arrivalCuller(OcclusionCuller culler);
}
