package art.arcane.wormholes.modded.mixin;

import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MapItemSavedData.class)
public interface MapDataAccess {
    @Accessor("trackingPosition")
    boolean wormholesTrackingPosition();
}
