package art.arcane.wormholes.modded.mixin.client;

import com.seibel.distanthorizons.core.render.RenderParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public interface DhPortalClientApiAccess {
    @Accessor("RENDER_PARAMS")
    static RenderParams wormholes$renderParams() {
        throw new IllegalStateException("Distant Horizons render parameters accessor is unavailable");
    }
}
