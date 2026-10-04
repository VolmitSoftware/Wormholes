package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.modded.client.ClientTravelWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class PreparedLevelMetadataMixin implements ClientTravelWorld {
    @Unique
    private ClientViewMessage.TravelWorld wormholes$travelWorld;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholesCaptureWorld(ClientPacketListener connection, ClientLevel.ClientLevelData data, ResourceKey<Level> dimension,
                                       Holder<DimensionType> type, int viewDistance, int simulationDistance, LevelExtractor extractor,
                                       boolean debug, long seed, int seaLevel, CallbackInfo callback) {
        wormholes$travelWorld = new ClientViewMessage.TravelWorld(dimension.identifier().toString(),
            type.unwrapKey().orElseThrow().identifier().toString(), seed, debug,
            ((PreparedLevelDataAccess) data).wormholes$flat(), seaLevel, type.value().minY(), type.value().height());
    }

    @Override
    public ClientViewMessage.TravelWorld wormholes$travelWorld() {
        return wormholes$travelWorld;
    }
}
