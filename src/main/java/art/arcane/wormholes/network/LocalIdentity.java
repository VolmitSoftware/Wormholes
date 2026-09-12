package art.arcane.wormholes.network;

import java.security.PrivateKey;

public record LocalIdentity(
    String serverName,
    String mcVersion,
    String pluginVersion,
    String advertiseHost,
    int wormholePort,
    GameEndpoint gameEndpoint,
    GameEndpoint privateGameEndpoint,
    byte[] publicKey,
    PrivateKey privateKey,
    /** The capability set this server advertises right now, which its configuration can reduce. */
    long capabilities
) {
}
