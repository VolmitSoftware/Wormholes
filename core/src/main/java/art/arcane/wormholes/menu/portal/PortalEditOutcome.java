package art.arcane.wormholes.menu.portal;

import java.util.Objects;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;

public sealed interface PortalEditOutcome permits PortalEditOutcome.Applied, PortalEditOutcome.Refused {
    static PortalEditOutcome applied() {
        return new Applied();
    }

    static PortalEditOutcome refused(TextKey reason, MessageArgs arguments) {
        return new Refused(reason, arguments);
    }

    record Applied() implements PortalEditOutcome {
    }

    record Refused(TextKey reason, MessageArgs arguments) implements PortalEditOutcome {
        public Refused {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(arguments, "arguments");
        }
    }
}
