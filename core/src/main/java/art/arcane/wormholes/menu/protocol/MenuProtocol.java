package art.arcane.wormholes.menu.protocol;

public final class MenuProtocol {
    public static final String NAMESPACE = "wormholes";
    public static final String PATH = "menu";
    public static final String CHANNEL = NAMESPACE + ":" + PATH;
    public static final int VERSION = 1;

    private MenuProtocol() {
    }
}
