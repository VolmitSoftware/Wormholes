package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.ResidentRoutesHolder;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerLevel.class)
public abstract class ResidentRoutesLevelMixin implements ResidentRoutesHolder {
    @Unique
    private boolean wormholes$residentRoutes;

    @Override
    public boolean wormholesResidentRoutes() {
        return wormholes$residentRoutes;
    }

    @Override
    public void wormholesResidentRoutes(boolean present) {
        wormholes$residentRoutes = present;
    }
}
