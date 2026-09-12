package art.arcane.wormholes.hook;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;

import java.util.Objects;

/**
 * Result of a {@link TraversalGate}. {@link Allow} continues to the next gate; {@link Deny} rejects the
 * traversal with a player-facing reason (and a pushback when {@code bounce} is true); {@link Defer}
 * silently skips this tick without latching or bouncing, which is how a warmup keeps a traveler at
 * the aperture until it completes.
 */
public sealed interface TraversalVerdict permits TraversalVerdict.Allow, TraversalVerdict.Deny, TraversalVerdict.Defer {
    Allow ALLOW = new Allow();

    record Allow() implements TraversalVerdict {
    }

    record Deny(TextKey reason, MessageArgs args, boolean bounce) implements TraversalVerdict {
        public Deny {
            reason = Objects.requireNonNull(reason, "reason");
            args = args == null ? MessageArgs.empty() : args;
        }

        public static Deny of(TextKey reason) {
            return new Deny(reason, MessageArgs.empty(), true);
        }
    }

    record Defer(TextKey reason) implements TraversalVerdict {
    }
}
