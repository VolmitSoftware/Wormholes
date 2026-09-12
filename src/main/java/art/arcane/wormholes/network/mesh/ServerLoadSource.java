package art.arcane.wormholes.network.mesh;

/** Live load numbers behind a {@link LoadBeacon}; the Bukkit implementation lives in {@link BukkitServerLoadSource}. */
public interface ServerLoadSource {
    /** No server behind the manager (tests, early boot): empty and healthy. */
    ServerLoadSource EMPTY = new ServerLoadSource() {
        @Override
        public int online() {
            return 0;
        }

        @Override
        public int max() {
            return 0;
        }

        @Override
        public int reserved() {
            return 0;
        }

        @Override
        public double tps() {
            return 20.0D;
        }

        @Override
        public double msptP95() {
            return 0.0D;
        }
    };

    int online();

    /** Player cap; 0 means unlimited. */
    int max();

    /** Inbound handoff reservations that have not arrived yet. */
    int reserved();

    double tps();

    double msptP95();
}
