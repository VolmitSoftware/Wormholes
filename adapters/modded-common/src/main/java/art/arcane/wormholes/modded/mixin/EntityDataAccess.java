package art.arcane.wormholes.modded.mixin;

import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SynchedEntityData.class)
public interface EntityDataAccess {
    @Accessor("itemsById")
    SynchedEntityData.DataItem<?>[] wormholesItems();
}
