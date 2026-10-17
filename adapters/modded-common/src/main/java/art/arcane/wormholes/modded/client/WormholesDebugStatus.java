package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.menu.protocol.MenuServerInfo;

import java.util.Objects;

public record WormholesDebugStatus(ClientViewSession.ConnectionStatus connection, String clientVersion, MenuServerInfo server,
                                   Protocols protocols, Activity activity) {
    public WormholesDebugStatus {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(clientVersion, "clientVersion");
        Objects.requireNonNull(protocols, "protocols");
        Objects.requireNonNull(activity, "activity");
    }

    public boolean serverKnown() {
        return server != null;
    }

    public record Protocols(int viewWire, boolean view, boolean travel, int menus) {
    }

    public record Activity(int armed, int drawn, int streams, int residents, Travel travel) {
        public Activity {
            Objects.requireNonNull(travel, "travel");
        }
    }

    public enum Travel {
        IDLE("idle"),
        ARMED("armed"),
        AWAITING("awaiting server"),
        CROSSING("crossing");

        private final String label;

        Travel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
