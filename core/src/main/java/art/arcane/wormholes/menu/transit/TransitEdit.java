package art.arcane.wormholes.menu.transit;

import java.util.Objects;

import art.arcane.optics.crossing.ScaleRule;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.TransitionProfile;

public sealed interface TransitEdit permits TransitEdit.Gravity, TransitEdit.Momentum, TransitEdit.Orientation, TransitEdit.Membrane,
    TransitEdit.Bounce, TransitEdit.Profile, TransitEdit.Scale {
    record Gravity(boolean enabled) implements TransitEdit {
    }

    record Momentum(MomentumPolicy policy) implements TransitEdit {
        public Momentum {
            Objects.requireNonNull(policy, "policy");
        }
    }

    record Orientation(OrientationPolicy policy) implements TransitEdit {
        public Orientation {
            Objects.requireNonNull(policy, "policy");
        }
    }

    record Membrane(boolean enabled) implements TransitEdit {
    }

    record Bounce(boolean enabled) implements TransitEdit {
    }

    record Profile(TransitionProfile profile) implements TransitEdit {
        public Profile {
            Objects.requireNonNull(profile, "profile");
        }
    }

    record Scale(ScaleRule rule) implements TransitEdit {
        public Scale {
            Objects.requireNonNull(rule, "rule");
        }
    }
}
