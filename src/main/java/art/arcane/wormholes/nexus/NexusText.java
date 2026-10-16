package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.wormholes.localization.WormholesLocalization;

import java.util.Objects;

/** Message argument helper shared by the nexus subsystem and its dial gestures. */
final class NexusText {
    private NexusText() {
    }

    static MessageArgs args(Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Localization arguments require name-value pairs");
        }
        MessageArgument[] arguments = new MessageArgument[nameValuePairs.length / 2];
        for (int index = 0; index < nameValuePairs.length; index += 2) {
            String name = Objects.requireNonNull((String) nameValuePairs[index], "Localization argument name");
            Object value = Objects.requireNonNull(nameValuePairs[index + 1], "Localization argument value");
            arguments[index / 2] = MessageArgument.untrusted(name, value);
        }
        return WormholesLocalization.args(arguments);
    }
}
