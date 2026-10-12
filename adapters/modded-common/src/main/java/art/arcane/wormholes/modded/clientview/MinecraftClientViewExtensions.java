package art.arcane.wormholes.modded.clientview;

import java.util.List;

import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamExtension;
import art.arcane.wormholes.network.client.FxExtension;
import art.arcane.wormholes.network.client.LocalDestinationExtension;
import art.arcane.wormholes.network.client.TravelExtension;
import art.arcane.wormholes.network.client.RootSourceExtension;

public final class MinecraftClientViewExtensions {
    public static final List<ViewStreamExtension<?>> ALL = List.of(FxExtension.INSTANCE, TravelExtension.INSTANCE, RootSourceExtension.INSTANCE,
        LocalDestinationExtension.INSTANCE);
    public static final ViewStreamCodec CODEC = new ViewStreamCodec(ALL);

    private MinecraftClientViewExtensions() {
    }
}
