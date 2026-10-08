package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(FogRenderer.class)
public interface ClientWorldFogAccess {
    @Accessor("FOG_ENVIRONMENTS")
    static List<FogEnvironment> wormholes$environments() {
        throw new AssertionError("mixin accessor");
    }
}
