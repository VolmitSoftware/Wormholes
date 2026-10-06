package art.arcane.optics.claim;

import java.util.List;
import java.util.UUID;

import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.BiomeClaimSet;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.fidelity.WeatherRelay;
import art.arcane.optics.light.ProjectorLighting;

public interface ProjectionOutput<O> {
    boolean online(O observer);

    boolean chunkSent(O observer, int chunkX, int chunkZ);

    void light(O observer, ProjectorLighting.ChunkLight light);

    int lightSectionBudget();

    void blockEntity(O observer, long cellKey, BlockEntitySample sample);

    void biomes(O observer, List<BiomeClaimSet.ChunkBiomes> chunks);

    void weather(O observer, WeatherRelay.Precipitation particle, long cellKey);

    void sound(O observer, AcousticsBridge.Playback playback);

    boolean clientAmbient(O observer);

    List<O> observersOf(UUID endpointId);
}
