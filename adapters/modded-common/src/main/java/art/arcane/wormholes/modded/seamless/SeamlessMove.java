package art.arcane.wormholes.modded.seamless;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

public final class SeamlessMove {
    private SeamlessMove() {
    }

    public static boolean crossLevel(Steps steps) {
        Objects.requireNonNull(steps, "steps");
        steps.moving(true);
        try {
            if (!steps.allowLevelChange()) {
                return false;
            }
            if (!steps.accept()) {
                return false;
            }
            steps.departLevel();
            steps.enterLevel();
            handOver(steps);
            steps.addToLevel();
            steps.dimensionTriggers();
            steps.levelInfo();
            steps.levelChanged();
            return true;
        } finally {
            steps.moving(false);
        }
    }

    public static boolean sameLevel(Steps steps, boolean resident) {
        Objects.requireNonNull(steps, "steps");
        if (!steps.accept()) {
            return false;
        }
        if (resident) {
            steps.departView();
            steps.reposition();
            handOver(steps);
        } else {
            steps.reposition();
        }
        steps.track();
        return true;
    }

    private static void handOver(Steps steps) {
        try {
            steps.handOver();
        } catch (RuntimeException failure) {
            steps.abandonHandOver(failure);
        }
    }

    public interface Steps {
        void moving(boolean moving);

        boolean allowLevelChange();

        boolean accept();

        void departLevel();

        void departView();

        void enterLevel();

        void handOver();

        void abandonHandOver(RuntimeException failure);

        void addToLevel();

        void dimensionTriggers();

        void levelInfo();

        void levelChanged();

        void reposition();

        void track();
    }

    public interface Events {
        Events NONE = new Events() {
            @Override
            public boolean allowLevelChange(ServerPlayer player, ServerLevel destination) {
                return true;
            }

            @Override
            public void levelChanged(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
            }
        };

        boolean allowLevelChange(ServerPlayer player, ServerLevel destination);

        void levelChanged(ServerPlayer player, ServerLevel origin, ServerLevel destination);
    }
}
