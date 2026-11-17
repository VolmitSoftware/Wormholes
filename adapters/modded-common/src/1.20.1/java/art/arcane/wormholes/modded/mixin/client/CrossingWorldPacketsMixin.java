package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddExperienceOrbPacket;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundHorseScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Runs world packets against the crossing source level while a crossing redirects them. MixinExtras 0.4 cannot
 * coerce a {@code @WrapMethod} parameter, so each handler gets a wrapper with its exact packet type.
 */
@Mixin(ClientPacketListener.class)
public abstract class CrossingWorldPacketsMixin {
    @WrapMethod(method = "handleAddEntity")
    private void wormholes$handleAddEntity(ClientboundAddEntityPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleAddExperienceOrb")
    private void wormholes$handleAddExperienceOrb(ClientboundAddExperienceOrbPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleAddPlayer")
    private void wormholes$handleAddPlayer(ClientboundAddPlayerPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetEntityMotion")
    private void wormholes$handleSetEntityMotion(ClientboundSetEntityMotionPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetEntityData")
    private void wormholes$handleSetEntityData(ClientboundSetEntityDataPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleTeleportEntity")
    private void wormholes$handleTeleportEntity(ClientboundTeleportEntityPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleMoveEntity")
    private void wormholes$handleMoveEntity(ClientboundMoveEntityPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleRotateMob")
    private void wormholes$handleRotateMob(ClientboundRotateHeadPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleRemoveEntities")
    private void wormholes$handleRemoveEntities(ClientboundRemoveEntitiesPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleChunkBlocksUpdate")
    private void wormholes$handleChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleLevelChunkWithLight")
    private void wormholes$handleLevelChunkWithLight(ClientboundLevelChunkWithLightPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleForgetLevelChunk")
    private void wormholes$handleForgetLevelChunk(ClientboundForgetLevelChunkPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleBlockUpdate")
    private void wormholes$handleBlockUpdate(ClientboundBlockUpdatePacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleTakeItemEntity")
    private void wormholes$handleTakeItemEntity(ClientboundTakeItemEntityPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleAnimate")
    private void wormholes$handleAnimate(ClientboundAnimatePacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleHurtAnimation")
    private void wormholes$handleHurtAnimation(ClientboundHurtAnimationPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetTime")
    private void wormholes$handleSetTime(ClientboundSetTimePacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetSpawn")
    private void wormholes$handleSetSpawn(ClientboundSetDefaultSpawnPositionPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetEntityPassengersPacket")
    private void wormholes$handleSetEntityPassengersPacket(ClientboundSetPassengersPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleEntityLinkPacket")
    private void wormholes$handleEntityLinkPacket(ClientboundSetEntityLinkPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleEntityEvent")
    private void wormholes$handleEntityEvent(ClientboundEntityEventPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleDamageEvent")
    private void wormholes$handleDamageEvent(ClientboundDamageEventPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleExplosion")
    private void wormholes$handleExplosion(ClientboundExplodePacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleBlockEntityData")
    private void wormholes$handleBlockEntityData(ClientboundBlockEntityDataPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetEquipment")
    private void wormholes$handleSetEquipment(ClientboundSetEquipmentPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleBlockEvent")
    private void wormholes$handleBlockEvent(ClientboundBlockEventPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleBlockDestruction")
    private void wormholes$handleBlockDestruction(ClientboundBlockDestructionPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleLevelEvent")
    private void wormholes$handleLevelEvent(ClientboundLevelEventPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleUpdateMobEffect")
    private void wormholes$handleUpdateMobEffect(ClientboundUpdateMobEffectPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleRemoveMobEffect")
    private void wormholes$handleRemoveMobEffect(ClientboundRemoveMobEffectPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetCamera")
    private void wormholes$handleSetCamera(ClientboundSetCameraPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleInitializeBorder")
    private void wormholes$handleInitializeBorder(ClientboundInitializeBorderPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetBorderCenter")
    private void wormholes$handleSetBorderCenter(ClientboundSetBorderCenterPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetBorderLerpSize")
    private void wormholes$handleSetBorderLerpSize(ClientboundSetBorderLerpSizePacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetBorderSize")
    private void wormholes$handleSetBorderSize(ClientboundSetBorderSizePacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetBorderWarningDistance")
    private void wormholes$handleSetBorderWarningDistance(ClientboundSetBorderWarningDistancePacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetBorderWarningDelay")
    private void wormholes$handleSetBorderWarningDelay(ClientboundSetBorderWarningDelayPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSoundEvent")
    private void wormholes$handleSoundEvent(ClientboundSoundPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSoundEntityEvent")
    private void wormholes$handleSoundEntityEvent(ClientboundSoundEntityPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleParticleEvent")
    private void wormholes$handleParticleEvent(ClientboundLevelParticlesPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleLightUpdatePacket")
    private void wormholes$handleLightUpdatePacket(ClientboundLightUpdatePacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleChunksBiomes")
    private void wormholes$handleChunksBiomes(ClientboundChunksBiomesPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetChunkCacheCenter")
    private void wormholes$handleSetChunkCacheCenter(ClientboundSetChunkCacheCenterPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleSetChunkCacheRadius")
    private void wormholes$handleSetChunkCacheRadius(ClientboundSetChunkCacheRadiusPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleBlockChangedAck")
    private void wormholes$handleBlockChangedAck(ClientboundBlockChangedAckPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleUpdateAttributes")
    private void wormholes$handleUpdateAttributes(ClientboundUpdateAttributesPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleGameEvent")
    private void wormholes$handleGameEvent(ClientboundGameEventPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleHorseScreenOpen")
    private void wormholes$handleHorseScreenOpen(ClientboundHorseScreenOpenPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleLookAt")
    private void wormholes$handleLookAt(ClientboundPlayerLookAtPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @WrapMethod(method = "handleOpenSignEditor")
    private void wormholes$handleOpenSignEditor(ClientboundOpenSignEditorPacket packet, Operation<Void> original) {
        wormholes$sourceWorld(() -> original.call(packet));
    }

    @Unique
    private static void wormholes$sourceWorld(Runnable handler) {
        WormholesClient client = WormholesClient.instance();
        ResidentLevels residents = client == null ? null : client.seamlessTravel().residents();
        ClientLevel crossingSource = residents == null ? null : residents.redirectTarget();
        if (crossingSource == null) {
            handler.run();
            return;
        }
        residents.withLevel(crossingSource, handler);
    }
}
