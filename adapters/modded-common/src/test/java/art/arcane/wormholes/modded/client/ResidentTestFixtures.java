package art.arcane.wormholes.modded.client;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.modded.seamless.RoutedPackets;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;

import org.mockito.AdditionalAnswers;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

final class ResidentTestFixtures {
    static final RegistryAccess.Frozen REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    static final TravelMessage.TravelWorld OVERWORLD = new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld",
        7, false, false, 63, -64, 384);
    static final TravelMessage.TravelWorld NETHER = new TravelMessage.TravelWorld("minecraft:the_nether", "minecraft:the_nether",
        7, false, false, 32, 0, 256);

    private ResidentTestFixtures() {
    }

    static Minecraft minecraft(ClientLevel current, ClientPacketListener connection) {
        Minecraft minecraft = mock(Minecraft.class);
        minecraft.level = current;
        when(minecraft.getConnection()).thenReturn(connection);
        when(minecraft.isSameThread()).thenReturn(true);
        return minecraft;
    }

    @SuppressWarnings("unchecked")
    static ClientPacketListener connection(ClientLevel current) {
        ClientPacketListener connection = mock(ClientPacketListener.class, withSettings().extraInterfaces(PreparedPacketAccess.class));
        PreparedPacketAccess access = (PreparedPacketAccess) connection;
        AtomicReference<ClientLevel> level = new AtomicReference<>(current);
        AtomicReference<ClientLevel.ClientLevelData> data = new AtomicReference<>(current == null ? null : current.getLevelData());
        doAnswer(call -> {
            level.set(call.getArgument(0));
            return null;
        }).when(access).wormholes$level(any());
        doAnswer(call -> {
            data.set(call.getArgument(0));
            return null;
        }).when(access).wormholes$data(any(ClientLevel.ClientLevelData.class));
        when(access.wormholes$data()).thenAnswer(call -> data.get());
        when(connection.getLevel()).thenAnswer(call -> level.get());
        when(connection.getConnection()).thenReturn(mock(Connection.class));
        when(connection.registryAccess()).thenReturn(REGISTRIES);
        when(access.wormholes$chunkRadius()).thenReturn(10);
        return connection;
    }

    static ClientLevel level(TravelMessage.TravelWorld world) {
        ClientLevel level = mock(ClientLevel.class, withSettings().extraInterfaces(ClientTravelWorld.class, PreparedLevelAccess.class));
        when(((ClientTravelWorld) level).wormholes$travelWorld()).thenReturn(world);
        when(level.dimension()).thenReturn(ResourceKey.create(Registries.DIMENSION, Identifier.parse(world.dimension())));
        when(level.getLevelData()).thenReturn(mock(ClientLevel.ClientLevelData.class));
        ClientChunkCache cache = mock(ClientChunkCache.class, withSettings().extraInterfaces(PreparedChunkColumns.class));
        when(level.getChunkSource()).thenReturn(cache);
        when(((PreparedChunkColumns) cache).wormholes$columns()).thenReturn(new AtomicReferenceArray<>(0));
        when(level.entitiesForRendering()).thenReturn(List.of());
        return level;
    }

    static void loaded(ClientLevel level, int x, int z) {
        when(level.getChunkSource().getChunk(x, z, FULL, false)).thenReturn(mock(LevelChunk.class));
    }

    static TravelMessage.RemoteLevelOpen open(int handle, TravelMessage.TravelWorld world, int x, int z) {
        return new TravelMessage.RemoteLevelOpen(handle, world, environment(world), 4, new TravelMessage.TravelCoordinate(x, z));
    }

    static EnvironmentState environment(TravelMessage.TravelWorld world) {
        EnvironmentState base = PortalEnvironmentTest.environment(OpticTransform.IDENTITY);
        EnvironmentState.World source = base.world();
        return new EnvironmentState(base.gameTime(), base.sky(), base.fog(), base.lighting(), base.clouds(), base.transform(), base.dimension(),
            new EnvironmentState.World(world.dimension(), source.clockTime(), source.biomeKey(), source.seaLevel(), source.blockLight(),
                source.skyLight(), source.logicalHeight(), source.hasCeiling(), source.ambientLight(), source.eyeMedium(), source.hasFixedTime()));
    }

    static List<TravelMessage.RoutedPacket> routed(int handle, int sequence, Packet<? super ClientGamePacketListener> packet) {
        return RoutedPackets.encode(RoutedPackets.protocol(REGISTRIES), handle, sequence, packet);
    }

    @SuppressWarnings("unchecked")
    static RegistryAccess.Frozen dimensionTypes(TravelMessage.TravelWorld... worlds) {
        RegistryAccess.Frozen registries = mock(RegistryAccess.Frozen.class, withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(REGISTRIES)));
        Registry<DimensionType> lookup = mock(Registry.class);
        doReturn(lookup).when(registries).lookupOrThrow(Registries.DIMENSION_TYPE);
        for (TravelMessage.TravelWorld world : worlds) {
            DimensionType type = mock(DimensionType.class);
            when(type.minY()).thenReturn(world.minY());
            when(type.height()).thenReturn(world.height());
            Holder.Reference<DimensionType> holder = mock(Holder.Reference.class);
            when(holder.value()).thenReturn(type);
            when(lookup.getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(world.dimensionType())))).thenReturn(holder);
        }
        return registries;
    }

    static ResourceKey<Level> key(TravelMessage.TravelWorld world) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(world.dimension()));
    }
}
