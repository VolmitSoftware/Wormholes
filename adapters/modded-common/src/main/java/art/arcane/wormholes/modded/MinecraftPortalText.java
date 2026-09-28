package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;

import java.util.Objects;
import java.util.UUID;

public final class MinecraftPortalText {
    private static final String BLACK = "§0";
    private static final String GRAY = "§7";
    private static final String YELLOW = "§e";
    private static final String GOLD = "§6";
    private static final String BOLD = "§l";

    private MinecraftPortalText() {
    }

    public static String router(WormholesModRuntime runtime, MinecraftPortal portal, boolean dark) {
        return router(runtime, portal, dark, null);
    }

    public static String router(WormholesModRuntime runtime, MinecraftPortal portal, boolean dark, MinecraftPortal source) {
        StringBuilder router = new StringBuilder();
        if (source != null) {
            router.append(dark ? GRAY : YELLOW).append(BOLD).append(source.getName());
            router.append(GRAY).append(" -> ");
        }
        router.append(dark ? BLACK : GOLD).append(BOLD).append(portal.getName());
        UUID destinationId = portal.getDestinationId();
        MinecraftPortal destination = destinationId == null ? null : runtime.portals().get(destinationId);
        if (destination != null) {
            router.append(GRAY).append(" -> ");
            router.append(GRAY).append(BOLD).append(destination.getName());
        }
        return router.toString();
    }

    public static MessageArgs arguments(Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Localization arguments require name-value pairs");
        }
        MessageArgs.Builder arguments = MessageArgs.builder();
        for (int index = 0; index < nameValuePairs.length; index += 2) {
            String name = Objects.requireNonNull((String) nameValuePairs[index], "Localization argument name");
            Object value = Objects.requireNonNull(nameValuePairs[index + 1], "Localization argument value");
            arguments.add(MessageArgument.untrusted(name, value));
        }
        return arguments.build();
    }
}
