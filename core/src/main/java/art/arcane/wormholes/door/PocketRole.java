package art.arcane.wormholes.door;

/** Roster standing inside one pocket. Higher ranks include everything the lower ones may do. */
public enum PocketRole {
    VISITOR(0),
    BUILDER(1),
    OWNER(2);

    private final int rank;

    PocketRole(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean atLeast(PocketRole required) {
        return rank >= required.rank;
    }
}
