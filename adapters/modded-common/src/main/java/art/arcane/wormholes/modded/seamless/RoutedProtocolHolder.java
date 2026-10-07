package art.arcane.wormholes.modded.seamless;

import net.minecraft.network.ProtocolInfo;

public interface RoutedProtocolHolder {
    ProtocolInfo<?> wormholesInboundProtocol();

    ProtocolInfo<?> wormholesOutboundProtocol();
}
