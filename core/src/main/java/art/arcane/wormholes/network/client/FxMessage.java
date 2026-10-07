package art.arcane.wormholes.network.client;

import java.util.List;
import java.util.Objects;

public sealed interface FxMessage {
    int FX = 13;
    int MAX_FX_EMITTERS = 255;
    int WORLD_FX_KEY = 0;

    record Fx(int portalKey, List<FxEmitter> emitters) implements FxMessage {
        public Fx {
            emitters = List.copyOf(emitters);
            if (emitters.size() > MAX_FX_EMITTERS) {
                throw new IllegalArgumentException("fx with " + emitters.size() + " emitters");
            }
        }
    }

    record FxEmitter(FxKind kind, String key, double x, double y, double z, float paramA, float paramB, int ticks, int flags) {
        public FxEmitter {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(key, "key");
        }
    }

    enum FxKind {
        RIM_DUST,
        SURFACE,
        SOUND,
        DOOR_ANIM,
        ANIMATION,
        BURST;

        public static FxKind byId(int id) {
            FxKind[] values = values();
            return id < 0 || id >= values.length ? null : values[id];
        }
    }
}
