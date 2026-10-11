package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.ViewMutationAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PalettedContainer.class)
public abstract class ViewPaletteMutationMixin<T> implements ViewMutationAccess.Palette {
    @Shadow @Final private Strategy<T> strategy;
    @Shadow protected abstract T get(int index);
    @Unique private LevelChunkSection wormholes$section;

    @Override
    public void wormholesBind(LevelChunkSection section) {
        wormholes$section = section;
    }

    @WrapMethod(method = "getAndSet(ILjava/lang/Object;)Ljava/lang/Object;")
    private T wormholesAppliedSwap(int index, T value, Operation<T> original) {
        T previous = original.call(index, value);
        if (previous != value) {
            wormholesChanged(index);
        }
        return previous;
    }

    @WrapMethod(method = "set(ILjava/lang/Object;)V")
    private void wormholesAppliedSet(int index, T value, Operation<Void> original) {
        boolean active = wormholes$section != null && ((ViewMutationAccess.Section) wormholes$section).wormholesPaletteActive();
        T previous = active ? get(index) : null;
        original.call(index, value);
        if (active && previous != get(index)) {
            wormholesChanged(index);
        }
    }

    @WrapMethod(method = "read")
    @SuppressWarnings("unchecked")
    private void wormholesAppliedRead(FriendlyByteBuf buffer, Operation<Void> original) {
        if (wormholes$section == null || !((ViewMutationAccess.Section) wormholes$section).wormholesPaletteActive()) {
            original.call(buffer);
            return;
        }
        PalettedContainer<T> before = ((PalettedContainer<T>) (Object) this).copy();
        original.call(buffer);
        int bits = strategy.entryCount() == 64 ? 2 : 4;
        int mask = (1 << bits) - 1;
        for (int index = 0; index < strategy.entryCount(); index++) {
            if (before.get(index & mask, index >> (bits * 2), (index >> bits) & mask) != get(index)) {
                ((ViewMutationAccess.Section) wormholes$section).wormholesPaletteReplaced(this);
                return;
            }
        }
    }

    @Unique
    private void wormholesChanged(int index) {
        if (wormholes$section == null || !((ViewMutationAccess.Section) wormholes$section).wormholesPaletteActive()) {
            return;
        }
        int bits = strategy.entryCount() == 64 ? 2 : 4;
        int mask = (1 << bits) - 1;
        ((ViewMutationAccess.Section) wormholes$section).wormholesPaletteMutated(this,
            index & mask, index >> (bits * 2), (index >> bits) & mask);
    }
}
