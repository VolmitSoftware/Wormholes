package art.arcane.wormholes.menu;

public enum MenuStyle {
    NORMAL(0),
    PRIMARY(1),
    DESTRUCTIVE(2);

    private final int wireId;

    MenuStyle(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static MenuStyle byWireId(int wireId) {
        for (MenuStyle style : values()) {
            if (style.wireId == wireId) {
                return style;
            }
        }
        return null;
    }
}
