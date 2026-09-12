package art.arcane.wormholes.api.network;

/**
 * One federated peer as of the last poll.
 *
 * @param name        the peer's server name
 * @param transport   TCP, SIDEBAND, or UDS
 * @param connected   whether the link is up and the handshake finished
 * @param rttMillis   last measured round trip
 * @param remotePortals how many of that peer's portals this server knows
 */
public record PeerSnapshot(String name, String transport, boolean connected, long rttMillis, int remotePortals) {
}
