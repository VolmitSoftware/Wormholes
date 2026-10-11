package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.modded.client.render.dh.DhClipPrograms;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = DhClipPrograms.class, remap = false)
public interface DistantHorizonsClipAccess {
    @Invoker("plane")
    static Vector4fc wormholesTest$plane() {
        throw new IllegalStateException("Distant Horizons clip accessor is unavailable");
    }
}
