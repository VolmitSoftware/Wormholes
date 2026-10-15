package art.arcane.wormholes.modded.mixin.client;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramBuilder", remap = false)
public interface IrisPortalProgramBuilderAccess {
    @Invoker("<init>")
    static ProgramBuilder wormholes$create(String name, int program, ImmutableSet<Integer> reservedTextureUnits) {
        throw new AssertionError();
    }

    @Accessor("program")
    int wormholes$program();
}
