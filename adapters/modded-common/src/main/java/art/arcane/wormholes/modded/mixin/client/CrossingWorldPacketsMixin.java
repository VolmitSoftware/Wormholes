package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(ClientPacketListener.class)
public abstract class CrossingWorldPacketsMixin {
    @WrapMethod(method = {
        "handleAddEntity", "handleSetEntityMotion", "handleSetEntityData", "handleEntityPositionSync",
        "handleTeleportEntity", "handleMoveEntity", "handleMinecartAlongTrack", "handleRotateMob", "handleRemoveEntities",
        "handleChunkBlocksUpdate", "handleLevelChunkWithLight", "handleForgetLevelChunk", "handleBlockUpdate",
        "handleTakeItemEntity", "handleAnimate", "handleHurtAnimation", "handleSwingAnimation", "handleSetTime",
        "handleSetSpawn", "handleSetEntityPassengersPacket", "handleAddTransientBlockPacket", "handleEntityLinkPacket",
        "handleEntityEvent", "handleDamageEvent", "handleExplosion", "handleBlockEntityData", "handleSetEquipment",
        "handleBlockEvent", "handleBlockDestruction", "handleLevelEvent", "handleUpdateMobEffect", "handleRemoveMobEffect",
        "handleSetCamera", "handleInitializeBorder", "handleSetBorderCenter", "handleSetBorderLerpSize", "handleSetBorderSize",
        "handleSetBorderWarningDistance", "handleSetBorderWarningDelay", "handleSoundEvent", "handleSoundEntityEvent",
        "handleParticleEvent", "handleLightUpdatePacket", "handleChunksBiomes", "handleSetChunkCacheCenter",
        "handleSetChunkCacheRadius", "handleBlockChangedAck", "handleUpdateAttributes", "handleProjectilePowerPacket",
        "handleGameEvent", "handleMountScreenOpen", "handleLookAt", "handleOpenSignEditor", "handleTickingState", "handleTickingStep"
    })
    private void wormholes$sourceWorld(@Coerce Packet<ClientGamePacketListener> packet, Operation<Void> original) {
        WormholesClient client = WormholesClient.instance();
        ResidentLevels residents = client == null ? null : client.seamlessTravel().residents();
        ClientLevel crossingSource = residents == null ? null : residents.redirectTarget();
        if (crossingSource == null) {
            original.call(packet);
            return;
        }
        residents.withLevel(crossingSource, () -> original.call(packet));
    }
}
