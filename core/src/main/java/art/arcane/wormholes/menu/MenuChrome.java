package art.arcane.wormholes.menu;

public record MenuChrome(
    String back,
    String close,
    String apply,
    String confirm,
    String cancel,
    String previousPage,
    String nextPage,
    String pageLabel,
    String useHeldItem,
    String aim,
    String timeout
) {
    public static final MenuChrome EMPTY = new MenuChrome("", "", "", "", "", "", "", "", "", "", "");

    public MenuChrome {
        back = MenuText.text(back, "chrome back");
        close = MenuText.text(close, "chrome close");
        apply = MenuText.text(apply, "chrome apply");
        confirm = MenuText.text(confirm, "chrome confirm");
        cancel = MenuText.text(cancel, "chrome cancel");
        previousPage = MenuText.text(previousPage, "chrome previous page");
        nextPage = MenuText.text(nextPage, "chrome next page");
        pageLabel = MenuText.text(pageLabel, "chrome page label");
        useHeldItem = MenuText.text(useHeldItem, "chrome use held item");
        aim = MenuText.text(aim, "chrome aim");
        timeout = MenuText.text(timeout, "chrome timeout");
    }
}
