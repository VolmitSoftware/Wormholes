/*
 * The seamless level switch is derived from Immersive Portals' ClientTeleportationManager.changePlayerDimension
 * (https://github.com/iPortalTeam/ImmersivePortalsMod), Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes.
 */
package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.Pose;
import art.arcane.wormholes.modded.client.render.PortalShaderWarmup;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedEntityAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

final class ClientLevelSwitch {
    private ClientLevelSwitch() {
    }

    static void activate(ResidentLevels residents, ClientLevel destination, Pose pose, ClientTravelMotion.Carry carry) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientWorldLoader.initializeIfNeeded();
        PreparedPacketAccess connection = (PreparedPacketAccess) minecraft.getConnection();
        connection.wormholes$level(destination);
        connection.wormholes$data(destination.getLevelData());
        if (minecraft.level != null) {
            minecraft.level.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        }
        PreparedEntityAccess access = (PreparedEntityAccess) player;
        access.wormholes$level(destination);
        access.wormholes$restore();
        ClientTravelMotion.apply(player, pose);
        carry.restore(player);
        PortalShaderWarmup.shared().hold();
        minecraft.level = destination;
        ClientWorldLoader.changeLevel(destination, player.getEyePosition());
        residents.particles().swap((ParticleEngineAccess) minecraft.particleEngine, destination);
        minecraft.gameRenderer.setLevel(destination);
        ((PreparedChunkColumns) destination.getChunkSource()).wormholes$announceColumns();
        destination.addEntity(player);
        minecraft.setCameraEntity(player);
    }
}
