package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewMessage;

public final class ClientViewAnnouncer {
    private ClientViewMessage.Accept announced;

    public boolean due(boolean enabled, boolean active, ClientViewMessage.Accept accept, boolean playerPresent) {
        if (!enabled || !active || accept == null || !playerPresent || accept == announced) {
            return false;
        }
        announced = accept;
        return true;
    }
}
