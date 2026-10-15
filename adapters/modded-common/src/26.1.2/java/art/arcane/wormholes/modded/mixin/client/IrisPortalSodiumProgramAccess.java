package art.arcane.wormholes.modded.mixin.client;

import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Function;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlProgram", remap = false)
public interface IrisPortalSodiumProgramAccess {
    @Invoker("<init>")
    static GlProgram<?> wormholes$create(int program, Function<ShaderBindingContext, ?> factory) {
        throw new AssertionError();
    }
}
