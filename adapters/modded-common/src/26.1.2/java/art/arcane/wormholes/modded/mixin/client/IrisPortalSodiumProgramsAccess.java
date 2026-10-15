package art.arcane.wormholes.modded.mixin.client;

import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.EnumMap;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.SodiumPrograms", remap = false)
public interface IrisPortalSodiumProgramsAccess {
    @Accessor("shaders")
    EnumMap<SodiumPrograms.Pass, GlProgram<ChunkShaderInterface>> wormholes$shaders();
}
