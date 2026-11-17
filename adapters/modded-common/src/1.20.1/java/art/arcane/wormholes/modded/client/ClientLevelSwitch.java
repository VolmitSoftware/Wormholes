/*
 * The seamless level switch is derived from Immersive Portals' ClientTeleportationManager.changePlayerDimension
 * (https://github.com/iPortalTeam/ImmersivePortalsMod), Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes.
 */
package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.Pose;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.CrossingEntityAccess;
import art.arcane.wormholes.modded.mixin.client.CrossingPacketAccess;
import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

final class ClientLevelSwitch {
    private ClientLevelSwitch() {
    }

    static void activate(ClientLevel destination, Pose pose, ClientTravelMotion.Carry carry) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientWorldLoader.initializeIfNeeded();
        CrossingPacketAccess connection = (CrossingPacketAccess) minecraft.getConnection();
        connection.wormholes$crossingLevel(destination);
        destination.getChunkSource().updateViewRadius(connection.wormholes$crossingChunkRadius());
        connection.wormholes$crossingData(destination.getLevelData());
        if (minecraft.level != null) {
            minecraft.level.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        }
        CrossingEntityAccess access = (CrossingEntityAccess) player;
        access.wormholes$crossingLevel(destination);
        access.wormholes$crossingRestore();
        ClientTravelMotion.apply(player, pose);
        carry.restore(player);
        minecraft.level = destination;
        ClientWorldLoader.changeLevel(destination, player.getEyePosition());
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().residents().particles().swap((ParticleEngineAccess) minecraft.particleEngine, destination);
        } else {
            minecraft.particleEngine.setLevel(destination);
        }
        minecraft.getBlockEntityRenderDispatcher().setLevel(destination);
        if (destination.getChunkSource() instanceof PreparedChunkColumns columns) {
            columns.wormholes$announceColumns();
        }
        destination.addPlayer(player.getId(), player);
        minecraft.setCameraEntity(player);
    }
}
