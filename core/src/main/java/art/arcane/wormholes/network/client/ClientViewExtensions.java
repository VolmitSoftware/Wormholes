package art.arcane.wormholes.network.client;

import java.util.List;

import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamExtension;

public final class ClientViewExtensions {
    public static final List<ViewStreamExtension<?>> ALL = List.of(FxExtension.INSTANCE, TravelExtension.INSTANCE);
    public static final ViewStreamCodec CODEC = new ViewStreamCodec(ALL);

    private ClientViewExtensions() {
    }
}
