/*
 * The seamless level switch is derived from Immersive Portals' ClientTeleportationManager.changePlayerDimension
 * (https://github.com/iPortalTeam/ImmersivePortalsMod), Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes.
 */
package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.Pose;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.ClientWorldLoader;
import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
import art.arcane.wormholes.modded.client.render.PortalShaderWarmup;
import art.arcane.wormholes.modded.client.render.PreparedLevelExtractor;
import art.arcane.wormholes.modded.mixin.client.ParticleEngineAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedEntityAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

final class ClientLevelSwitch {
    private static final boolean IRIS = ClientLevelSwitch.class.getClassLoader().getResource("net/irisshaders/iris/Iris.class") != null;

    private ClientLevelSwitch() {
    }

    static void attachPlayer(ClientLevel destination, Pose pose, ClientTravelMotion.Carry carry) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level != null) {
            minecraft.level.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        }
        PreparedEntityAccess access = (PreparedEntityAccess) player;
        access.wormholes$level(destination);
        access.wormholes$restore();
        ClientTravelMotion.apply(player, pose);
        carry.restore(player);
        ((PreparedLevelAccess) destination).wormholes$extractor(minecraft.levelExtractor);
        attachLevel(destination, false);
        destination.addEntity(player);
        minecraft.setCameraEntity(player);
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

    private static void detachExtractor(Minecraft minecraft, ClientLevel destination) {
        if (minecraft.level != null && minecraft.level != destination) {
            ((PreparedLevelAccess) minecraft.level).wormholes$extractor(new PreparedLevelExtractor(minecraft));
        }
    }

    static void attachLevel(ClientLevel destination, boolean authoritative) {
        Minecraft minecraft = Minecraft.getInstance();
        detachExtractor(minecraft, destination);
        try (ClientSodiumTerrain.Handoff ignored = authoritative
            ? ClientSodiumTerrain.authoritativeHandoff(destination) : ClientSodiumTerrain.handoff(destination)) {
            if (IRIS) {
                Shaded.attach(minecraft, destination, authoritative);
            } else {
                minecraft.setLevel(destination);
            }
        }
        ((PreparedChunkColumns) destination.getChunkSource()).wormholes$announceColumns();
    }

    private static final class Shaded {
        private static void attach(Minecraft minecraft, ClientLevel level, boolean authoritative) {
            try (PortalIrisMainPipelines.Handoff ignored = authoritative
                ? PortalIrisMainPipelines.authoritativeHandoff(level) : PortalIrisMainPipelines.handoff(level)) {
                minecraft.setLevel(level);
            }
        }
    }
}
