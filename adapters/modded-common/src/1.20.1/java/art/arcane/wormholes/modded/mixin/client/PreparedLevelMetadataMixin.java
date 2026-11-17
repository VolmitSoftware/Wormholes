package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientTravelWorld;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(ClientLevel.class)
public abstract class PreparedLevelMetadataMixin implements ClientTravelWorld {
    @Unique
    private TravelMessage.TravelWorld wormholes$travelWorld;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholesCaptureWorld(ClientPacketListener connection, ClientLevel.ClientLevelData data, ResourceKey<Level> dimension,
                                       Holder<DimensionType> type, int viewDistance, int simulationDistance,
                                       Supplier<ProfilerFiller> profiler, LevelRenderer renderer, boolean debug, long seed,
                                       CallbackInfo callback) {
        wormholes$travelWorld = new TravelMessage.TravelWorld(dimension.location().toString(),
            type.unwrapKey().orElseThrow().location().toString(), seed, debug,
            ((PreparedLevelDataAccess) data).wormholes$flat(), ((Level) (Object) this).getSeaLevel(), type.value().minY(),
            type.value().height());
    }

    @Override
    public TravelMessage.TravelWorld wormholes$travelWorld() {
        return wormholes$travelWorld;
    }
}
