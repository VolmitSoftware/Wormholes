package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.LivingEntityUseState;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public abstract class LivingEntityUseAccessor implements LivingEntityUseState {
    @Shadow
    @Final
    private static EntityDataAccessor<Byte> DATA_LIVING_ENTITY_FLAGS;

    @Override
    public EntityDataAccessor<Byte> wormholesLivingFlags() {
        return DATA_LIVING_ENTITY_FLAGS;
    }

    @Accessor("useItem")
    public abstract void wormholesUseItem(ItemStack item);

    @Accessor("useItemRemaining")
    public abstract void wormholesUseItemRemaining(int ticks);
}
