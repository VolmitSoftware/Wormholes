package art.arcane.wormholes.network.convoy;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.WireMessageType;

/**
 * Inbound wire handlers for the convoy channel. CONVOY_TRANSFER and CONVOY_ACK are consumed; HANDOFF_RESULT
 * is observed and left for the legacy router. Services are resolved lazily because handlers are registered
 * before the traversal service exists.
 */
public final class ConvoyHandlers {
    private ConvoyHandlers() {
    }

    public static void register(WormholesRegistrar registrar, Supplier<ConvoyTransferService> transfers,
                                Supplier<ConvoyArrivalPlacer> arrivals, LongSupplier timeoutMillis) {
        registrar.wireHandler(WireMessageType.CONVOY_TRANSFER, (peerName, message) -> {
            ConvoyArrivalPlacer placer = arrivals.get();
            if (placer != null && message instanceof WireMessage.ConvoyTransfer transfer) {
                placer.admit(peerName, transfer.manifest(), timeoutMillis.getAsLong());
            }
            return true;
        });
        registrar.wireHandler(WireMessageType.CONVOY_ACK, (peerName, message) -> {
            ConvoyTransferService service = transfers.get();
            if (service != null && message instanceof WireMessage.ConvoyAck ack) {
                service.onAck(peerName, ack);
            }
            return true;
        });
        registrar.wireHandler(WireMessageType.HANDOFF_RESULT, (peerName, message) -> {
            ConvoyTransferService service = transfers.get();
            if (service != null && message instanceof WireMessage.HandoffResult result) {
                service.onHandoffResult(peerName, result);
            }
            return false;
        });
    }
}
