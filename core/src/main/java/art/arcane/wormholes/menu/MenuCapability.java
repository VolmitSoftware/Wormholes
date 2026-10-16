package art.arcane.wormholes.menu;

public record MenuCapability(int version) {
    public MenuCapability {
        if (version < 1 || version > 255) {
            throw new IllegalArgumentException("menu capability version " + version + " is outside 1..255");
        }
    }
}
