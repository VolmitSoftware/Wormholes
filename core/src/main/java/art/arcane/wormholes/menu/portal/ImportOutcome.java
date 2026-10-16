package art.arcane.wormholes.menu.portal;

import java.util.Objects;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;

public sealed interface ImportOutcome permits ImportOutcome.Imported, ImportOutcome.Rejected {
    static ImportOutcome imported() {
        return new Imported();
    }

    static ImportOutcome rejected(TextKey reason, MessageArgs arguments) {
        return new Rejected(reason, arguments);
    }

    record Imported() implements ImportOutcome {
    }

    record Rejected(TextKey reason, MessageArgs arguments) implements ImportOutcome {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(arguments, "arguments");
        }
    }
}
