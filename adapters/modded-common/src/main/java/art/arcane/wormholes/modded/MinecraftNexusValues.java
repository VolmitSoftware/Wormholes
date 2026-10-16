package art.arcane.wormholes.modded;

import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.DialState;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.portal.PortalStateCodec;

import java.util.Map;
import java.util.UUID;

public final class MinecraftNexusValues {
    private MinecraftNexusValues() {
    }

    public static UUID networkId(MinecraftPortal portal) {
        return portal.setting("nexus.networkId") instanceof String id && !id.isBlank() ? UUID.fromString(id) : null;
    }

    public static String address(MinecraftPortal portal) {
        return portal.setting("nexus.address") instanceof String address ? address : "";
    }

    public static boolean reciprocal(MinecraftPortal portal) {
        return Boolean.TRUE.equals(portal.setting("nexus.reciprocal"));
    }

    public static DestinationPolicy policy(MinecraftPortal portal) {
        return DestinationPolicy.fromMap(document(portal, "policy"));
    }

    public static FrameIo frameIo(MinecraftPortal portal) {
        return FrameIo.fromMap(document(portal, "frameIo"));
    }

    public static DialState dial(MinecraftPortal portal) {
        return DialState.fromMap(document(portal, "dial"));
    }

    private static Map<String, Object> document(MinecraftPortal portal, String key) {
        Object value = portal.setting("nexus." + key);
        return value instanceof Map<?, ?> ? PortalStateCodec.object(Map.of("value", value), "value") : null;
    }
}
