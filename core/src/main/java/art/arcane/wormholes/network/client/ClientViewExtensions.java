package art.arcane.wormholes.network.client;

import java.util.List;

import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamExtension;

public final class ClientViewExtensions {
    public static final long FX_EMITTERS = 1L << ViewStreamCapability.FIRST_EXTENSION_BIT;
    public static final long PREPARED_TRAVEL = FX_EMITTERS << 1;
    public static final long PREPARED_TRAVEL_CACHE = FX_EMITTERS << 2;
    public static final long REMOTE_VIEW = FX_EMITTERS << 3;
    public static final long SEAMLESS_TRAVEL = FX_EMITTERS << 4;
    public static final List<ViewStreamExtension<?>> ALL = List.of(FxExtension.INSTANCE, TravelExtension.PREPARED);
    public static final ViewStreamCodec CODEC = new ViewStreamCodec(ALL);

    private ClientViewExtensions() {
    }
}
