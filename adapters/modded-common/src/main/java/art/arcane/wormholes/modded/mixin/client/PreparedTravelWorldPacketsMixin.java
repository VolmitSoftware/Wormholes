package art.arcane.wormholes.modded.mixin.client;

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
public abstract class PreparedTravelWorldPacketsMixin {
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
        "handleSetChunkCacheRadius"
    })
    private void wormholes$sourceWorld(@Coerce Packet<ClientGamePacketListener> packet, Operation<Void> original) {
        WormholesClient client = WormholesClient.instance();
        ClientLevel crossingSource = client == null ? null : client.preparedTravel().residents().redirectTarget();
        if (crossingSource != null) {
            client.preparedTravel().residents().withLevel(crossingSource, () -> original.call(packet));
            return;
        }
        if (client == null || !client.preparedTravel().deferWorldPacket(packet, () -> original.call(packet))) {
            original.call(packet);
        }
    }

    @WrapMethod(method = {
        "handleBlockChangedAck", "handleUpdateAttributes", "handleProjectilePowerPacket", "handleGameEvent", "handleMountScreenOpen",
        "handleLookAt", "handleOpenSignEditor", "handleTickingState", "handleTickingStep"
    })
    private void wormholes$seamlessSourceWorld(@Coerce Packet<ClientGamePacketListener> packet, Operation<Void> original) {
        WormholesClient client = WormholesClient.instance();
        ClientLevel crossingSource = client == null ? null : client.preparedTravel().residents().redirectTarget();
        if (crossingSource == null) {
            original.call(packet);
            return;
        }
        client.preparedTravel().residents().withLevel(crossingSource, () -> original.call(packet));
    }
}
