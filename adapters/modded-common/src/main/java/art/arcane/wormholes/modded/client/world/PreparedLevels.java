package art.arcane.wormholes.modded.client.world;

import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.network.client.TravelMessage;
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
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        Holder<DimensionType> type = connection.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
            .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(world.dimensionType())));
        if (type.value().minY() != world.minY() || type.value().height() != world.height()) {
            throw new IllegalArgumentException("Resident level " + world.dimension() + " height differs from the synchronized registry");
        }
        ClientLevel.ClientLevelData source = minecraft.level.getLevelData();
        ClientLevel.ClientLevelData data = new ClientLevel.ClientLevelData(source.getDifficulty(), source.isHardcore(), world.flat());
        data.setGameTime(environment.gameTime());
        PreparedLevelExtractor extractor = new PreparedLevelExtractor(minecraft);
        ClientLevel level = new ClientLevel(connection, data, ResourceKey.create(Registries.DIMENSION, Identifier.parse(world.dimension())),
            type, ((PreparedPacketAccess) connection).wormholes$chunkRadius(), minecraft.level.getServerSimulationDistance(),
            extractor, world.debug(), world.seed(), world.seaLevel());
        level.getChunkSource().updateViewCenter(center.x(), center.z());
        level.setRainLevel(environment.sky().rain());
        level.setThunderLevel(environment.sky().thunder());
        return level;
    }
}
