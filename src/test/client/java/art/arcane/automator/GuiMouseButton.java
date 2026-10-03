package art.arcane.automator;

final class GuiMouseButton {
    private GuiMouseButton() {
    }

    static int fromProtocol(int button) {
        return switch (button) {
            case 0 -> 1;
            case 1 -> 3;
            default -> throw new IllegalArgumentException("GUI button must be left or right");
        };
    }
}
