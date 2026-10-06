package art.arcane.optics.claim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.BiomeClaimSet;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.fidelity.WeatherRelay;
import art.arcane.optics.light.ProjectorLighting;
import art.arcane.optics.math.CellKeys;

public final class RecordingProjectionOutput<O> implements ProjectionOutput<O> {
    public final List<ProjectorLighting.ChunkLight> lights = new ArrayList<ProjectorLighting.ChunkLight>();
    public final List<Emitted<O, BlockEntitySend>> blockEntities = new ArrayList<Emitted<O, BlockEntitySend>>();
    public final List<Emitted<O, BiomeClaimSet.ChunkBiomes>> biomes = new ArrayList<Emitted<O, BiomeClaimSet.ChunkBiomes>>();
    public final List<Emitted<O, WeatherSend>> weather = new ArrayList<Emitted<O, WeatherSend>>();
    public final List<Emitted<O, AcousticsBridge.Playback>> sounds = new ArrayList<Emitted<O, AcousticsBridge.Playback>>();
    public final Map<UUID, List<O>> observers = new HashMap<UUID, List<O>>();
    public final Set<O> offline = new HashSet<O>();
    public final Set<O> ambientClients = new HashSet<O>();
    public final Set<Long> unsentChunks = new HashSet<Long>();
    public List<O> everyone = List.of();
    public Consumer<ProjectorLighting.ChunkLight> lightListener = light -> { };
    public int lightSectionBudget = Integer.MAX_VALUE;

    @Override
    public boolean online(O observer) {
        return !offline.contains(observer);
    }

    @Override
    public boolean chunkSent(O observer, int chunkX, int chunkZ) {
        return !unsentChunks.contains(Long.valueOf(CellKeys.chunkKey(chunkX, chunkZ)));
    }

    @Override
    public void light(O observer, ProjectorLighting.ChunkLight light) {
        lights.add(light);
        lightListener.accept(light);
    }

    @Override
    public int lightSectionBudget() {
        return lightSectionBudget;
    }

    @Override
    public void blockEntity(O observer, long cellKey, BlockEntitySample sample) {
        blockEntities.add(new Emitted<O, BlockEntitySend>(observer, new BlockEntitySend(cellKey, sample)));
    }

    @Override
    public void biomes(O observer, List<BiomeClaimSet.ChunkBiomes> chunks) {
        for (BiomeClaimSet.ChunkBiomes chunk : chunks) {
            biomes.add(new Emitted<O, BiomeClaimSet.ChunkBiomes>(observer, chunk));
        }
    }

    @Override
    public void weather(O observer, WeatherRelay.Precipitation particle, long cellKey) {
        weather.add(new Emitted<O, WeatherSend>(observer, new WeatherSend(particle, cellKey)));
    }

    @Override
    public void sound(O observer, AcousticsBridge.Playback playback) {
        sounds.add(new Emitted<O, AcousticsBridge.Playback>(observer, playback));
    }

    @Override
    public boolean clientAmbient(O observer) {
        return ambientClients.contains(observer);
    }

    @Override
    public List<O> observersOf(UUID endpointId) {
        return observers.getOrDefault(endpointId, everyone);
    }

    public record Emitted<O, T>(O observer, T value) {
    }

    public record BlockEntitySend(long cellKey, BlockEntitySample sample) {
    }

    public record WeatherSend(WeatherRelay.Precipitation particle, long cellKey) {
    }
}
