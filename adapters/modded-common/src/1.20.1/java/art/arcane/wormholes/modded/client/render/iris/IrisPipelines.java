package art.arcane.wormholes.modded.client.render.iris;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Iris 1.7 builds one pipeline per dimension on that dimension's first frame and offers no way to build it ahead
 * without replacing the selected pipeline, so arrivals never wait on Iris and take the same first-frame compile a
 * vanilla dimension change does.
 */
public final class IrisPipelines {
    private IrisPipelines() {
    }

    public static boolean arrivalReady(ClientLevel level) {
        return true;
    }
}
