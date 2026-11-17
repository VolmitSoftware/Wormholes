package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientViewDebugEntry;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class ClientViewDebugOverlayMixin {
    @ModifyReturnValue(method = "getGameInformation()Ljava/util/List;", at = @At("RETURN"))
    private List<String> wormholesDebugLines(List<String> lines) {
        List<String> extra = ClientViewDebugEntry.shownLines();
        if (!extra.isEmpty()) {
            lines.add("");
            lines.addAll(extra);
        }
        return lines;
    }
}
