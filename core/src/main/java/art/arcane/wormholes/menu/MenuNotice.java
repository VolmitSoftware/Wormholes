package art.arcane.wormholes.menu;

import java.util.Objects;

public record MenuNotice(MenuFeedback feedback, NoticeChannel chestChannel) {
    public MenuNotice {
        Objects.requireNonNull(feedback, "feedback");
        Objects.requireNonNull(chestChannel, "chestChannel");
    }
}
