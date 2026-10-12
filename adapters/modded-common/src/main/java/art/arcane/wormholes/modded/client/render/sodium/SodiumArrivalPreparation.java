package art.arcane.wormholes.modded.client.render.sodium;

public final class SodiumArrivalPreparation {
    private long section = Long.MIN_VALUE;
    private long revision = Long.MIN_VALUE;
    private boolean incomplete;

    public boolean needed(long section, long revision) {
        return incomplete || this.section != section || this.revision != revision;
    }

    public void prepared(long section, long revision, boolean complete) {
        this.section = section;
        this.revision = revision;
        incomplete = !complete;
    }
}
