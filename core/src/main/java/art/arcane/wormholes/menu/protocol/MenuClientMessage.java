package art.arcane.wormholes.menu.protocol;

import java.util.Objects;

import art.arcane.wormholes.menu.MenuAction;

public sealed interface MenuClientMessage permits MenuClientMessage.Hello, MenuClientMessage.Action, MenuClientMessage.Back,
    MenuClientMessage.Page, MenuClientMessage.Confirm, MenuClientMessage.Closed {
    record Hello(int version) implements MenuClientMessage {
        public Hello {
            if (version < 0 || version > 255) {
                throw new IllegalArgumentException("hello version " + version + " does not fit one byte");
            }
        }
    }

    record Action(MenuAction action) implements MenuClientMessage {
        public Action {
            Objects.requireNonNull(action, "action");
        }
    }

    record Back(long revision) implements MenuClientMessage {
    }

    record Page(long revision, int page) implements MenuClientMessage {
    }

    record Confirm(long revision, boolean accepted) implements MenuClientMessage {
    }

    record Closed(long revision) implements MenuClientMessage {
    }
}
