package art.arcane.wormholes.menu.portal;

public record GatewayView(String destinationName, String server, GatewayLinkState link) {
    public static final GatewayView UNPAIRED = new GatewayView(null, null, null);

    public GatewayView {
        if ((server == null) != (link == null)) {
            throw new IllegalArgumentException("gateway server and link state are set together");
        }
        if (server != null && destinationName == null) {
            throw new IllegalArgumentException("a gateway peer needs a paired destination");
        }
    }

    public boolean paired() {
        return destinationName != null;
    }
}
