package art.arcane.wormholes.modded.clientview;

import java.util.List;

import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamExtension;
import art.arcane.wormholes.network.client.FxExtension;
import art.arcane.wormholes.network.client.SeamlessTravelCodec;
import art.arcane.wormholes.network.client.TravelExtension;

public final class MinecraftClientViewExtensions {
    public static final List<ViewStreamExtension<?>> ALL = List.of(FxExtension.INSTANCE, new TravelExtension(SeamlessTravelCodec.INSTANCE));
    public static final ViewStreamCodec CODEC = new ViewStreamCodec(ALL);

    private MinecraftClientViewExtensions() {
    }
}
