package art.arcane.wormholes.modded.client.world;

import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.modded.client.ClientLevelScene;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.network.client.ArrivalMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.DimensionType;

public final class PreparedLevels {
    private PreparedLevels() {
    }

    public static ClientLevel create(TravelMessage.TravelWorld world, EnvironmentState environment, TravelMessage.TravelCoordinate center) {
        ClientLevel level = create(world, center, ((PreparedPacketAccess) Minecraft.getInstance().getConnection()).wormholes$chunkRadius());
        level.getLevelData().setGameTime(environment.gameTime());
        level.setRainLevel(environment.sky().rain());
        level.setThunderLevel(environment.sky().thunder());
        return level;
    }

    public static ClientLevel arrival(ArrivalMessage.Prepare prepare) {
        ClientLevel level = create(prepare.world(), new TravelMessage.TravelCoordinate(prepare.centerX(), prepare.centerZ()),
            ((PreparedPacketAccess) Minecraft.getInstance().getConnection()).wormholes$chunkRadius());
        level.getLevelData().setGameTime(prepare.gameTime());
        new ClientLevelScene(level, Minecraft.getInstance()::getConnection).clock(prepare.clockTime());
        level.setRainLevel(prepare.rain());
        level.setThunderLevel(prepare.thunder());
        return level;
    }

    private static ClientLevel create(TravelMessage.TravelWorld world, TravelMessage.TravelCoordinate center, int radius) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        Holder<DimensionType> type = connection.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
            .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(world.dimensionType())));
        if (type.value().minY() != world.minY() || type.value().height() != world.height()) {
            throw new IllegalArgumentException("Resident level " + world.dimension() + " height differs from the synchronized registry");
        }
        ClientLevel.ClientLevelData source = minecraft.level.getLevelData();
        ClientLevel.ClientLevelData data = new ClientLevel.ClientLevelData(source.getDifficulty(), source.isHardcore(), world.flat());
        PreparedLevelExtractor extractor = new PreparedLevelExtractor(minecraft);
        ClientLevel level = new ClientLevel(connection, data, ResourceKey.create(Registries.DIMENSION, Identifier.parse(world.dimension())),
            type, radius, minecraft.level.getServerSimulationDistance(),
            extractor, world.debug(), world.seed(), world.seaLevel());
        level.getChunkSource().updateViewCenter(center.x(), center.z());
        return level;
    }
}
