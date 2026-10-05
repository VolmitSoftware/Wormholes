package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientPreparedTravel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

@Mixin(LayerLightSectionStorage.class)
public abstract class PreparedLightSectionStorageMixin {
    @Shadow @Final protected LightChunkGetter chunkSource;
    @Shadow @Final protected Long2ObjectMap<DataLayer> queuedSections;
    @Shadow @Final private LongSet toRemove;

    @Shadow protected abstract DataLayer getDataLayer(long section, boolean updating);
    @Shadow protected abstract boolean storingLightForSection(long section);

    @Inject(method = "queueSectionData", at = @At("HEAD"))
    private void wormholes$nativeLightDelta(long section, DataLayer incoming, CallbackInfo callback) {
        if (!ClientPreparedTravel.applyingNativeLight(chunkSource, section)) {
            return;
        }
        DataLayer queued = queuedSections.get(section);
        DataLayer current = queued == null ? getDataLayer(section, true) : queued;
        if (toRemove.contains(section) || !storingLightForSection(section) || !wormholes$sameLight(current, incoming)) {
            ClientPreparedTravel.nativeLightSectionChanged(chunkSource, section);
        }
    }

    @Unique
    private static boolean wormholes$sameLight(DataLayer current, DataLayer incoming) {
        if (current == null || incoming == null) {
            return false;
        }
        if (current.isDefinitelyHomogenous()) {
            return wormholes$filledLight(incoming, current.get(0, 0, 0));
        }
        if (incoming.isDefinitelyHomogenous()) {
            return wormholes$filledLight(current, incoming.get(0, 0, 0));
        }
        return Arrays.equals(current.getData(), incoming.getData());
    }

    @Unique
    private static boolean wormholes$filledLight(DataLayer layer, int value) {
        if (layer.isDefinitelyHomogenous()) {
            return layer.get(0, 0, 0) == value;
        }
        byte filled = (byte) (value | value << 4);
        for (byte sample : layer.getData()) {
            if (sample != filled) {
                return false;
            }
        }
        return true;
    }
}
