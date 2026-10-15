package art.arcane.wormholes.modded.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlProgram$Builder", remap = false)
public interface IrisPortalSodiumBuilderAccess {
    @Accessor("program")
    int wormholes$program();
}
