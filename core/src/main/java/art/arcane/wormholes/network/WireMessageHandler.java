package art.arcane.wormholes.network;

/**
 * Lane-registered inbound handler for one {@link WireMessageType}. Runs on the peer reader thread (or
 * the status-poll executor for sideband traffic); must not block. Return true when the message was
 * consumed so the legacy router does not see it.
 */
public interface WireMessageHandler {
    boolean handle(String peerName, WireMessage message);
}
