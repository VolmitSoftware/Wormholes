package art.arcane.wormholes.network;

@FunctionalInterface
public interface PeerMessageSender {
    boolean send(String peerName, WireMessage message);
}
