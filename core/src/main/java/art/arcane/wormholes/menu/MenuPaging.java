package art.arcane.wormholes.menu;

public record MenuPaging(int page, int pageCount) {
    public MenuPaging {
        if (pageCount < 1) {
            throw new IllegalArgumentException("page count " + pageCount + " must be at least 1");
        }
        if (page < 0 || page >= pageCount) {
            throw new IllegalArgumentException("page " + page + " is outside 0.." + (pageCount - 1));
        }
    }

    public boolean contains(int candidate) {
        return candidate >= 0 && candidate < pageCount;
    }
}
