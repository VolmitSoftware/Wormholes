package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.Pose;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
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
        if (minecraft.level != null) {
            minecraft.level.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        }
        PreparedEntityAccess access = (PreparedEntityAccess) player;
        access.wormholes$level(destination);
        access.wormholes$restore();
        ClientTravelMotion.apply(player, pose);
        carry.restore(player);
        ((PreparedLevelAccess) destination).wormholes$extractor(minecraft.levelExtractor);
        PreparedPacketAccess connection = (PreparedPacketAccess) minecraft.getConnection();
        connection.wormholes$level(destination);
        connection.wormholes$data(destination.getLevelData());
        residents.activate(() -> attachLevel(destination, false));
        destination.addEntity(player);
        minecraft.setCameraEntity(player);
    }

    static void attachLevel(ClientLevel destination, boolean authoritative) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level != destination) {
            ((PreparedLevelAccess) minecraft.level).wormholes$extractor(new PreparedLevelExtractor(minecraft));
        }
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
