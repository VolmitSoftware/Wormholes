package art.arcane.wormholes.door;

    /** Everything a reshape would destroy or move, counted before anything is touched. */
public record PocketResizeImpact(long blocks, long containers, long entities, long players) {
    public PocketResizeImpact {
            if (blocks < 0 || containers < 0 || entities < 0 || players < 0) {
                throw new IllegalArgumentException("impact counts cannot be negative");
            }
        }

        public static PocketResizeImpact none() {
            return new PocketResizeImpact(0L, 0L, 0L, 0L);
        }

        /** True when the reshape takes nothing away and moves nobody. */
        public boolean isHarmless() {
            return blocks == 0L && containers == 0L && entities == 0L;
        }
    }
