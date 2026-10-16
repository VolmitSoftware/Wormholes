package art.arcane.wormholes.menu;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;

public final class MenuNotices {
    private MenuNotices() {
    }

    public static MenuNotice of(MenuViewer viewer, TextKey key, MessageArgs arguments, MenuFeedbackStyle style, NoticeChannel channel) {
        return new MenuNotice(new MenuFeedback(style, viewer.strings().text(key, arguments)), channel);
    }

    public static MenuNotice hud(MenuViewer viewer, TextKey key) {
        return of(viewer, key, MessageArgs.empty(), MenuFeedbackStyle.ERROR, NoticeChannel.HUD);
    }

    public static MenuNotice success(MenuViewer viewer, TextKey key, MessageArgs arguments) {
        return of(viewer, key, arguments, MenuFeedbackStyle.SUCCESS, NoticeChannel.SUCCESS_EFFECT);
    }

    public static MenuNotice failure(MenuViewer viewer, TextKey key, MessageArgs arguments) {
        return of(viewer, key, arguments, MenuFeedbackStyle.ERROR, NoticeChannel.FAILURE_EFFECT);
    }

    public static MenuNotice nativeOnly(MenuViewer viewer, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
        return of(viewer, key, arguments, style, NoticeChannel.NONE);
    }

    public static MenuNotice text(String text, MenuFeedbackStyle style, NoticeChannel channel) {
        return new MenuNotice(new MenuFeedback(style, text), channel);
    }
}
