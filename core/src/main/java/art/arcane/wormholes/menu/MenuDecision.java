package art.arcane.wormholes.menu;

import java.util.Objects;

public sealed interface MenuDecision permits MenuDecision.Allow, MenuDecision.Deny, MenuDecision.Redirect {
    static MenuDecision allow() {
        return new Allow();
    }

    static MenuDecision deny(MenuNotice notice) {
        return new Deny(notice, false);
    }

    static MenuDecision denyAndClose(MenuNotice notice) {
        return new Deny(notice, true);
    }

    static MenuDecision redirect(MenuRoute route, MenuNotice notice) {
        return new Redirect(route, notice);
    }

    record Allow() implements MenuDecision {
    }

    record Deny(MenuNotice notice, boolean close) implements MenuDecision {
        public Deny {
            Objects.requireNonNull(notice, "notice");
        }
    }

    record Redirect(MenuRoute route, MenuNotice notice) implements MenuDecision {
        public Redirect {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(notice, "notice");
        }
    }
}
