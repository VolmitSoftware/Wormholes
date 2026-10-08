package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Vector3d;

public interface SodiumPortalRenderer {
    RenderSectionManager wormholes$sections();

    UniformBufferManager wormholes$uniforms();

    FogParameters wormholes$fog();

    void wormholes$fog(FogParameters fog);

    Vector3d wormholes$lastCamera();

    void wormholes$lastCamera(Vector3d camera);

    int wormholes$renderDistance();

    void wormholes$processChunkEvents();

    boolean wormholes$claimBuild();

    long wormholes$sweptSection();

    long wormholes$sweptAt();

    void wormholes$swept(long section, long nanos);

    boolean wormholes$discoveryArmed();

    void wormholes$discoveryArmed(boolean armed);
}
