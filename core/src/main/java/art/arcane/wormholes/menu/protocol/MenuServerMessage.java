package art.arcane.wormholes.menu.protocol;

import java.util.Objects;

import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuScreen;

public sealed interface MenuServerMessage permits MenuServerMessage.Accept, MenuServerMessage.Open, MenuServerMessage.Update,
    MenuServerMessage.Close, MenuServerMessage.Feedback {
    record Accept(int version, MenuServerInfo server) implements MenuServerMessage {
        public Accept {
            if (version < 1 || version > 255) {
                throw new IllegalArgumentException("accepted version " + version + " is outside 1..255");
            }
            Objects.requireNonNull(server, "server");
        }
    }

    record Open(MenuScreen screen) implements MenuServerMessage {
        public Open {
            Objects.requireNonNull(screen, "screen");
        }
    }

    record Update(MenuScreen screen) implements MenuServerMessage {
        public Update {
            Objects.requireNonNull(screen, "screen");
        }
    }

    record Close() implements MenuServerMessage {
    }

    record Feedback(MenuFeedback feedback) implements MenuServerMessage {
        public Feedback {
            Objects.requireNonNull(feedback, "feedback");
        }
    }
}
