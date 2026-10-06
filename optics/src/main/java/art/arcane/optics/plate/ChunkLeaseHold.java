package art.arcane.optics.plate;


public record ChunkLeaseHold(ChunkLease lease) implements PlateCaptureJob.Hold {
    @Override
    public boolean settled() {
        return lease.ready().isDone();
    }

    @Override
    public boolean ready() {
        return Boolean.TRUE.equals(lease.ready().getNow(Boolean.FALSE));
    }

    @Override
    public void release() {
        lease.close();
    }
}
