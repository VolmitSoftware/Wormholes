package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.MessageKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Lane-owned message groups merged into {@link WormholesMessages#catalog()}. Lanes never edit this
 * class or {@link WormholesMessages}; they add keys to their own group class.
 */
public final class WormholesMessageGroups {
    private WormholesMessageGroups() {
    }

    public static List<MessageKey> keys() {
        List<MessageKey> keys = new ArrayList<>();
        keys.addAll(RulesMessages.keys());
        keys.addAll(AccessMessages.keys());
        keys.addAll(NexusMessages.keys());
        keys.addAll(AtlasMessages.keys());
        keys.addAll(DoorViewMessages.keys());
        keys.addAll(PocketsMessages.keys());
        keys.addAll(MeshMessages.keys());
        keys.addAll(TransitMessages.keys());
        keys.addAll(FidelityMessages.keys());
        keys.addAll(OpsMessages.keys());
        return keys;
    }
}
