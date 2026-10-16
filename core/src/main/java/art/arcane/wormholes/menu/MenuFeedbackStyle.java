package art.arcane.wormholes.menu;

public enum MenuFeedbackStyle {
    INFO(0),
    SUCCESS(1),
    WARNING(2),
    ERROR(3);

    private final int wireId;

    MenuFeedbackStyle(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static MenuFeedbackStyle byWireId(int wireId) {
        for (MenuFeedbackStyle style : values()) {
            if (style.wireId == wireId) {
                return style;
            }
        }
        return null;
    }
}
