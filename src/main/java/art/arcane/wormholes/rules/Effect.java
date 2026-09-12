package art.arcane.wormholes.rules;

import java.util.Locale;
import java.util.UUID;

/**
 * Something a matched rule does to the traveler on arrival. Command effects carry the author so the
 * privilege they were saved with can be re-checked before the command runs.
 */
public sealed interface Effect {
    /** Scales the traveler's arrival velocity. */
    record Velocity(double multiplier) implements Effect {
    }

    /** Applies or clears a potion effect. */
    record Potion(String type, int ticks, int amplifier, boolean clear) implements Effect {
        public Potion {
            type = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        }
    }

    /** Runs a command as the traveler, or as console when {@code asConsole} and the config allows it. */
    record Command(String line, boolean asConsole, UUID authorId) implements Effect {
        public Command {
            line = line == null ? "" : line.trim();
        }
    }

    /** Sends a message id from the catalog, or the literal text when the id is unknown. */
    record Message(String message) implements Effect {
        public Message {
            message = message == null ? "" : message;
        }
    }

    /** Plays a sound at the arrival point. */
    record Sound(String key, float volume, float pitch) implements Effect {
        public Sound {
            key = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** Stamps an extra cooldown, optionally on a shared group rather than this portal. */
    record StampCooldown(String group, long millis) implements Effect {
        public StampCooldown {
            group = group == null ? "" : group.trim();
        }
    }
}
